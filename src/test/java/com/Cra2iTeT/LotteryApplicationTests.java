package com.Cra2iTeT;

import com.Cra2iTeT.domain.Order;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;

@SpringBootTest
class LotteryApplicationTests {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void test(){
        Order order = new Order();
        order.setActivityId(100L);
        order.setNo("11321");
//        stringRedisTemplate.opsForZSet().add("activity:order",JSON.toJSONString(order),1);
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        stringRedisTemplate.opsForZSet().remove("activity:order", JSON.toJSONString(order));
    }


}
