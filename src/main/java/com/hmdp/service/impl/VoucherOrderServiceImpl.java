package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

        @Transactional(rollbackFor = Exception.class)
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券是否存在
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (null == seckillVoucher) {
            return Result.fail("优惠券不存在");
        }
        //2.判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }

        //4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("优惠券已抢光");
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock",0).update();
        if (!success) {
            return Result.fail("库存不足");
        }

        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 订单ID
        long orderId = redisIdWorker.nexId("order");
        voucherOrder.setId(orderId);
        //6.2 用户ID
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //6.3 代金券ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //7. 返回订单ID
        return Result.ok(orderId);
    }
}
