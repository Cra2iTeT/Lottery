package com.Cra2iTeT.config;

import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author Cra2iTeT
 * @since 2023/3/6 10:29
 */
@Component
public class ThreadPoolConfig {
    @Bean
    public Executor MQListener() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        //最大线程数
        executor.setMaxPoolSize(2);
        //核心线程数
        executor.setCorePoolSize(1);
        //任务队列的大小
        executor.setQueueCapacity(20);
        //线程前缀名
        executor.setThreadNamePrefix("Redis-MQ-Listener-Executor-");
        //线程存活时间
        executor.setKeepAliveSeconds(30);

        /**
         * 拒绝处理策略
         * CallerRunsPolicy()：交由调用方线程运行，比如 main 线程。
         * AbortPolicy()：直接抛出异常。
         * DiscardPolicy()：直接丢弃。
         * DiscardOldestPolicy()：丢弃队列中最老的任务。
         */
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        //线程初始化
        executor.initialize();
        return executor;
    }
}
