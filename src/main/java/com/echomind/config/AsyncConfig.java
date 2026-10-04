package com.echomind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 显式定义全部线程池，避免三类隐患：
 * 1. Agent 并行分支/知识检索子查询落在 ForkJoinPool.commonPool 上——
 *    该池并行度为 CPU-1（容器里可能只有 1），且承载阻塞 I/O 属于误用；
 * 2. 二者共用一个池会形成“外层任务等待内层任务”的嵌套提交，饥饿甚至死锁，
 *    因此拆成 agentExecutor 与 toolExecutor 两个独立池；
 * 3. @Async 默认执行器的无界队列在高并发下无限堆积。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor(EchoMindProperties properties) {
        EchoMindProperties.Agent config = properties.getAgent();
        return newBoundedPool("agent-exec", config.getCorePoolSize(),
                Math.max(config.getCorePoolSize(), config.getMaxPoolSize()), config.getQueueCapacity(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Blocking leaf calls have their own capacity, separate from Agent orchestration and RAG workers. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService operationExecutor(EchoMindProperties properties) {
        EchoMindProperties.Agent config = properties.getAgent();
        return newBoundedPool("operation-exec", config.getOperationCorePoolSize(),
                Math.max(config.getOperationCorePoolSize(), config.getOperationMaxPoolSize()),
                config.getOperationQueueCapacity(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService toolExecutor() {
        return newBoundedPool("tool-exec", 4, 16, 200, new ThreadPoolExecutor.AbortPolicy());
    }

    /** @Async（如用户画像更新）使用的有界执行器。 */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("async-exec-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.initialize();
        return executor;
    }

    private static ExecutorService newBoundedPool(String namePrefix, int core, int max, int queueCapacity,
                                                 java.util.concurrent.RejectedExecutionHandler rejectionHandler) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 检索任务必须拒绝后显式降级，不能在请求线程同步执行而绕过 deadline。
        return new ThreadPoolExecutor(core, max, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, rejectionHandler);
    }
}
