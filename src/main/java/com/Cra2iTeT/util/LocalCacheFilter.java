package com.Cra2iTeT.util;

import cn.hutool.bloomfilter.BitMapBloomFilter;
import cn.hutool.bloomfilter.BloomFilterUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Cra2iTeT
 * @since 2023/3/7 17:21
 */
@Component
public class LocalCacheFilter {

    private static ConcurrentHashMap<String, AtomicLong> concurrentHashMap;

    public boolean filter(String key) {
        if (concurrentHashMap.containsKey(key) && concurrentHashMap.get(key).get() > 0) {
            synchronized (concurrentHashMap.get(key)) {
                AtomicLong atomicLong = concurrentHashMap.get(key);
                if (atomicLong.get() < 0) {
                    return false;
                }
                atomicLong.decrementAndGet();
                return true;
            }
        }
        return false;
        // TO
    }

    public void add(String key, AtomicLong atomicLong) {
        concurrentHashMap.put(key, atomicLong);
    }

}
