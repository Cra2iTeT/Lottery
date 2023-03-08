package com.Cra2iTeT.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.NumberUtil;
import com.Cra2iTeT.commons.LocalUserInfo;
import com.Cra2iTeT.commons.R;
import com.Cra2iTeT.domain.Activity;
import com.Cra2iTeT.domain.RaffleCount;
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
        if (curTime - activity.getStartTime().getTime() < 30 * 60 * 1000) {
            return new R<>(401, "开始时间至少半小时之后");
        }
        if (activity.getEndTime().isBefore(activity.getStartTime())) {
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

        Long userId = LocalUserInfo.get().getId();
        RReadWriteLock raffleCountLock = redisson.getReadWriteLock("lock:raffleCount:"
                + activityId + userId);
        RLock raffleCountReadLock = raffleCountLock.readLock();
        raffleCountReadLock.lock();

        String raffleCountJson = (String) stringRedisTemplate.opsForHash()
                .get("activity:raffleCount:" + activityId, userId);
        RaffleCount raffleCount;
        if (StringUtils.isEmpty(raffleCountJson)) {
            raffleCount = new RaffleCount(0, 1);
        } else {
            raffleCount = JSON.parseObject(raffleCountJson, RaffleCount.class);
            if (Objects.equals(raffleCount.getCount(), raffleCount.getTotalCount())) {
                raffleCountReadLock.unlock();
                return new R<>(203, "没有抽奖次数了，可以去邀请好友获得");
            }
        }

        String activityJson = stringRedisTemplate.opsForValue().get("activity:" + activityId);
        Activity activity = JSON.parseObject(activityJson, Activity.class);
        long curTime = System.currentTimeMillis();
        long endTime = activity.getEndTime().getTime();
        long startTime = activity.getStartTime().getTime();

        RLock activityReadLock = activityLock.readLock();
        boolean isLock = activityReadLock.tryLock();
        if (isLock) {
            Integer stock0 = (Integer) stringRedisTemplate.opsForHash()
                    .get("activity:stock0", String.valueOf(activityId));
            Integer stock1 = (Integer) stringRedisTemplate.opsForHash()
                    .get("activity:stock1", String.valueOf(activityId));

            if ((stock0 == null || stock0 <= 0) && (stock1 == null || stock1 <= 0)) {
                raffleCountReadLock.unlock();
                return new R<>(203, "奖品被抽取完毕，下次早点");
            }

            if (stock0 != null && stock0 > 0
                    && tryRaffle(activityId, 0, startTime, endTime, curTime,
                    raffleCountReadLock, raffleCountLock, userId)) {
                return new R<>(200, "恭喜");
            }

            if (stock1 != null && stock1 > 0
                    && tryRaffle(activityId, 1, startTime, endTime, curTime,
                    raffleCountReadLock, raffleCountLock, userId)) {
                return new R<>(200, "恭喜");
            }

            return new R<>(203, "祝下次好运");
        }
        return new R<>(401, "活动正在调整，请稍后重试");
    }

    private boolean tryRaffle(long activityId, int idx, long startTime, long endTime, long curTime,
                              RLock raffleCountReadLock, RReadWriteLock raffleCountLock, long userId) {
        RReadWriteLock stock0Lock = redisson.getReadWriteLock("lock:stock:" + idx + activityId);
        RLock stock0ReadLock = stock0Lock.readLock();
        try {
            boolean readLockIsLocked = stock0ReadLock.tryLock(150, TimeUnit.MILLISECONDS);
            if (readLockIsLocked) {
                Integer stock = (Integer) stringRedisTemplate.opsForHash()
                        .get("activity:stock" + idx, String.valueOf(activityId));
                if (stock != null && stock > 0 && isLucky(startTime, endTime, curTime, idx, stock, activityId,
                        stock0Lock, stock0ReadLock, raffleCountReadLock, raffleCountLock, userId)) {
                    return true;
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (raffleCountReadLock.isLocked() && raffleCountReadLock.isHeldByCurrentThread()) {
                raffleCountReadLock.unlock();
            }
        }
        return false;
    }

    private boolean isLucky(long startTime, long endTime, long curTime, Integer stock, int idx,
                            long activityId, RReadWriteLock stock0Lock, RLock stock0ReadLock,
                            RLock raffleCountReadLock, RReadWriteLock raffleCountLock, long userId) {
        double seed = (endTime - curTime) / (endTime - startTime);
        int[] random = NumberUtil.generateRandomNumber(1, 101, 1);
        int res = (int) (random[0] * 30000 / LocalUserInfo.get().getLevel() * seed);
        if (res < stock) {
            stock0ReadLock.unlock();
            if (raffleCountReadLock.isLocked() && raffleCountReadLock.isHeldByCurrentThread()) {
                raffleCountReadLock.unlock();
            }
            RLock raffleCountWriteLock = raffleCountLock.writeLock();
            try {
                boolean raffleCountWriteIsLocked = raffleCountWriteLock
                        .tryLock(100, TimeUnit.MILLISECONDS);
                if (!raffleCountWriteIsLocked) {
                    return false;
                }

                String raffleCountJson = (String) stringRedisTemplate.opsForHash()
                        .get("activity:raffleCount:" + activityId, userId);
                RaffleCount raffleCount;
                if (StringUtils.isEmpty(raffleCountJson)) {
                    raffleCount = new RaffleCount(0, 1);
                } else {
                    raffleCount = JSON.parseObject(raffleCountJson, RaffleCount.class);
                    if (Objects.equals(raffleCount.getCount(), raffleCount.getTotalCount())) {
                        return false;
                    }
                }

                raffleCount.setCount(raffleCount.getCount() + 1);
                RLock stock0WriteLock = stock0Lock.writeLock();
                stock0WriteLock.lock(200, TimeUnit.MILLISECONDS);
                try {
                    stock = (Integer) stringRedisTemplate.opsForHash()
                            .get("activity:stock" + idx, String.valueOf(activityId));
                    if (stock != null && stock > 0) {
                        stock -= 0;
                        stringRedisTemplate.opsForHash().put("activity:stock" + idx,
                                String.valueOf(activityId), stock);
                        stringRedisTemplate.opsForHash()
                                .put("activity:raffleCount:" + activityId, userId,
                                        JSON.toJSONString(raffleCount));
                        return true;
                    }
                } finally {
                    if (stock0WriteLock.isLocked() &&
                            stock0WriteLock.isHeldByCurrentThread()) {
                        stock0WriteLock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                if (raffleCountWriteLock.isLocked() && raffleCountWriteLock.isHeldByCurrentThread()) {
                    raffleCountWriteLock.unlock();
                }
            }
        }
        return false;
    }
}
