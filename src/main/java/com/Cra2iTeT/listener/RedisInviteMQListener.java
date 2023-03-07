package com.Cra2iTeT.listener;

import com.Cra2iTeT.domain.LinkClick;
import com.Cra2iTeT.domain.RaffleCount;
import com.Cra2iTeT.service.LinkClickService;
import com.Cra2iTeT.service.RaffleCountService;
import com.alibaba.fastjson.JSON;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Cra2iTeT
 * @since 2023/3/7 17:22
 */
@Component
@EnableAsync
public class RedisInviteMQListener {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private LinkClickService linkClickService;

    @Resource
    private RaffleCountService raffleCountService;

    @Async("MQListener")
    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void InviteSetListener() {
        long curTime = System.currentTimeMillis();
        Set<String> zSet = stringRedisTemplate.opsForZSet().range("mq:invite", 0, curTime);
        if (zSet == null || zSet.isEmpty()) {
            return;
        }
        linkClickService.saveBatch(zSet.stream().map((linkClickJson) ->
                JSON.parseObject(linkClickJson, LinkClick.class)).collect(Collectors.toList()));
    }

    @Async("MQListener")
    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void RaffleCountSetListener() {
        long curTime = System.currentTimeMillis();
        Set<String> zSet = stringRedisTemplate.opsForZSet().range("mq:raffleCount", 0, curTime);
        if (zSet == null || zSet.isEmpty()) {
            return;
        }
        raffleCountService.saveOrUpdateBatch(zSet.stream().map((raffleCountJson) ->
                JSON.parseObject(raffleCountJson, RaffleCount.class)).collect(Collectors.toList()));
    }
}
