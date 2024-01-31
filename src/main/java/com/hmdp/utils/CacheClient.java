package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 写入 Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从 redis 查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }

        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据 id 查询数据库，并保存到 redis 中
        R r = dbFallback.apply(id);

        // 5. 不存在，返回错误
        if (r == null) {
            // 将空值写入 Redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }

        // 6. 存在，写入 redis
        this.set(key, r, time, unit);

        return r;
    }

    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, String lockKeyPrefix,
                                    Function<ID, R> dbFallback, Long nullTTL, Long cacheTTL, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        // 1. 从 redis 查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }

        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4. 实现缓存重建
        // 4.1 获取互斥锁
        String lockKey = lockKeyPrefix + id;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLock) {
                // 4.3 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, lockKeyPrefix, dbFallback, nullTTL, cacheTTL, timeUnit);
            }

            // 4.4 二次校验
            json = stringRedisTemplate.opsForValue().get(key);

            // 判断是否存在
            if (StrUtil.isNotBlank(json)) {
                // 存在，直接返回
                return JSONUtil.toBean(json, type);
            }

            // 判断命中的是否是空值
            if (json != null) {
                // 返回一个错误信息
                return null;
            }

            // 4.5 不存在，根据 id 查询数据库，并保存到 redis 中
            return getAndSaveShop2Redis(id, key, dbFallback, nullTTL, cacheTTL, timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放互斥锁
            unlock(lockKey);
        }
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                            String lockKeyPrefix, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从 redis 查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3. 不存在，直接返回
            return null;
        }

        // 4. 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回店铺信息
            return r;
        }
        // 5.2 已过期，需要缓存重建
        // 6. 缓存重建
        // 6.1 获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        // 6.2 判断是否获取锁成功
        if (isLock) {
            // 6.3 成功，二次检验
            // todo 重复代码
            json = stringRedisTemplate.opsForValue().get(key);

            // 判断是否存在
            if (StrUtil.isBlank(json)) {
                // 不存在，直接返回
                return null;
            }

            // 命中，需要先把json反序列化为对象
            redisData = JSONUtil.toBean(json, RedisData.class);
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();

            // 判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())) {
                // 未过期，直接返回店铺信息
                return r;
            }

            // 6.4 开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    // 查数据库
                    R r1 = dbFallback.apply(id);
                    // 写入 redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.5 返回过期的商铺信息
        return r;
    }

    private <R, ID> R getAndSaveShop2Redis(ID id, String key, Function<ID, R> dbFallback,
                                           Long nullTTL, Long cacheTTL, TimeUnit timeUnit) throws InterruptedException {
        R r = dbFallback.apply(id);
        // 模拟重建的延时
        Thread.sleep(200);

        // 不存在，返回错误
        if (r == null) {
            // 将空值写入 redis
            stringRedisTemplate.opsForValue().set(key, "", nullTTL, timeUnit);
            // 返回错误信息
            return null;
        }

        // 存在，写入 redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), cacheTTL, timeUnit);

        // 返回店铺
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
