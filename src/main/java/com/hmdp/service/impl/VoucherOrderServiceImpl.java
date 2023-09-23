package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.impl.SimpleRedisLock;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.nio.file.CopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    //阻塞队列,只有队列中有元素的时候才会执行
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECTOR = Executors.newSingleThreadExecutor();

    /**
     * 多线程异步的时候代理是拿不到的，所以要在主线程里获取或者是说一起放到阻塞队列里
     */
    @Autowired
    private IVoucherOrderService proxy;

   private static final String QUEUE_NAME = "stream.orders";


    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECTOR.submit(() -> {
            while (true) {
                //1.获取订单中的队列信息
                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
                    //改成从redis拿消息而不是阻塞队列
                    //2.判断获取消息是否成功 xreadgroup group g1 c1 count 1 block 2000 streams stream.orders
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    if (CollectionUtils.isEmpty(list)) {
                        handlePendingList();
                        //2.1 获取失败说明没有消息进行下一次循环
                        continue;
                    }
                    //2.2 有消息进行下单操作
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //3.ack确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", entries.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        });
    }

    private void handlePendingList() {
        while (true) {
            //1.获取订单中的队列信息
            try {
//                    VoucherOrder voucherOrder = orderTasks.take();
                //改成从redis拿消息而不是阻塞队列
                //2.判断获取消息是否成功 xreadgroup group g1 c1 count 1 block 2000 streams stream.orders
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );
                if (CollectionUtils.isEmpty(list)) {
                    //2.1 获取失败说明没有pending的消息
                    return;
                }
                //2.2 有消息进行下单操作
                MapRecord<String, Object, Object> entries = list.get(0);
                Map<Object, Object> value = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                //3.ack确认 sack stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", entries.getId());
            } catch (Exception e) {
                log.error("处理订单异常");
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.获取锁队形
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        proxy.createVoucherOrder(voucherOrder);

    }
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//
//        }
//    }

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        UserDTO user = UserHolder.getUser();
        //1、执行lua脚本呢
        //2.2 为0，有购买资格，把下单信息保存到阻塞对队列
        Long orderId = redisIdWorker.nexId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), user.getId().toString(), orderId.toString());
        int r = result.intValue();
        //2.判断结果是否为0
        if (r != 0) {
            //2.1 不为0没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "禁止重复下单");
        }
//        VoucherOrder voucherOrder =  new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(user.getId());
//        voucherOrder.setVoucherId(voucherId);
//        //2.3 放入阻塞队列
//        orderTasks.add(voucherOrder);
//        //3.获取代理对象
//        proxy = (IVoucherOrderService)AopContext.currentProxy();
//        proxy.createVoucherOrder(voucherOrder);
        //4. 返回订单ID
        return Result.ok(orderId);
    }

    /**
     * 核心就是购买资格的教研和库存校验以及扣减
     *
     * @param voucherId
     * @return
     * @throws InterruptedException
     */
//    @Transactional(rollbackFor = Exception.class)
//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //1.查询优惠券是否存在
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        if (null == seckillVoucher) {
//            return Result.fail("优惠券不存在");
//        }
//        //2.判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        //3.判断秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//
//        //4.判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("优惠券已抢光");
//        }
//        //5.扣减库存
//        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //toString内部其实是new String 所以要用intern
////        synchronized (userId.toString().intern()) {
////            //Spring事务其实是启用一个代理A方法调B方法引用默认是this当前对象，
////            //为了保证事务不失效，可以获取到代理对象然后调方法
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
////        boolean isLock = lock.tryLock(1200);
////        if (!isLock){
////            return Result.fail("不允许重复下单");
////        }
////        try {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }finally {
////            lock.unLock();
////        }
//        RLock lock = redissonClient.getLock("order:" + userId);
//        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
//        if (!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }
    @Transactional
    public Result createVoucherOrder(VoucherOrder voucherOrder) {
        save(voucherOrder);
        return Result.ok();
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 订单ID
        long orderId = redisIdWorker.nexId("order");
        voucherOrder.setId(orderId);
        //6.2 用户ID
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            Result.fail("一个用户只能抢一张");
        }
        voucherOrder.setUserId(userId);
        //6.3 代金券ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok();
    }
}
