package com.Cra2iTeT.listener;

import com.Cra2iTeT.domain.Activity;
import com.Cra2iTeT.service.ActivityService;
import com.alibaba.fastjson.JSON;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    StringRedisTemplate stringRedisTemplate;

    @Resource
    Redisson redisson;

    @Resource
    ActivityService activityService;

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
        Activity activity = activityService.getById(activityId);
        if (activity != null) {
            RLock rLock = redisson.getLock("lock:activity:" + activityId);
            rLock.lock(300, TimeUnit.MILLISECONDS);
            try {
                // 上架活动
                if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                        .setIfAbsent("activity:" + activity.getId(), JSON.toJSONString(activity)))) {
                    int stock0 = activity.getStock() / 2;
                    int stock1 = activity.getStock() - stock0;
                    setStock(activityId, stock0, 0);
                    setStock(activityId, stock1, 1);
                    // 删除消息
                    stringRedisTemplate.opsForZSet().remove("mq:activity", activityId);
                }
            } finally {
                if (rLock.isLocked() && rLock.isHeldByCurrentThread()) {
                    rLock.unlock();
                }
            }
        }
    }

    private void setStock(Long activityId, int stock, int idx) {
        stringRedisTemplate.opsForHash().put("activity:stock" + idx, activityId, stock);
    }
}
