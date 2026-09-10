package repit.repit_api_server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** SSE 하트비트처럼 요청 밖에서 도는 작업을 위한 스케줄러. */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * 하트비트가 구독에 빈 줄을 흘릴 때 쓰는 스레드들.
     *
     * <p>구독에 쓰는 일은 블로킹이다. 받는 쪽이 읽지 않아 소켓 버퍼가 차면 그 write는 소켓
     * 타임아웃까지 멈춰 있고, 인터럽트로 깨울 수도 없다. 스케줄러 스레드에서 순회하며 쓰면
     * 그 대기에 뒤 순서 구독들이 그 틱의 ping을 통째로 잃는다.
     *
     * <p>구독마다 ping을 하나까지만 띄우므로(@code SseHeartbeat) 큐에 쌓이는 작업은 구독 수를
     * 넘지 않는다. 그래도 한계를 정해두는 것은, 넘쳤을 때 조용히 쌓이는 대신 거절로 드러나게
     * 하기 위해서다. 거절된 ping은 다음 틱에 다시 나간다.
     */
    @Bean
    public ThreadPoolTaskExecutor ssePingExecutor(
            @Value("${app.sse.ping-pool-size:8}") int poolSize,
            @Value("${app.sse.ping-queue-capacity:2000}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("sse-ping-");
        // 내려갈 때 막힌 write를 기다리지 않는다. 기다리면 소켓 타임아웃만큼 종료가 늦어진다.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
