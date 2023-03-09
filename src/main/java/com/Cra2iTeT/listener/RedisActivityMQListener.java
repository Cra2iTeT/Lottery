package com.Cra2iTeT.listener;

import cn.hutool.core.date.DateTime;
import com.Cra2iTeT.domain.Activity;
import com.Cra2iTeT.service.ActivityService;
import com.Cra2iTeT.util.BloomFilter;
import com.Cra2iTeT.util.LocalCacheFilter;
import com.alibaba.fastjson.JSON;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Cra2iTeT
 * @since 2023/3/6 12:31
 */
@Component
@EnableAsync
public class RedisActivityMQListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Redisson redisson;

    @Resource
    private ActivityService activityService;

    @Resource
    private BloomFilter bloomFilter;

    @Resource
    private LocalCacheFilter localCacheFilter;

    @Resource(name = "MainExecutor")
    private Executor executor;

    @Async("MQListener")
    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void ActivityUPSetListener() {
        long millis = System.currentTimeMillis();
        // 接受半小时以后的消息
        Set<String> zSet = stringRedisTemplate.opsForZSet()
                .range("mq:activity", millis + 30 * 60 * 1000, millis + 60 * 60 * 1000);
        if (zSet == null || zSet.isEmpty()) {
            return;
        }
        for (String s : zSet) {
            consumeActivityUpSetMQ(Long.valueOf(s));
        }
    }

    @Async("MQListener")
    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void ActivityDownSetListener() {
        long millis = System.currentTimeMillis();
        // 接受半小时以后的消息
        Set<String> zSet = stringRedisTemplate.opsForZSet()
                .range("mq:activity:down", millis + 30 * 60 * 1000, millis + 60 * 60 * 1000);
        if (zSet == null || zSet.isEmpty()) {
            return;
        }
        for (String s : zSet) {
            consumeActivityDownSetMQ(Long.valueOf(s));
        }
    }

    private void consumeActivityDownSetMQ(Long activityId) {
        if (bloomFilter.mightContain("bloom:activity", activityId)) {
            return;
        }
        String activityJson = stringRedisTemplate.opsForValue().get("activity:" + activityId);
        if (StringUtils.isEmpty(activityJson)) {
            return;
        }
        Activity activity = JSON.parseObject(activityJson, Activity.class);
        DateTime curDateTime = new DateTime();
        if (activity != null && curDateTime.isAfter(activity.getEndTime())) {
            RReadWriteLock activityLock = redisson.getReadWriteLock("lock:activity:" + activityId);
            RLock activityWriteLock = activityLock.writeLock();
            try {
                activityWriteLock.lock(300, TimeUnit.MILLISECONDS);
                // 下架活动
                stringRedisTemplate.opsForValue().getAndDelete("activity:" + activityId);
                // 清空库存
                CompletableFuture<Void> clearFuture2 = clearStock(0, activityId);
                CompletableFuture<Void> clearFuture1 = clearStock(1, activityId);
                // 清空过滤器,记录
                CompletableFuture.runAsync(() -> {
                    localCacheFilter.remove(String.valueOf(activityId));
                    bloomFilter.remove("bloom:activity", activityId);
                    stringRedisTemplate.opsForHash().delete("activity:raffleCount:max:" + activityId);
                }, executor);
                CompletableFuture.runAsync(() -> {
                    stringRedisTemplate.opsForHash().delete("activity:link:" + activityId);
                    bloomFilter.remove("activity:linkClick:" + activityId);
                });
                clearFuture1.join();
                clearFuture2.join();

                // 消费消息
                stringRedisTemplate.opsForZSet().remove("mq:activity:down", activityId);
            } finally {
                if (activityWriteLock.isLocked() && activityWriteLock.isHeldByCurrentThread()) {
                    activityWriteLock.unlock();
                }
            }
        }
    }

    private CompletableFuture<Void> clearStock(int idx, long activityId) {
        return CompletableFuture.runAsync(() -> {
            stringRedisTemplate.opsForHash().delete("activity:stock" + idx, activityId);
        }, executor);
    }

    private void consumeActivityUpSetMQ(Long activityId) {
        if (!StringUtils.isEmpty(stringRedisTemplate.opsForValue().get("activity:" + activityId))) {
            return;
        }
        RReadWriteLock activityLock = redisson.getReadWriteLock("lock:activity:" + activityId);
        RLock activityWriteLock = activityLock.writeLock();
        try {
            boolean isLock = activityWriteLock.tryLock(300, TimeUnit.MILLISECONDS);
            if (isLock) {
                Activity activity = activityService.getById(activityId);
                try {
                    if (activity != null) {
                        // 上架活动
                        if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                                .setIfAbsent("activity:" + activity.getId(), JSON.toJSONString(activity)))) {
                            // 上架库存
                            int stock0 = activity.getStock() / 2;
                            int stock1 = activity.getStock() - stock0;
                            setStock(activityId, stock0, 0);
                            setStock(activityId, stock1, 1);
                            // 生成下架消息
                            stringRedisTemplate.opsForZSet().add("mq:activity:down",
                                    String.valueOf(activityId), activity.getEndTime().getTime());
                            // 设置过滤器
                            bloomFilter.add("bloom:activity", activityId);
                            localCacheFilter.add(String.valueOf(activityId), new AtomicLong(activity.getStock()));
                            // 删除消息
                            stringRedisTemplate.opsForZSet().remove("mq:activity", String.valueOf(activityId));
                        }
                    }
                } finally {
                    if (activityWriteLock.isLocked() && activityWriteLock.isHeldByCurrentThread()) {
                        activityWriteLock.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void setStock(Long activityId, int stock, int idx) {
        stringRedisTemplate.opsForHash().put("activity:stock" + idx, String.valueOf(activityId),
                String.valueOf(stock));
    }
}
