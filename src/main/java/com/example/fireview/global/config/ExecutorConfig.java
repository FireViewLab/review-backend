package com.example.fireview.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 외부 서버 호출용 스레드 풀.
 *
 * CompletableFuture.supplyAsync()에 executor를 넘기지 않으면 ForkJoinPool.commonPool()을 쓰는데,
 * 그 크기는 (CPU 코어 수 - 1)이라 2 vCPU 운영 서버에서는 스레드 1개다.
 * 블로킹 I/O(RestTemplate)를 그 풀에 올리면 "병렬" 호출이 사실상 순차 실행된다.
 *
 * 외부 호출은 CPU가 아니라 대기 시간이 지배적이므로 코어 수와 무관하게 풀을 잡는다.
 * 요청 하나가 AI 서버 3개 API를 동시에 부르므로 core=6이면 동시 요청 2건까지 대기 없이 처리된다.
 */
@Configuration
public class ExecutorConfig {

    public static final String AI_CALL_EXECUTOR = "aiCallExecutor";
    public static final String CHAT_EXECUTOR = "chatExecutor";
    public static final String DATA_SERVER_EXECUTOR = "dataServerExecutor";
    public static final String REVIEW_SUMMARY_EXECUTOR = "reviewSummaryExecutor";

    @Bean(name = AI_CALL_EXECUTOR)
    public Executor aiCallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-call-");
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        // 풀과 큐가 모두 차면 호출 스레드가 직접 실행한다.
        // 요청을 버리는 대신 느려지는 쪽을 택해, AI 서버 지연이 예외로 번지지 않게 한다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 챗봇 LLM 호출 전용 풀.
     *
     * AI 분석 호출과 분리한 이유는 부하 성격이 다르기 때문이다.
     * 분석 호출은 수 초, LLM 챗봇은 수십 초가 걸릴 수 있어 한 풀에 섞으면
     * 챗봇이 분석 호출을 밀어낸다.
     *
     * 포화 시 CallerRunsPolicy 를 쓰지 않는다. 호출 스레드는 DeferredResult 를 만든
     * Tomcat 워커이고, 거기서 LLM 을 직접 기다리면 워커를 붙잡게 되어
     * 스레드 분리의 의미가 사라진다. 대신 큐를 넉넉히 두고 넘치면 거부한다.
     */
    @Bean(name = CHAT_EXECUTOR)
    public Executor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-llm-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(120);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Data 서버 검색용 풀. 쇼핑몰 여러 곳을 동시에 부른다.
     *
     * <p>AI 호출 풀과 나눈 이유: 검색은 사용자가 화면 앞에서 기다리는 짧은 호출이고,
     * AI 분석은 길게 붙잡는 호출이다. 같은 풀을 쓰면 분석이 몰릴 때 검색이 줄을 선다.
     *
     * <p>포화 시 CallerRunsPolicy — 호출 스레드가 직접 돌린다. 검색 요청 스레드는 어차피
     * 결과를 기다려야 하므로 거절하는 것보다 느려지는 편이 낫다.
     */
    @Bean(name = DATA_SERVER_EXECUTOR)
    public Executor dataServerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("data-server-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 상품 리뷰 요약 생성용 풀(#277). 상세 조회가 요약을 기다리지 않도록 뒤에서 만든다.
     *
     * <p>작게 둔다. 요약은 급하지 않고, 한꺼번에 몰리면 LLM 비용과 Data 서버 부하만 커진다.
     * 포화 시 거부한다 — 호출 스레드는 상세 조회 요청이라 거기서 LLM 을 기다리면 안 된다.
     * 거부된 상품은 다음 조회 때 다시 요청된다.
     */
    @Bean(name = REVIEW_SUMMARY_EXECUTOR)
    public Executor reviewSummaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("review-summary-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
