package repit.repit_api_server.domain.metadata.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 하트비트는 두 가지를 한다. 놀고 있는 연결을 프록시가 끊지 못하게 붙잡아두고,
 * 이미 끊긴 연결은 콜백이 도착하기 전에 걷어낸다.
 */
class SseHeartbeatTest {

    private final SseEmitterRepository repository = new SseEmitterRepository();
    // 흐름을 그대로 좇으려고 같은 스레드에서 실행한다. 떼어둔 것을 확인하는 테스트는 각자 실행자를 세운다.
    private final SseHeartbeat heartbeat = new SseHeartbeat(repository, Runnable::run);

    @Test
    void 살아있는_구독에는_빈_줄을_흘리고_그대로_둔다() throws IOException {
        SseSubscription emitter = mock(SseSubscription.class);
        repository.save("job-1", emitter);

        heartbeat.ping();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(repository.get("job-1")).isSameAs(emitter);
    }

    /** 여기서 걷어내지 않으면 콜백이 도착했을 때 죽은 연결에 완료 이벤트를 쓰다 잃는다. */
    @Test
    void 끊긴_구독은_걷어내고_닫는다() throws IOException {
        SseSubscription gone = mock(SseSubscription.class);
        doThrow(new IOException("Broken pipe")).when(gone).send(any(SseEmitter.SseEventBuilder.class));
        repository.save("job-2", gone);

        heartbeat.ping();

        assertThat(repository.get("job-2")).isNull();
        verify(gone).complete();
    }

    /** 이미 끝난 구독에 쓰면 IllegalStateException이 난다. 이것도 걷어낼 대상이다. */
    @Test
    void 이미_끝난_구독도_걷어낸다() throws IOException {
        SseSubscription finished = mock(SseSubscription.class);
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(finished).send(any(SseEmitter.SseEventBuilder.class));
        repository.save("job-3", finished);

        heartbeat.ping();

        assertThat(repository.get("job-3")).isNull();
    }

    /** 한 구독이 끊겼다고 나머지 구독까지 못 받게 되면 안 된다. */
    @Test
    void 끊긴_구독이_있어도_나머지는_계속_흐른다() throws IOException {
        SseSubscription gone = mock(SseSubscription.class);
        doThrow(new IOException("Broken pipe")).when(gone).send(any(SseEmitter.SseEventBuilder.class));
        SseSubscription alive = mock(SseSubscription.class);
        repository.save("job-4", gone);
        repository.save("job-5", alive);

        heartbeat.ping();

        assertThat(repository.get("job-4")).isNull();
        assertThat(repository.get("job-5")).isSameAs(alive);
        verify(alive).send(any(SseEmitter.SseEventBuilder.class));
    }

    /**
     * 이미 에러로 끝난 구독은 닫는 것조차 거부당한다. 톰캣이 에러 처리가 끝난 AsyncContext를
     * 다시 쓰지 못하게 막기 때문이다. 그 거부가 새어나가면 순회가 멈춰 뒤 구독들이 ping을 잃는다.
     */
    @Test
    void 닫는_것마저_거부당해도_나머지는_계속_흐른다() throws IOException {
        SseSubscription dead = mock(SseSubscription.class);
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException(
                "A non-container (application) thread attempted to use the AsyncContext"))
                .when(dead).complete();
        SseSubscription alive = mock(SseSubscription.class);
        repository.save("job-6", dead);
        repository.save("job-7", alive);

        // 순회 순서는 보장되지 않는다. 어느 순서로 돌든 밖으로 새어나가는 것이 없어야 한다.
        assertThatCode(heartbeat::ping).doesNotThrowAnyException();

        assertThat(repository.get("job-6")).isNull();
        assertThat(repository.get("job-7")).isSameAs(alive);
        verify(alive).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** send가 IOException도 IllegalStateException도 아닌 것으로 터져도 순회는 이어져야 한다. */
    @Test
    void 예상하지_못한_실패도_순회를_멈추지_않는다() throws IOException {
        SseSubscription broken = mock(SseSubscription.class);
        doThrow(new RuntimeException("예상 못 한 실패"))
                .when(broken).send(any(SseEmitter.SseEventBuilder.class));
        SseSubscription alive = mock(SseSubscription.class);
        repository.save("job-8", broken);
        repository.save("job-9", alive);

        assertThatCode(heartbeat::ping).doesNotThrowAnyException();

        assertThat(repository.get("job-8")).isNull();
        verify(alive).send(any(SseEmitter.SseEventBuilder.class));
    }

    /**
     * 구독에 쓰는 것은 블로킹이다. 받는 쪽이 읽지 않으면 그 write는 소켓 타임아웃까지 멈춰 있다.
     * 한 스레드에서 순회하며 쓰면 그 대기에 뒤 순서 구독이 그 틱의 ping을 통째로 잃는다.
     */
    @Test
    void 막힌_구독이_있어도_나머지_구독은_ping을_받는다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        SseHeartbeat isolating = new SseHeartbeat(repository, pool);

