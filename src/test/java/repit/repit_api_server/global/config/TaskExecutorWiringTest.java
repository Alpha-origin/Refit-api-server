package repit.repit_api_server.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주기 작업과 SSE ping이 쓰는 스레드 풀이 실제로 의도대로 떠 있는지 확인한다.
 *
 * <p>둘 다 설정 한 줄에 달려 있어 조용히 어긋난다. 어긋나도 예외가 나지 않고 기본값으로 돌아가므로,
 * 컨텍스트를 띄워 직접 보지 않으면 드러나는 자리가 없다.
 */
@SpringBootTest
class TaskExecutorWiringTest {

    @Autowired
    private ApplicationContext context;

    /**
     * ssePingExecutor를 등록하면 Boot의 applicationTaskExecutor 자동설정이 꺼진다.
     *
     * <p>그 빈은 {@code @ConditionalOnMissingBean(Executor.class)} 또는
     * {@code spring.task.execution.mode=force}일 때만 만들어진다. 이 앱에는 Executor 타입 빈이
     * 없어서 늘 걸렸는데, ping용 풀이 그 조건을 깼다. mode=force로 되살려 둔 것이 유지되는지 본다.
     *
     * <p>꺼지면 MVC async가 SimpleAsyncTaskExecutor로 내려가 호출마다 새 스레드를 만든다.
     * 지금은 @Async도 Callable 반환도 없어 증상이 없지만, 생기는 순간 원인을 여기까지 되짚기 어렵다.
     */
    @Test
    void applicationTaskExecutor가_살아있다() {
        assertThat(context.containsBean("applicationTaskExecutor"))
                .as("ssePingExecutor 때문에 자동설정이 꺼졌다면 spring.task.execution.mode=force가 빠진 것이다")
                .isTrue();
    }

    @Test
    void ssePingExecutor가_떠_있고_한계가_정해져_있다() {
        ThreadPoolTaskExecutor executor = context.getBean("ssePingExecutor", ThreadPoolTaskExecutor.class);

        // 막힌 write는 인터럽트로 깨지지 않는다. 이 수만큼은 동시에 막혀 있어도 나머지 구독이 ping을 받는다.
        assertThat(executor.getCorePoolSize()).isEqualTo(8);
        assertThat(executor.getMaxPoolSize()).isEqualTo(8);
        assertThat(executor.getThreadPoolExecutor().isShutdown()).isFalse();
    }

    /**
     * 종료는 인터럽트로 알리는데 블로킹 소켓 write는 인터럽트로 깨지지 않는다.
     * 논데몬으로 두면 그 write가 끝날 때까지 JVM이 남아 종료가 소켓 타임아웃만큼 늘어진다.
     */
    @Test
    void ping_스레드는_JVM_종료를_붙잡지_않는다() throws Exception {
        ThreadPoolTaskExecutor executor = context.getBean("ssePingExecutor", ThreadPoolTaskExecutor.class);

        CompletableFuture<Boolean> daemon = new CompletableFuture<>();
        executor.execute(() -> daemon.complete(Thread.currentThread().isDaemon()));

        assertThat(daemon.get(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 스케줄러 스레드가 하나면 SSE 하트비트(15초)와 면접 준비 sweep(30초)이 서로를 밀어낸다.
     * 기본값이 1이라 설정이 빠지면 조용히 그 상태로 돌아간다.
     */
    @Test
    void 스케줄러가_스레드를_하나만_쓰지_않는다() {
        ThreadPoolTaskScheduler scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);

        // getPoolSize()가 아니라 설정값을 본다. 앞은 지금까지 실제로 만들어진 스레드 수라,
        // 스레드가 필요할 때 생기는 탓에 재는 시점에 따라 달라진다.
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThan(1);
    }
}
