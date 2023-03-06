package com.Cra2iTeT.listener;

import com.Cra2iTeT.domain.Activity;
import com.Cra2iTeT.service.ActivityService;
import com.Cra2iTeT.util.BloomFilter;
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

    @Async("MQListener")
    @Scheduled(fixedRate = 900000)
    public void ActivitySetListener() {
        long millis = System.currentTimeMillis();
        Set<String> zSet = stringRedisTemplate.opsForZSet()
                .range("mq:activity", millis, millis + 1800000);
        if (zSet == null || zSet.isEmpty()) {
            return;
        }
        for (String s : zSet) {
            consumerActivitySetMQ(Long.valueOf(s));
        }
    }

    private void consumerActivitySetMQ(Long activityId) {
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
