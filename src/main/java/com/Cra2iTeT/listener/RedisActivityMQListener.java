package com.Cra2iTeT.listener;

import cn.hutool.core.date.DateTime;
import com.Cra2iTeT.domain.Activity;
import com.Cra2iTeT.service.ActivityService;
import com.Cra2iTeT.util.BloomFilter;
import com.Cra2iTeT.util.LocalCacheFilter;
import com.alibaba.fastjson.JSON;
import org.redisson.Redisson;
import org.redisson.api.RLock;
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
    @Scheduled(fixedRate = 30 * 60 * 1000)
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
    @Scheduled(fixedRate = 30 * 60 * 1000)
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
            RLock activityLock = redisson.getLock("lock:activity:" + activityId);
            try {
                boolean isLock = activityLock.tryLock(300, TimeUnit.MILLISECONDS);
                if (isLock) {
                    // 下架活动
                    stringRedisTemplate.opsForValue().getAndDelete("activity:" + activityId);
                    // 清空库存
                    // 锁住库存
                    RLock stockLock0 = redisson.getLock("lock:activity:stock0:" + activity);
                    RLock stockLock1 = redisson.getLock("lock:activity:stock1:" + activity);
                    CompletableFuture<Void> clearFuture2 = clearStock(stockLock0, 0, activityId);
                    CompletableFuture<Void> clearFuture1 = clearStock(stockLock1, 1, activityId);

                    clearFuture1.join();
                    clearFuture2.join();
                    // 删除消息
                    stringRedisTemplate.opsForZSet().remove("mq:activity:down", activityId);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                if (activityLock.isLocked() && activityLock.isHeldByCurrentThread()) {
                    activityLock.unlock();
                }
            }
        }
    }

    private CompletableFuture<Void> clearStock(RLock stockLock, int idx, long activityId) {
        return CompletableFuture.runAsync(() -> {
            stockLock.lock(300, TimeUnit.MILLISECONDS);
            try {
                stringRedisTemplate.opsForHash().delete("activity:stock" + idx, activityId);
            } finally {
                if (stockLock.isLocked() && stockLock.isHeldByCurrentThread()) {
                    stockLock.unlock();
                }
            }
        }, executor);
    }

    private void consumeActivityUpSetMQ(Long activityId) {
        if (bloomFilter.mightContain("bloom:activity", activityId) ||
                StringUtils.isEmpty(stringRedisTemplate.opsForValue().get("activity:" + activityId))) {
            return;
        }
        RLock activityLock = redisson.getLock("lock:activity:" + activityId);
        try {
            boolean isLock = activityLock.tryLock(300, TimeUnit.MILLISECONDS);
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
                            // 设置过滤器
                            bloomFilter.add("bloom:activity", activityId);
                            localCacheFilter.add(String.valueOf(activityId), new AtomicLong(activity.getStock()));
                            // 删除消息
                            stringRedisTemplate.opsForZSet().remove("mq:activity", activityId);
                        }
                    }
                } finally {
                    if (activityLock.isLocked() && activityLock.isHeldByCurrentThread()) {
                        activityLock.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void setStock(Long activityId, int stock, int idx) {
        stringRedisTemplate.opsForHash().put("activity:stock" + idx, activityId, stock);
    }
}
