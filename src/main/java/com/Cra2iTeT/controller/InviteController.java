package com.Cra2iTeT.controller;

import cn.hutool.core.util.IdUtil;
import com.Cra2iTeT.commons.LocalUserInfo;
import com.Cra2iTeT.commons.R;
import com.Cra2iTeT.domain.Link;
import com.Cra2iTeT.domain.RaffleCount;
import com.alibaba.fastjson.JSON;
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
import java.util.concurrent.Executor;

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
    private Redisson redisson;

    /**
     * 生成邀请连接
     *
     * @param activityId
     * @return
     */
    @RequestMapping("/generate/{activityId}")
    public R<String> generateInviteLink(@PathVariable("activityId") long activityId) {
        // 判断活动是否存在
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
            RReadWriteLock generateLock = redisson.getReadWriteLock("lock:link:generate:"
                    + activityId + ":" + userId);
            RLock generateWriteLock = generateLock.writeLock();
            if (generateWriteLock.tryLock()) {
                try {
                    String linkJson = (String) stringRedisTemplate.opsForHash()
                            .get("activity:link:" + activityId, String.valueOf(userId));
                    Link link;
                    if (StringUtils.isEmpty(linkJson)) {
                        link = new Link();
                        link.setBelongActivityId(activityId);
                        link.setBelongUserId(userId);
                        link.setInetAddress(IdUtil.simpleUUID());
                        stringRedisTemplate.opsForHash().put("activity:link:" + activityId,
                                String.valueOf(userId), JSON.toJSONString(link));
                        stringRedisTemplate.opsForHash().put("activity:linkMap:" + activityId,
                                link.getInetAddress(), String.valueOf(userId));
                    } else {
                        link = JSON.parseObject(linkJson, Link.class);
                    }
                    return new R<>(200, null, "http://localhost:10086/invite/click/"
                            + activityId + "/" + link.getInetAddress());
                } finally {
                    if (generateWriteLock.isHeldByCurrentThread()) {
                        generateWriteLock.unlock();
                    }
                }
            }
            return new R<>(401, "请勿重复创建");
        } finally {
            if (activityReadLock.isHeldByCurrentThread()) {
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
        String activityV = stringRedisTemplate.opsForValue().get("activity:" + activityId);
        if (StringUtils.isEmpty(activityV)) {
            return new R<>(401, "活动不存在");
        }

        String userIdV = (String) stringRedisTemplate.opsForHash()
                .get("activity:linkMap:" + activityId, inetAddress);
        if (StringUtils.isEmpty(userIdV)) {
            return new R<>(401, "邀请链接不存在");
        }

        Long userId = LocalUserInfo.get().getId();
        Long belongUserId = Long.valueOf(userIdV);
        if (Objects.equals(userId, belongUserId)) {
            return new R<>(401, "不允许点击自己的邀请链接");
        }

        if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember("activity:invite:record:" +
                activityId, String.valueOf(userId)))) {
            return new R<>(401, "单人单场次只能接受一次邀请");
        }

        RReadWriteLock activityLock = redisson.getReadWriteLock("lock:activity:" + activityId);
        RLock activityReadLock = activityLock.readLock();
        if (!activityReadLock.tryLock()) {
            return new R<>(401, "活动正在调整，请稍后重试");
        }

        try {
            RLock inviteRecordLock = redisson.getLock("lock:invite:record:" + activityId + userId);
            inviteRecordLock.lock();
            try {
                stringRedisTemplate.opsForSet().add("activity:invite:record:" +
                        activityId, String.valueOf(userId));
            } finally {
                if (inviteRecordLock.isHeldByCurrentThread()) {
                    inviteRecordLock.unlock();
                }
            }
            RLock raffleCountLock = redisson.getLock("lock:raffleCount:" + activityId + ":" + belongUserId);
            raffleCountLock.lock();
            try {
                String raffleCountV = (String) stringRedisTemplate.opsForHash()
                        .get("activity:raffleCount:" + activityId, String.valueOf(belongUserId));
                RaffleCount raffleCount;
                if (!StringUtils.isEmpty(raffleCountV)) {
                    raffleCount = JSON.parseObject(raffleCountV, RaffleCount.class);
                    if (raffleCount.getTotalCount() >= 51) {
                        return new R<>(200, "接受邀请成功");
                    }
                } else {
                    raffleCount = new RaffleCount(0, 1);
                }
                raffleCount.setTotalCount(raffleCount.getTotalCount() + 1);
                stringRedisTemplate.opsForHash().put("activity:raffleCount:" + activityId,
                        String.valueOf(belongUserId), String.valueOf(raffleCount));
            } finally {
                if (raffleCountLock.isHeldByCurrentThread()) {
                    raffleCountLock.unlock();
                }
            }
            return new R<>(200, "成功接受邀请");
        } finally {
            if (activityReadLock.isHeldByCurrentThread()) {
                activityReadLock.unlock();
            }
        }
    }
}
