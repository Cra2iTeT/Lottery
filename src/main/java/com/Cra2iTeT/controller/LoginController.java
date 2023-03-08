package com.Cra2iTeT.controller;

import cn.hutool.core.util.IdUtil;
import com.Cra2iTeT.commons.R;
import com.Cra2iTeT.domain.User;
import com.Cra2iTeT.service.UserService;
import com.Cra2iTeT.util.BloomFilter;
import com.Cra2iTeT.util.NumberUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @author Cra2iTeT
 * @since 2023/3/8 17:40
 */
@RestController
@RequestMapping("/login")
public class LoginController {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserService userService;

    @Resource
    private BloomFilter bloomFilter;

    @RequestMapping("/register")
    public R<Void> register(@RequestBody User user) {
        String token = (String) stringRedisTemplate.opsForHash()
                .get("login:map", user.getAccNum());
        if (!StringUtils.isEmpty(token)) {
            return new R<>(401, "账号已经注册");
        }
        User one = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getAccNum, user.getAccNum())
                .eq(User::getIsDeleted, 0).last("limit 1"));
        if (one != null) {
            return new R<>(401, "账号已经注册");
        }
        user.setLevel((byte) 1);
        user.setId(IdUtil.getSnowflakeNextId());
        user.setIsDeleted((byte) 0);
        userService.save(user);
        return new R<>(200, "注册成功");
    }

    @RequestMapping("/login")
    public R<String> login(@RequestBody User user) {
        String token = (String) stringRedisTemplate.opsForHash()
                .get("login:map", user.getAccNum());
        if (!StringUtils.isEmpty(token)) {
            String userJson = stringRedisTemplate.opsForValue().get("login:" + token);
            if (!StringUtils.isEmpty(userJson)) {
                Long expire = stringRedisTemplate.getExpire("login:" + token);
                expire = expire != null ? (expire + 60 * 1000) : 12 * 60 * 1000;
                stringRedisTemplate.expire("login:" + token, expire, TimeUnit.MILLISECONDS);
                return new R<>(200, "注册成功");
            } else {
                bloomFilter.remove("login:token", token);
            }
        }
        User one = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getAccNum, user.getAccNum())
                .eq(User::getIsDeleted, 0).last("limit 1"));
        if (!one.getPwd().equals(user.getPwd())) {
            return new R<>(401, "密码错误");
        }
        token = NumberUtil.genToken(one.getId());
        stringRedisTemplate.opsForValue().set("login:" + token, JSON.toJSONString(one),
                24 * 60 * 1000, TimeUnit.MILLISECONDS);
        stringRedisTemplate.opsForHash().put("login:map", user.getAccNum(), token);
        bloomFilter.add("login:token", token);
        return new R<>(200, token);
    }
}
