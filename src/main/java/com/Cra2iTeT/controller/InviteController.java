package com.Cra2iTeT.controller;

import cn.hutool.core.util.IdUtil;
import com.Cra2iTeT.commons.LocalUserInfo;
import com.Cra2iTeT.commons.R;
import com.Cra2iTeT.domain.Link;
import com.Cra2iTeT.domain.LinkClick;
import com.Cra2iTeT.domain.RaffleCount;
import com.Cra2iTeT.service.LinkService;
import com.Cra2iTeT.service.RaffleCountService;
import com.Cra2iTeT.util.BloomFilter;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * @author Cra2iTeT
 * @since 2023/3/6 15:33
 */
@RestController
@RequestMapping("/invite")
public class InviteController {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private LinkService linkService;

    @Resource
    private Redisson redisson;

    @Resource
    private BloomFilter bloomFilter;

    @Resource(name = "MainExecutor")
    private Executor executor;

    @Resource
    private RaffleCountService raffleCountService;

    /**
     * 生成邀请连接
     *
     * @param activityId
     * @return
     */
    @RequestMapping("/generate/{activityId}")
    public R<String> generateInviteLink(@PathVariable("activityId") long activityId) {
        // 判断活动是否存在
        if (!bloomFilter.mightContain("bloom:activity", activityId)) {
            return new R<>(401, "活动不存在");
        }
        String activityJson = stringRedisTemplate.opsForValue().get("activity:" + activityId);
        if (StringUtils.isEmpty(activityJson)) {
            return new R<>(401, "活动不存在");
        }
        // 判断是否已经生成过本场的邀请连接
        Long userId = LocalUserInfo.get().getId();
        RLock generateLock = redisson.getLock("lock:activity:generate:" + activityId + userId);
        generateLock.lock(300, TimeUnit.MILLISECONDS);
        try {
            String linkJson = (String) stringRedisTemplate.opsForHash()
                    .get("activity:link:" + activityId, userId);
            Link link;
            if (StringUtils.isEmpty(linkJson)) {
                link = linkService.getOne(new LambdaQueryWrapper<Link>()
                        .eq(Link::getBelongUserId, userId).eq(Link::getBelongActivityId, activityId));
                if (link == null) {
                    link = new Link();
                    link.setBelongActivityId(activityId);
                    link.setBelongUserId(userId);
                    link.setInetAddress(IdUtil.simpleUUID());
                    linkService.save(link);
                }
                stringRedisTemplate.opsForHash()
                        .put("activity:link:" + activityId, link.getInetAddress(), JSON.toJSONString(link));
            } else {
                link = JSON.parseObject(linkJson, Link.class);
            }
            return new R<>(200, null, "http://localhost:10086/invite/click/"
                    + activityId + "/" + link.getInetAddress());
        } finally {
            if (generateLock.isLocked() && generateLock.isHeldByCurrentThread()) {
                generateLock.unlock();
            }
        }
    }

    /**
     * 点击链接增加指定用户抽奖次数
     *
     * @param activityId
     * @param inetAddress
     * @return
     */
    @RequestMapping("/click/{activityId}/{inetAddress}")
    public R<String> clickLink(@PathVariable("activityId") long activityId
            , @PathVariable("inetAddress") String inetAddress) {
        if (!bloomFilter.mightContain("bloom:activity", activityId)) {
            return new R<>(401, "活动不存在");
        }
        String activityJson = stringRedisTemplate.opsForValue().get("activity:" + activityId);
        if (StringUtils.isEmpty(activityJson)) {
            return new R<>(401, "活动不存在");
        }
        String linkJson = (String) stringRedisTemplate.opsForHash()
                .get("activity:link:" + activityId, inetAddress);
        if (StringUtils.isEmpty(linkJson)) {
            return new R<>(401, "邀请链接不存在");
        }
        Long userId = LocalUserInfo.get().getId();
        Link link = JSON.parseObject(linkJson, Link.class);
        if (Objects.equals(link.getBelongUserId(), userId)) {
            return new R<>(401, "不允许点击自己的邀请链接");
        }
        if (bloomFilter.mightContain("activity:linkClick:" + activityId, userId) ||
                Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                        .isMember("activity:linkClick:record:" + activityId + link.getBelongUserId(), userId))) {
            return new R<>(401, "单人单场次只能接受一次邀请");
        }

