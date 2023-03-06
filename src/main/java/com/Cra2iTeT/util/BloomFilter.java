package com.Cra2iTeT.util;

import cn.hutool.core.util.HashUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @author Cra2iTeT
 * @since 2023/3/6 16:06
 */
@Component
public class BloomFilter {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean add(String prefix, String key) {
        int offset = hash(key);
        return Boolean.TRUE
                .equals(stringRedisTemplate.opsForValue().setBit(prefix, offset, true));
    }

    public boolean add(String prefix, int key) {
        return this.add(prefix, String.valueOf(key));
    }

    public boolean add(String prefix, long key) {
        return this.add(prefix, String.valueOf(key));
    }

    public boolean add(String prefix, String key1, String key2) {
        int offset = hash(key1, key2);
        return Boolean.TRUE
                .equals(stringRedisTemplate.opsForValue().getBit(prefix, offset));
    }

    public boolean add(String prefix, int key1, int key2) {
        return this.add(prefix, String.valueOf(key1), String.valueOf(key2));
    }

    public boolean add(String prefix, long key1, long key2) {
        return this.add(prefix, String.valueOf(key1), String.valueOf(key2));
    }

    public boolean mightContain(String prefix, String key) {
        int offset = hash(key);
        return Boolean.TRUE
                .equals(stringRedisTemplate.opsForValue().getBit(prefix, offset));
    }

    public boolean mightContain(String prefix, int key) {
        return this.mightContain(prefix, String.valueOf(key));
    }

    public boolean mightContain(String prefix, long key) {
        return this.mightContain(prefix, String.valueOf(key));
    }

    public boolean mightContain(String prefix, String key1, String key2) {
        int offset = hash(key1, key2);
        return Boolean.TRUE
                .equals(stringRedisTemplate.opsForValue().getBit(prefix, offset));
    }

    public boolean mightContain(String prefix, int key1, int key2) {
        return this.mightContain(prefix, String.valueOf(key1), String.valueOf(key2));
    }

    public boolean mightContain(String prefix, long key1, long key2) {
        return this.mightContain(prefix, String.valueOf(key1), String.valueOf(key2));
    }

    private int hash(String key) {
        int hashcode = HashUtil.apHash(key);
        hashcode = HashUtil.rsHash(String.valueOf(hashcode));
        return hashcode & Integer.MAX_VALUE;
    }

    private int hash(String key1, String key2) {
        int hashcode1 = HashUtil.apHash(key1);
        int hashcode2 = HashUtil.apHash(key2);
        return HashUtil.rsHash("" + hashcode1 + hashcode2) & Integer.MAX_VALUE;
    }
}
