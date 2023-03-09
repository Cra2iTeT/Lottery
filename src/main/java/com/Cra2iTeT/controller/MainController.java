package com.Cra2iTeT.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.NumberUtil;
import com.Cra2iTeT.commons.LocalUserInfo;
import com.Cra2iTeT.commons.R;
import com.Cra2iTeT.domain.Activity;
import com.Cra2iTeT.domain.RaffleCount;
import com.Cra2iTeT.domain.User;
import com.Cra2iTeT.service.ActivityService;
import com.Cra2iTeT.util.BloomFilter;
import com.alibaba.fastjson.JSON;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.xml.crypto.Data;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * @author Cra2iTeT
 * @since 2023/3/8 17:40
 */
@RestController
@RequestMapping("/main")
public class MainController {
    // TODO 活动上传 抽奖

    @Resource
    private ActivityService activityService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BloomFilter bloomFilter;

    @Resource
    private Redisson redisson;

    @Resource(name = "MainExecutor")
    private Executor executor;

    @RequestMapping("/up")
    public R<Void> up(@RequestBody Activity activity) {
        long curTime = System.currentTimeMillis();
//        if (curTime - activity.getStartTime().getTime() < 30 * 60 * 1000) {
//            return new R<>(401, "开始时间至少半小时之后");
//        }
        if (activity.getEndTime().before(activity.getStartTime())) {
            return new R<>(401, "时间不对");
        }
        activity.setId(IdUtil.getSnowflakeNextId());
        activityService.save(activity);
        stringRedisTemplate.opsForZSet().add("mq:activity", String.valueOf(activity.getId()),
                activity.getStartTime().getTime() - 30 * 60 * 1000);
        stringRedisTemplate.opsForZSet().add("mq:activity", String.valueOf(activity.getId()),
                activity.getStartTime().getTime() + 5 * 1000);
        return new R<>(200, "创建成功");
    }

    @RequestMapping("/raffle/{activityId}")
    public R raffle(@PathVariable("activityId") Long activityId) {
        if (!bloomFilter.mightContain("bloom:activity", activityId)) {
            return new R<Void>(401, "活动不存在");
        }

        RReadWriteLock activityLock = redisson
                .getReadWriteLock("lock:activity:" + activityId);

        RLock activityReadLock = activityLock.readLock();
        boolean isLock = activityReadLock.tryLock();
        if (isLock) {
            try {
                String activityJson = stringRedisTemplate.opsForValue().get("activity:" + activityId);
                if (StringUtils.isEmpty(activityJson)) {
                    return new R<Void>(401, "活动不存在");
                }
                Activity activity = JSON.parseObject(activityJson, Activity.class);
                Date now = new Date();
                if (activity.getStartTime().after(now) || activity.getEndTime().before(now)) {
                    return new R<Void>(401, "活动没开始");
                }

                String stock0V = (String) stringRedisTemplate.opsForHash()
                        .get("activity:stock0", String.valueOf(activityId));
                String stock1V = (String) stringRedisTemplate.opsForHash()
                        .get("activity:stock1", String.valueOf(activityId));
                int stock0 = StringUtils.isEmpty(stock0V) ? 0 : Integer.parseInt(stock0V);
                int stock1 = StringUtils.isEmpty(stock1V) ? 0 : Integer.parseInt(stock1V);
                if (stock0 <= 0 && stock1 <= 0) {
                    return new R<>(203, "慢了");
                }

                // TODO 不要读写锁
                User user = LocalUserInfo.get();
                String raffleCountJson = (String) stringRedisTemplate.opsForHash()
                        .get("activity:raffleCount:" + activityId, String.valueOf(user.getId()));
                RaffleCount raffleCount;
                if (!StringUtils.isEmpty(raffleCountJson)) {
                    raffleCount = JSON.parseObject(raffleCountJson, RaffleCount.class);
                    if (Objects.equals(raffleCount.getCount(), raffleCount.getTotalCount())) {
                        return new R<>(203, "没有抽奖次数了，可以去邀请好友获得");
                    }
                }

                if (tryRaffle(activityId, 0, user, activity.getStartTime().getTime()
                        , activity.getEndTime().getTime(), now.getTime()) && tryRaffle(activityId,
                        1, user, activity.getStartTime().getTime(), activity.getEndTime().getTime(),
                        now.getTime())) {
                    return new R<>(200, "恭喜");
                }
                return new R<>(203, "祝下次好运");
            } finally {
                if (activityReadLock.isLocked() && activityReadLock.isHeldByCurrentThread()) {
                    activityReadLock.unlock();
                }
            }
        } else {
            return new R<>(401, "活动正在调整，请稍后重试");
        }
    }

    private boolean tryRaffle(long activityId, int idx, User user, long startTime, long endTime, long curTime) {
        RReadWriteLock raffleCountLock = redisson
                .getReadWriteLock("lock:raffleCount" + activityId + ":" + user.getId());
        RLock raffleCountWriteLock = raffleCountLock.writeLock();
        try {
            boolean isLock = raffleCountWriteLock.tryLock(50, TimeUnit.MILLISECONDS);
            if (isLock) {
                try {
                    String raffleCountJson = (String) stringRedisTemplate.opsForHash()
                            .get("activity:raffleCount:" + activityId, String.valueOf(user.getId()));
                    RaffleCount raffleCount;
                    if (!StringUtils.isEmpty(raffleCountJson)) {
                        raffleCount = JSON.parseObject(raffleCountJson, RaffleCount.class);
                        if (Objects.equals(raffleCount.getCount(), raffleCount.getTotalCount())) {
                            return false;
                        }
                    } else {
                        raffleCount = new RaffleCount(1, 1);
                    }
                    if (isLucky(activityId, idx, user, startTime, endTime, curTime) &&
                            isLucky(activityId, idx, user, startTime, endTime, curTime)) {
                        stringRedisTemplate.opsForHash().put("activity:raffleCount:"
                                + activityId, String.valueOf(user.getId()), JSON.toJSONString(raffleCount));
                        return true;
                    }
                } finally {
                    if (raffleCountWriteLock.isLocked() && raffleCountWriteLock.isHeldByCurrentThread()) {
                        raffleCountWriteLock.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isLucky(long activityId, int idx, User user, long startTime, long endTime, long curTime) {
        RLock raffleLock = redisson.getLock("lock:activity:raffle:" + activityId);
        try {
            boolean isLock = raffleLock.tryLock(50, TimeUnit.MILLISECONDS);
            if (isLock) {
                try {
                    double seed = (curTime - startTime) / (endTime - startTime);
                    int[] random = NumberUtil.generateRandomNumber(1, 101, 1);
                    int res = (int) (random[0] * 30000 / LocalUserInfo.get().getLevel() * seed);

                    String stockV = (String) stringRedisTemplate.opsForHash()
                            .get("activity:stock" + idx, String.valueOf(activityId));
                    if (!StringUtils.isEmpty(stockV)) {
                        int stock = Integer.parseInt(stockV);
                        if (stock > 0) {
                            if (res <= stock) {
                                stringRedisTemplate.opsForHash().put("activity:stock" + idx,
                                        String.valueOf(activityId), String.valueOf(stock - 1));
                                return true;
                            }
                        }
                    }
                } finally {
                    if (raffleLock.isLocked() && raffleLock.isHeldByCurrentThread()) {
                        raffleLock.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }
}