        // 获取邀请人已经获得的抽奖次数
        CompletableFuture<Integer> getRaffleMaxFuture = getRaffleMaxFuture(link);

        long curTime = System.currentTimeMillis();

        RLock inviteRecordLock = redisson.getLock("lock:invite:record:" + activityId + userId);
        inviteRecordLock.lock(300, TimeUnit.MILLISECONDS);
        try {
            // 给inviteMQ发送消息，落库与写缓存分离，增加接口吞吐量
            CompletableFuture.runAsync(() -> {
                LinkClick linkClick = new LinkClick();
                linkClick.setActivityId(activityId);
                linkClick.setBelongUserId(link.getBelongUserId());
                linkClick.setUserId(userId);
                linkClick.setCreateTime(curTime);
                stringRedisTemplate.opsForZSet().add("mq:invite", JSON.toJSONString(linkClick),
                        curTime + 15 * 60 * 1000);

                // 写入点击记录缓存
                stringRedisTemplate.opsForSet().add("activity:linkClick:record:" +
                                activityId + link.getBelongUserId(), String.valueOf(userId));
            }, executor);
        } finally {
            if (inviteRecordLock.isLocked() && inviteRecordLock.isHeldByCurrentThread()) {
                inviteRecordLock.unlock();
            }
        }
        try {
            Integer getRaffleMax = getRaffleMaxFuture.get();
            if (getRaffleMax >= 51) {
                return new R<>(200, "接受邀请成功");
            }
            RLock raffleCountLock = redisson.getLock("lock:raffleCount" + activityId + link.getBelongUserId());
            raffleCountLock.lock(300, TimeUnit.MILLISECONDS);
            try {
                getRaffleMax = getRaffleMaxFuture(link).get();
                Integer finalGetRaffleMax = getRaffleMax;
                CompletableFuture.runAsync(() -> {
                    stringRedisTemplate.opsForHash().put("activity:raffleCount:max:" + activityId,
                            link.getBelongUserId(), String.valueOf(finalGetRaffleMax + 1));
                }, executor);
                CompletableFuture.runAsync(() -> {
                    RaffleCount raffleCount = new RaffleCount();
                    raffleCount.setActivityId(activityId);
                    raffleCount.setTotalCount(finalGetRaffleMax + 1);
                    raffleCount.setUserId(link.getBelongUserId());
                    stringRedisTemplate.opsForZSet().add("mq:raffleCount", JSON.toJSONString(raffleCount),
                            curTime + 15 * 60 * 1000);
                }, executor);
            } finally {
                if (raffleCountLock.isLocked() && raffleCountLock.isHeldByCurrentThread()) {
                    raffleCountLock.unlock();
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        return new R<>(200, "成功接受邀请");
    }

    private CompletableFuture<Integer> getRaffleMaxFuture(Link link) {
        return CompletableFuture.supplyAsync(() -> {
            Integer raffleMax = (Integer) stringRedisTemplate.opsForHash()
                    .get("activity:raffleCount:max:" + link.getBelongActivityId(), link.getBelongUserId());
            if (raffleMax == null) {
                raffleMax = raffleCountService.getOne(new LambdaQueryWrapper<RaffleCount>()
                        .select(RaffleCount::getTotalCount).eq(RaffleCount::getUserId, link.getBelongUserId())
                        .eq(RaffleCount::getActivityId, link.getBelongActivityId())).getTotalCount();
            }
            return raffleMax == null ? 1 : raffleMax;
        }, executor);
    }
}
