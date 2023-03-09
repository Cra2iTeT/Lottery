package com.Cra2iTeT.controller;

import cn.hutool.core.util.IdUtil;
import com.Cra2iTeT.commons.LocalUserInfo;
import com.Cra2iTeT.commons.R;
import com.Cra2iTeT.domain.Link;
import com.Cra2iTeT.domain.LinkClick;
import com.Cra2iTeT.domain.RaffleCount;
import com.Cra2iTeT.service.LinkClickService;
import com.Cra2iTeT.service.LinkService;
import com.Cra2iTeT.util.BloomFilter;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
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
    private LinkClickService linkClickService;

    /**
     * 生成邀请连接
     *
     * @param activityId
     * @return
     */
    @RequestMapping("/generate/{activityId}")
    public R<String> generateInviteLink(@PathVariable("activityId") long activityId) {
        // 判断活动是否存在
        // TODO 布隆过滤器判断放到外面的请求过滤器中
        if (!bloomFilter.mightContain("bloom:activity", activityId)) {
            return new R<>(401, "活动不存在");
        }
        String activityJson = stringRedisTemplate.opsForValue().get("activity:" + activityId);
        if (StringUtils.isEmpty(activityJson)) {
            return new R<>(401, "活动不存在");
        }
        RReadWriteLock activityLock = redisson.getReadWriteLock("lock:activity:" + activityId);
        RLock activityReadLock = activityLock.readLock();
        // 尝试一次没有获取到就说明没有上架或者正在下架
        if (!activityReadLock.tryLock()) {
            return new R<>(401, "当前活动正在调整，请稍后重试");
        }
        try {
            // 判断是否已经生成过本场的邀请连接
            Long userId = LocalUserInfo.get().getId();
            RReadWriteLock generateLock = redisson.getReadWriteLock("lock:activity:generate:"
                    + activityId + userId);
            RLock generateWriteLock = generateLock.writeLock();
            if (generateWriteLock.tryLock()) {
                try {
                    String linkJson = (String) stringRedisTemplate.opsForHash()
                            .get("activity:link:" + activityId, String.valueOf(userId));
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
                        stringRedisTemplate.opsForHash().put("activity:link:"
                                + activityId, link.getInetAddress(), JSON.toJSONString(link));
                    } else {
                        link = JSON.parseObject(linkJson, Link.class);
                    }
                    return new R<>(200, null, "http://localhost:10086/invite/click/"
                            + activityId + "/" + link.getInetAddress());
                } finally {
                    if (generateWriteLock.isLocked() && generateWriteLock.isHeldByCurrentThread()) {
                        generateWriteLock.unlock();
                    }
                }
            } else {
                return new R<>(401, "请勿重复创建");
            }
        } finally {
            if (activityReadLock.isLocked() && activityReadLock.isHeldByCurrentThread()) {
                activityReadLock.unlock();
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
                        .isMember("activity:linkClick:record:" + activityId + link.getBelongUserId(),
                                String.valueOf(userId))) || linkClickService.getOne(new
                LambdaQueryWrapper<LinkClick>().eq(LinkClick::getActivityId,activityId)
                .eq(LinkClick::getUserId,userId).eq(LinkClick::getBelongUserId,link.getBelongUserId())
                .last("limit 1")) != null) {
            return new R<>(401, "单人单场次只能接受一次邀请");
        }

        RReadWriteLock activityLock = redisson.getReadWriteLock("lock:activity:" + activityId);
        RLock activityReadLock = activityLock.readLock();
        if (!activityReadLock.tryLock()) {
            return new R<>(401, "活动正在调整，请稍后重试");
        }
        try {
            // 获取邀请人已经获得的抽奖次数
            CompletableFuture<RaffleCount> getRaffleFuture = getRaffleFuture(link.getBelongActivityId(),
                    link.getBelongUserId());

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
                    // 写入点击记录缓存
                    stringRedisTemplate.opsForSet().add("activity:linkClick:record:" +
                            activityId + link.getBelongUserId(), String.valueOf(userId));
                    stringRedisTemplate.expire("activity:linkClick:record:" + activityId
                            + link.getBelongUserId(), 7, TimeUnit.MINUTES);
                    // 生产消息
                    stringRedisTemplate.opsForZSet().add("mq:invite", JSON.toJSONString(linkClick),
                            curTime + 15 * 60 * 1000);
                    bloomFilter.add("activity:linkClick:" + activityId, userId);
                }, executor);
            } finally {
                if (inviteRecordLock.isLocked() && inviteRecordLock.isHeldByCurrentThread()) {
                    inviteRecordLock.unlock();
                }
            }
            try {
                if (getRaffleFuture.get().getTotalCount() >= 51) {
                    return new R<>(200, "接受邀请成功");
                }
                RReadWriteLock raffleCountLock = redisson.getReadWriteLock("lock:raffleCount:"
                        + activityId + link.getBelongUserId());
                RLock raffleCountWriteLock = raffleCountLock.writeLock();
                raffleCountWriteLock.lock(300, TimeUnit.MILLISECONDS);
                try {
                    RaffleCount raffleCount = getRaffleFuture(link.getBelongActivityId(),
                            link.getBelongUserId()).get();
                    if (raffleCount.getTotalCount() >= 51) {
                        return new R<>(200, "接受邀请成功");
                    }
                    CompletableFuture.runAsync(() -> {
                        stringRedisTemplate.opsForHash().put("activity:raffleCount:" + activityId,
                                String.valueOf(link.getBelongUserId()), String.valueOf(raffleCount));
                    }, executor);
                } finally {
                    if (raffleCountWriteLock.isLocked() && raffleCountWriteLock.isHeldByCurrentThread()) {
                        raffleCountWriteLock.unlock();
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
            return new R<>(200, "成功接受邀请");
        } finally {
            if (activityReadLock.isLocked() && activityReadLock.isHeldByCurrentThread()) {
                activityReadLock.unlock();
            }
        }
    }

    private CompletableFuture<RaffleCount> getRaffleFuture(long activityId, long userId) {
        return CompletableFuture.supplyAsync(() -> {
            String raffleCountJson = (String) stringRedisTemplate.opsForHash()
                    .get("activity:raffleCount:" + activityId, String.valueOf(userId));
            return StringUtils.isEmpty(raffleCountJson) ? new RaffleCount(0, 1)
                    : JSON.parseObject(raffleCountJson, RaffleCount.class);
        }, executor);
    }
}
