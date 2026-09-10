package repit.repit_api_server.domain.metadata.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 붙어 있는 구독에 주기적으로 빈 줄을 흘린다.
 *
 * <p>분석은 끝날 때까지 아무 이벤트도 내보내지 않는다. 그동안 연결에 바이트가 하나도 흐르지
 * 않으면 중간의 프록시나 터널이 놀고 있는 연결로 보고 조용히 끊는다. 클라이언트는 다시 붙지만,
 * 그 사이에 콜백이 도착하면 이미 죽은 연결에 완료 이벤트를 쓰다 broken pipe로 잃는다.
 *
 * <p>끊긴 연결을 일찍 알아채는 효과도 같이 얻는다. SSE는 다음 쓰기를 시도할 때까지 상대가
 * 떠난 줄 모르므로, 아무것도 보내지 않으면 콜백이 도착하는 순간에야 알게 된다.
 *
 * <p>쓰는 일은 구독마다 따로 띄운다. 구독에 쓰는 것은 블로킹이라, 받는 쪽이 읽지 않아 소켓
 * 버퍼가 차면 그 write는 소켓 타임아웃까지 멈춰 있다. 한 스레드에서 순회하며 쓰면 그 대기에
 * 뒤 순서 구독들이 그 틱의 ping을 통째로 잃는다. 15초 주기인데 한 번 밀리면 멀쩡한 연결이
 * 프록시의 유휴 타임아웃에 걸려 끊기므로, 막힌 구독 하나가 나머지를 끌고 들어가지 않게 떼어둔다.
 */
@Component
public class SseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(SseHeartbeat.class);

    private final SseEmitterRepository sseEmitterRepository;
    private final Executor pingExecutor;

    /**
     * 지금 ping이 날아가 있는 구독들.
     *
     * <p>막힌 구독은 한 틱 안에 끝나지 않는다. 다음 틱이 또 띄우면 그 구독에만 작업이 쌓여,
     * "하트비트가 밀린다"가 "작업이 무한히 쌓인다"로 바뀐다. 구독마다 하나까지만 띄운다.
     *
     * <p>jobId가 아니라 구독 자체를 센다. jobId로 세면 다시 붙은 새 구독이 앞 구독의 막힌
     * ping 때문에 ping을 받지 못한다. {@link SseEmitter}는 동등성을 재정의하지 않아 여기서는
     * 객체가 같은지로만 가린다 — 바라는 바다.
     *
     * <p>구독을 들고 있지만 늘어나지는 않는다. 들어온 구독은 ping이 끝나는 자리에서 반드시
     * 걷히고, 구독마다 하나까지만 띄우므로 동시에 담기는 수는 띄워둔 ping 수를 넘지 못한다 —
     * 곧 스레드 수와 큐 크기의 합이 한계다. 막힌 구독이 오래 남아도 그 상한 안에 머문다.
     */
    private final Set<SseSubscription> pinging = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public SseHeartbeat(SseEmitterRepository sseEmitterRepository,
                        @Qualifier("ssePingExecutor") Executor pingExecutor) {
        this.sseEmitterRepository = sseEmitterRepository;
        this.pingExecutor = pingExecutor;
    }

    @Scheduled(fixedDelayString = "${app.sse.heartbeat-interval-ms:15000}")
    public void ping() {
        sseEmitterRepository.forEach(this::schedulePing);
    }

    /**
     * 한 구독의 ping을 띄운다.
     *
     * <p>띄우지 못한 ping은 흘려보낸다. 다음 틱에 다시 나가므로 여기서 붙잡고 기다릴 이유가 없다 —
     * 기다리면 순회하는 스레드가 막혀 떼어둔 뜻이 없어진다.
     *
     * <p>어떤 실패도 밖으로 내보내지 않는다. 여기서 예외가 새어나가면 순회가 통째로 멈춰,
     * 그 뒤 순서의 구독들이 그 틱의 ping을 받지 못한다. 한 구독을 띄우다 난 일이 나머지를
     * 끌고 들어가지 않게 한다.
     */
    private void schedulePing(String jobId, SseSubscription emitter) {
        try {
            if (!pinging.add(emitter)) {
                // 앞 틱의 ping이 아직 이 구독에서 끝나지 않았다. 덧붙이면 막힌 구독에만 쌓인다.
                return;
            }

            pingExecutor.execute(() -> {
                try {
                    ping(jobId, emitter);
                } finally {
                    pinging.remove(emitter);
                }
            });
        } catch (RuntimeException e) {
            // 큐가 찼거나 띄우는 도중 실패했다. 표시를 걷어내지 않으면 이 구독은 다시는 ping을 받지 못한다.
            pinging.remove(emitter);
            log.debug("ping을 띄우지 못해 다음 차례로 넘깁니다. jobId={}, 원인: {}", jobId, e.toString());
        }
    }

    /**
     * 한 구독에 빈 줄을 흘린다.
     *
     * <p>어떤 실패든 여기서 삼킨다. 예외가 새어나가면 이 구독의 표시가 남아 다시는 ping을
     * 받지 못한다.
     */
    private void ping(String jobId, SseSubscription emitter) {
        try {
            // 주석 줄이라 클라이언트의 이벤트 처리에는 걸리지 않는다. 연결을 살려두는 용도다.
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | RuntimeException e) {
            // 이미 떠난 구독이다. 여기서 걷어내야 콜백이 도착했을 때 죽은 연결에 쓰지 않는다.
            if (sseEmitterRepository.remove(jobId, emitter)) {
                log.debug("응답하지 않는 구독을 걷어냈습니다. jobId={}", jobId);
                SseEmitters.completeQuietly(emitter);
            }
        }
    }
}
