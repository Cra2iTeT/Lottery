package com.Cra2iTeT.listener;

import com.Cra2iTeT.domain.Order;
import com.Cra2iTeT.service.OrderService;
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
 * @since 2023/3/6 10:41
 */
@Component
@EnableAsync
public class RedisOrderMQListener {

    @Resource
    private Redisson redisson;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderService orderService;

    /**
     * 每十五分钟执行一次获取消息
     */
    @Async("MQListener")
    @Scheduled(fixedRate = 15 * 60 * 1000 + 5000)
    void orderSetListener() {
        Set<String> zSet = stringRedisTemplate.opsForZSet()
                .range("mq:order", 0, System.currentTimeMillis());
        if (zSet == null || zSet.isEmpty()) {
            return;
        }
        for (String s : zSet) {
            consumeOrderSetMQ(s);
        }
    }

    /**
     * 消费消息
     *
     * @param orderId
     */
    private void consumeOrderSetMQ(String orderId) {
        try {
            RLock rLock = redisson.getLock("lock:order:" + orderId);
            // 获取锁失败则已经有其他线程获取到锁，已经读到这条消息，不需要再重复消费
            if (rLock.tryLock()) {
                // 执行判断订单过期操作
                String orderJson = (String) stringRedisTemplate.opsForHash()
                        .get("order:unpaid:", orderId);
                if (StringUtils.isEmpty(orderJson)) {
                    return;
                }
                Order order = JSON.parseObject(orderJson, Order.class);
                try {
                    if (rollBackStock(order)) {
                        // ack 删除消息
                        stringRedisTemplate.opsForZSet()
                                .remove("mq:order", orderId);
                    }
                } finally {
                    if (rLock.isLocked() && rLock.isHeldByCurrentThread()) {
                        rLock.unlock();
                    }
                }
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    /**
     * 返还库存，向数据库存入订单
     *
     * @param order
     * @return
     */
    private boolean rollBackStock(Order order) {
        // 获取订单
        RLock stockLock = redisson.getLock("lock:stock0:" + order.getActivityId());
        int bucket = 0;
        try {
            if (!stockLock.tryLock()) {
                stockLock = redisson.getLock("lock:stock1:" + order.getActivityId());
                stockLock.lock(500, TimeUnit.SECONDS);
                bucket = 1;
            }
            if (stockLock.isLocked()) {
                // 写入数据库
                order.setIsDeleted((byte) 1);
                orderService.save(order);
                // 返还库存
                Integer stock = (Integer) stringRedisTemplate.opsForHash()
                        .get("activity:stock" + bucket, order.getActivityId());
                stringRedisTemplate.opsForHash().put("activity:stock" + bucket,
                        String.valueOf(order.getActivityId()), String.valueOf(stock != null ? stock + 1 : 1));
                return true;
            }
        } finally {
            if (stockLock.isLocked() && stockLock.isHeldByCurrentThread()) {
                stockLock.unlock();
            }
        }
        return false;
    }
}
