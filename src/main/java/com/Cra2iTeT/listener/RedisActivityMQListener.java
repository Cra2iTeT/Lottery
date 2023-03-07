package com.Cra2iTeT.listener;

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

    // TODO 活动下架消息监听

    @Async("MQListener")
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void ActivitySetListener() {
        long millis = System.currentTimeMillis();
        // 接受半小时以后的消息
        Set<String> zSet = stringRedisTemplate.opsForZSet()
                .range("mq:activity", millis + 30 * 60 * 1000, millis + 60 * 60 * 1000);
        if (zSet == null || zSet.isEmpty()) {
            return;
        }
        for (String s : zSet) {
            consumeActivitySetMQ(Long.valueOf(s));
        }
    }

    private void consumeActivitySetMQ(Long activityId) {
        if (bloomFilter.mightContain("bloom:activity", activityId) ||
                StringUtils.isEmpty(stringRedisTemplate.opsForValue().get("activity:" + activityId))) {
            return;
        }
        RLock rLock = redisson.getLock("lock:activity:" + activityId);
        try {
            boolean isLock = rLock.tryLock(300, TimeUnit.MILLISECONDS);
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
                    if (rLock.isLocked() && rLock.isHeldByCurrentThread()) {
                        rLock.unlock();
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
