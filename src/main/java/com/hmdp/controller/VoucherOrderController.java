package com.hmdp.controller;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Autowired
    private IVoucherService iVoucherService;

    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        LambdaQueryWrapper<SeckillVoucher> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(SeckillVoucher::getVoucherId, voucherId);

        SeckillVoucher seckillVoucher = iSeckillVoucherService.getOne(lambdaQueryWrapper);

        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始");
        }

        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }

        if(seckillVoucher.getStock() <= 0){
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

        //userId.toString().intern()是在常量池中新增对象和取对象，相同的对象会直接取出不会新增
        //所以可以保证每次相同的用户进来这里都会被加悲观锁
        //不在方法体上加锁是因为会导致每个用户都会被加锁，业务上只要对相同用户加锁
        //不在方法内部加锁是因为当锁释放的时候可能事务还没有提交，相同用户进来的时候可能也查不到自己导致线程安全问题
        //这样加锁可以保证事务正确提交才释放锁
        //@Transactional事务控制生效是因为spring对当前类做了动态代理
        //AopContext.currentProxy()是为了拿到代理对象，不然createVoucherOrder方法的调用默认是this
        //this的seckillVoucher是没有事务控制的，会导致createVoucherOrder的@Transactional不生效
        //这么做要在pom中加aspectjrt依赖，并在启动类中加上@EnableAspectJAutoProxy(exposeProxy = true)
        //使之暴露出代理对象，然后才能获取到
        synchronized (userId.toString().intern()) {
            VoucherOrderController proxy = (VoucherOrderController) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, seckillVoucher, userId);
        }

    }

    @Transactional
    public Result createVoucherOrder(long voucherId, SeckillVoucher seckillVoucher, long userId) {
            //一人一单
            LambdaQueryWrapper<VoucherOrder> lambdaQueryWrapper1 = new LambdaQueryWrapper<>();
            lambdaQueryWrapper1.eq(VoucherOrder::getUserId, UserHolder.getUser().getId());

            List<VoucherOrder> voucherOrders = voucherOrderService.list(lambdaQueryWrapper1);

            if (!voucherOrders.isEmpty()) {
                return Result.fail("用户已经下过单了");
            }


            // 扣库存
            seckillVoucher.setStock(seckillVoucher.getStock() - 1);
            boolean b = iSeckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();

            if (!b) {
                return Result.fail("抢卷失败，库存不足");
            }

            VoucherOrder voucherOrder = new VoucherOrder();
            long orderId = redisIdWorker.getId("voucher_order");
            voucherOrder.setId(orderId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setUserId(userId);

            voucherOrderService.save(voucherOrder);

            return Result.ok(orderId);
        }

}
