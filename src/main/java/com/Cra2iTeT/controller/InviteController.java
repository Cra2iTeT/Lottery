package com.Cra2iTeT.controller;

import cn.hutool.core.util.IdUtil;
import com.Cra2iTeT.commons.LocalUserInfo;
import com.Cra2iTeT.commons.R;
import com.Cra2iTeT.domain.Link;
import com.Cra2iTeT.service.ActivityService;
import com.Cra2iTeT.service.LinkService;
import com.Cra2iTeT.util.BloomFilter;
import com.Cra2iTeT.util.NumberUtil;
import com.alibaba.fastjson.JSON;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * @author Cra2iTeT
 * @since 2023/3/6 15:33
 */
@RestController
@RequestMapping("/invite")
public class InviteController {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    ActivityService activityService;

    @Resource
    LinkService linkService;

    @Resource
    BloomFilter bloomFilter;

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
        String linkJson = (String) stringRedisTemplate.opsForHash()
                .get("activity:link:" + activityId, userId);
        Link link;
        if (StringUtils.isEmpty(linkJson)) {
            link = new Link();
            link.setBelongActivityId(activityId);
            link.setBelongUserId(userId);
            String inetAddress = IdUtil.simpleUUID();
            link.setInetAddress("http://localhost:10086/invite/click" + activityId + "/" + inetAddress);
            stringRedisTemplate.opsForHash()
                    .put("activity:link:" + activityId, inetAddress, JSON.toJSONString(link));
        } else {
            link = JSON.parseObject(linkJson, Link.class);
        }
        return new R<>(200, null, link.getInetAddress());
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
        // TODO 点击链接增加指定用户抽奖次数
        String test = "sss";
        return new R<>(200, "成功接受邀请");
    }
}
