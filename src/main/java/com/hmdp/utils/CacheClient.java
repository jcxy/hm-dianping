package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author CVToolMan
 * @create 2023/8/10 22:54
 */
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置逻辑过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透（泛型应用）
     *
     * @param id
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbCallBack, Long time, TimeUnit unit) {
        //1.redis获取缓存
        String jsonResult = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2. 判断缓存是否存在
        if (StrUtil.isNotBlank(jsonResult)) {
            return JSONUtil.toBean(jsonResult, type);
        }
        if (null != jsonResult) {
            return null;
        }
        //需要传递数据库查询的逻辑（其实就是函数-》函数式编程）有参有返回值用Function函数
        R r = dbCallBack.apply(id);
        if (null == r) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(keyPrefix + id, r, time, unit);
        return r;
    }


    public  <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbCallback,Long time,TimeUnit timeUint) {
        //1、redis获取商铺信息
        String strJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2、不存在直接返回
        if (StrUtil.isBlank(strJson)) {
            return null;
        }
        //3、命中，需要先把json反序列对象
        RedisData redisData = JSONUtil.toBean(strJson, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4、判断是否过期
        if (LocalDateTime.now().isBefore(expireTime)) {
            //4.1 未过期，直接返回店铺信息
            return r;
        }
        //4.2 已过期，需要缓存重建
        //5. 缓存重新建
        //5.1 获取互斥锁
        String lockKey = keyPrefix + id;
        boolean isLock = tryLock(lockKey);
        //5.2 判断是否获取锁成功
        if (isLock) {
            //5.3 成功开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbCallback.apply(id);
                    //写入缓存
                    this.setWithLogicalExpire(lockKey,r1,time,timeUint);
//                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    relaseLock(lockKey);
                }
            });
        }
        //5.4 返回过期的商品信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private boolean relaseLock(String key) {
        Boolean flag = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(flag);
    }
}
