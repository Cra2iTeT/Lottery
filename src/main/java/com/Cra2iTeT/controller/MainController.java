package com.Cra2iTeT.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.NumberUtil;
import com.Cra2iTeT.commons.LocalUserInfo;
import com.Cra2iTeT.commons.R;
import com.Cra2iTeT.domain.Activity;
import com.Cra2iTeT.domain.Order;
import com.Cra2iTeT.domain.RaffleCount;
import com.Cra2iTeT.domain.User;
import com.Cra2iTeT.service.ActivityService;
import com.Cra2iTeT.service.OrderService;
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
import java.util.Date;
import java.util.Objects;
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
    private Redisson redisson;

    @Resource
    private OrderService orderService;

    @RequestMapping("/up")
    public R<Void> up(@RequestBody Activity activity) {
        long curTime = System.currentTimeMillis();
        if (curTime - activity.getStartTime().getTime() < 30 * 60 * 1000) {
            return new R<>(401, "开始时间至少半小时之后");
        }
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
        String activityJson = stringRedisTemplate.opsForValue().get("activity:" + activityId);
        if (StringUtils.isEmpty(activityJson)) {
            return new R<Void>(401, "活动不存在");
        }

        RReadWriteLock activityLock = redisson
                .getReadWriteLock("lock:activity:" + activityId);

        RLock activityReadLock = activityLock.readLock();
        boolean isLock = activityReadLock.tryLock();
        if (!isLock) {
            return new R<>(401, "活动正在调整，请稍后重试");
        }
        try {
            Activity activity = JSON.parseObject(activityJson, Activity.class);
            Date now = new Date();
            long curTime = now.getTime();
            if (activity.getStartTime().after(now)) {
                return new R<Void>(401, "活动没开始");
            }
            if (activity.getEndTime().before(now)) {
                return new R<Void>(401, "活动结束");
            }

            String stockV = (String) stringRedisTemplate.opsForHash()
                    .get("activity:stock", String.valueOf(activityId));
            if (StringUtils.isEmpty(stockV) || (Integer.parseInt(stockV)) <= 0) {
                return new R<>(203, "手慢了");
            }

            User user = LocalUserInfo.get();
            RLock raffleCountLock = redisson.getLock("lock:raffleCount:" + activityId + ":" + user.getId());
            raffleCountLock.lock();
            try {
                String raffleCountJson = (String) stringRedisTemplate.opsForHash()
                        .get("activity:raffleCount:" + activityId, String.valueOf(user.getId()));
                RaffleCount raffleCount;
                if (!StringUtils.isEmpty(raffleCountJson)) {
                    raffleCount = JSON.parseObject(raffleCountJson, RaffleCount.class);
                    if (Objects.equals(raffleCount.getCount(), raffleCount.getTotalCount())) {
                        return new R<>(203, "没有抽奖次数了，可以去邀请好友获得");
                    }
                } else {
                    raffleCount = new RaffleCount(0, 1);
                }
                raffleCount.setCount(raffleCount.getCount() + 1);
                // 加库存锁
                RLock stockLock = redisson.getLock("lock:stock:" + activityId);
                try {
                    isLock = stockLock.tryLock(10, TimeUnit.MILLISECONDS);
                    // 扣减次数
                    stringRedisTemplate.opsForHash().put("activity:raffleCount:" + activityId
                            , String.valueOf(user.getId()), JSON.toJSONString(raffleCount));
                    if (isLock) {
                        try {
                            stockV = (String) stringRedisTemplate.opsForHash()
                                    .get("activity:stock", String.valueOf(activityId));
                            int stock;
                            if (!StringUtils.isEmpty(stockV) && (stock = Integer.parseInt(stockV)) > 0 &&
                                    isLucky(curTime, activity.getStartTime().getTime(),
                                            activity.getEndTime().getTime(), stock)) {
                                stock -= 1;
                                // 放回库存
                                stringRedisTemplate.opsForHash().put("activity:stock",
                                        String.valueOf(activityId), String.valueOf(stock));
                                // 生产消息
                                Order order = new Order();
                                order.setId(IdUtil.getSnowflakeNextId());
                                order.setUserId(user.getId());
                                order.setActivityId(activityId);
                                orderService.save(order);
                                return new R<>(200, "运气爆棚");
                            }
                        } finally {
                            if (stockLock.isHeldByCurrentThread()) {
                                stockLock.unlock();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } finally {
                if (raffleCountLock.isHeldByCurrentThread()) {
                    raffleCountLock.unlock();
                }
            }
        } finally {
            if (activityReadLock.isHeldByCurrentThread()) {
                activityReadLock.unlock();
            }
        }
        return new R<>(203, "下次好运");
    }

    private boolean isLucky(long curTime, long startTime, long endTime, int stock) {
        double seed = 1.0 - (double) (endTime - curTime) / (endTime - startTime);
        if (seed == 0.0) {
            seed = 0.1;
        }
        int[] random = NumberUtil.generateRandomNumber(1, 101, 1);
        int res = (int) ((random[0] * 50 / LocalUserInfo.get().getLevel()) * seed);
//        System.out.println("当前时间：" + curTime + "开始时间：" + startTime + "结束时间：" + endTime
//                + "被除数：" + (curTime - startTime) + "除数：" + (endTime - startTime) + "随机数："
//                + random[0] + " 种子：" + seed + " 结果：" + res + " 库存：" + stock);
        return res <= stock;
    }
}