        CountDownLatch release = new CountDownLatch(1);
        SseSubscription stuck = mock(SseSubscription.class);
        doAnswer(invocation -> {
            release.await();
            return null;
        }).when(stuck).send(any(SseEmitter.SseEventBuilder.class));

        CountDownLatch alivePinged = new CountDownLatch(1);
        SseSubscription alive = mock(SseSubscription.class);
        doAnswer(invocation -> {
            alivePinged.countDown();
            return null;
        }).when(alive).send(any(SseEmitter.SseEventBuilder.class));

        repository.save("job-10", stuck);
        repository.save("job-11", alive);

        try {
            isolating.ping();

            // 막힌 구독을 풀어주지 않은 채로 건너와야 한다. 떼어두지 않았다면 여기서 시간이 다 된다.
            assertThat(alivePinged.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 막힌 구독은 한 틱 안에 끝나지 않는다. 다음 틱이 또 띄우면 그 구독에만 작업이 쌓여,
     * "하트비트가 밀린다"가 "작업이 무한히 쌓인다"로 바뀐다.
     */
    @Test
    void 앞_틱의_ping이_아직_끝나지_않은_구독에는_또_띄우지_않는다() {
        List<Runnable> queued = new ArrayList<>();
        SseHeartbeat queueing = new SseHeartbeat(repository, queued::add);
        repository.save("job-12", mock(SseSubscription.class));

        queueing.ping();
        queueing.ping();

        assertThat(queued).hasSize(1);

        // 앞의 ping이 끝나면 다음 틱은 다시 띄운다. 한 번 막혔다고 영영 멈추면 안 된다.
        queued.getFirst().run();
        queueing.ping();

        assertThat(queued).hasSize(2);
    }

    /**
     * ping을 띄운 뒤 같은 jobId에 구독이 다시 붙을 수 있다. 뒤늦게 도착한 ping이 죽은 옛 구독을
     * 보고 jobId만으로 걷어내면, 방금 붙은 구독이 밀려나 정작 콜백이 왔을 때 흘려보낼 곳이 없다.
     */
    @Test
    void 뒤늦은_ping은_그_사이_다시_붙은_구독을_밀어내지_않는다() throws IOException {
        List<Runnable> queued = new ArrayList<>();
        SseHeartbeat queueing = new SseHeartbeat(repository, queued::add);

        // 떠난 구독이다. 쓰면 터진다.
        SseSubscription gone = mock(SseSubscription.class);
        doThrow(new IOException("Broken pipe")).when(gone).send(any(SseEmitter.SseEventBuilder.class));
        repository.save("job-14", gone);

        queueing.ping();

        // ping이 날아가 있는 동안 같은 작업에 새 구독이 붙었다.
        SseSubscription reconnected = mock(SseSubscription.class);
        repository.save("job-14", reconnected);

        // 이제야 옛 구독의 ping이 실행된다.
        queued.getFirst().run();

        assertThat(repository.get("job-14")).isSameAs(reconnected);
        verify(reconnected, never()).complete();
    }

    /** 한 구독을 띄우다 실패해도 순회가 멈추면 안 된다. 멈추면 뒤 구독들이 그 틱의 ping을 잃는다. */
    @Test
    void 한_구독을_띄우다_실패해도_나머지는_띄운다() {
        repository.save("job-15", mock(SseSubscription.class));
        repository.save("job-16", mock(SseSubscription.class));

        AtomicInteger attempts = new AtomicInteger();
        // 처음 한 번만 터뜨린다. 순회 순서는 보장되지 않으므로 어느 쪽이 먼저 걸리든 결과는 같아야 한다.
        Executor flakyOnFirst = task -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("띄우다 실패");
            }
            task.run();
        };

        assertThatCode(new SseHeartbeat(repository, flakyOnFirst)::ping).doesNotThrowAnyException();

        // 두 구독 모두 띄우기를 시도했다 — 첫 실패에 순회가 멈추지 않았다.
        assertThat(attempts).hasValue(2);
    }

    /** 띄우지 못한 ping은 다음 틱에 다시 나가야 한다. 표시가 남으면 그 구독은 영영 ping을 잃는다. */
    @Test
    void 큐가_차서_띄우지_못해도_다음_틱에_다시_시도한다() {
        AtomicInteger attempts = new AtomicInteger();
        Executor rejecting = task -> {
            attempts.incrementAndGet();
            throw new RejectedExecutionException("큐가 찼습니다.");
        };
        SseHeartbeat rejected = new SseHeartbeat(repository, rejecting);
        repository.save("job-13", mock(SseSubscription.class));

        assertThatCode(rejected::ping).doesNotThrowAnyException();
        assertThatCode(rejected::ping).doesNotThrowAnyException();

        assertThat(attempts).hasValue(2);
    }
}
