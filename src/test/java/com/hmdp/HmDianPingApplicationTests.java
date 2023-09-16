package com.hmdp;

import com.hmdp.config.RedissonConfig;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Slf4j
public class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private RedissonClient redissonClient;

    private RLock lock;

    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    public void testSaveShop() throws Exception {
//        shopService.saveShop2Redis(1L,10L);
        Shop byId = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + byId.getId(), byId, 10L, TimeUnit.SECONDS);
    }

    @Test
    public void redisIdWorkerTest() throws InterruptedException {
        //多线程异步，用CountDownLatch计时
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = ()->{
            for (int i = 0;i<100;i++){
            long orderId = redisIdWorker.nexId("order");
            System.out.println(orderId);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0;i<300;i++){
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time:"+(end - begin));
    }

    @BeforeEach
    public  void setUp(){
        lock =  redissonClient.getLock("lock");
    }
    /**
     * redisson可重入测试验证
     */
    @Test
    public void method1() throws InterruptedException {
        boolean isLock = lock.tryLock(1L,TimeUnit.SECONDS);
        if (!isLock){
            log.error("获取锁失败");
            return;
        }
        try {
            log.info("获取锁成功，1");
            method2();
        }finally {
            log.info("释放锁，1");
            lock.unlock();
        }
    }

    public void method2(){
        boolean isLock = lock.tryLock();
        if (!isLock){
            log.info("获取锁失败，2");
            return;
        }
        try {
            log.info("获取锁成功，2");
        }finally {
            log.info("释放锁，2");
            lock.unlock();
        }
    }
}
