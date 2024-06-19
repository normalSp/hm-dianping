package com.hmdp;

import com.hmdp.controller.ShopController;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private IVoucherService voucherService;

    @Resource
    private ShopController shopController;

    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IShopService shopService;

    @Test
    public void testSaveShop(){
        shopController.saveShopToReids(1L, 10L);
    }


    @Test
    public void IdWorkerTest() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.getId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));

        }


/*
        System.out.println(redisIdWorker.getId("order"));
*/

    @Test
    public void addVoucherTest(){
        Voucher voucher = new Voucher();
        voucher.setId(2L);
        voucher.setStock(100);
        voucher.setBeginTime(LocalDateTime.now());
        voucher.setEndTime(LocalDateTime.now().plusDays(100));

        voucherService.addSeckillVoucher(voucher);
    }
    @Test
    public void RedissonTest() throws InterruptedException {
        RLock lock = redissonClient.getLock("lock");

        lock.tryLock(1L, TimeUnit.SECONDS);
    }


    @Test
    public void loadShopData(){
        //1. 获取所有商铺信息
        List<Shop> shopList = shopService.list();

        //2. 根据key：商铺类型，value：所属商铺list，转成map
        Map<Long, List<Shop>> shopMap = shopList
                .stream().collect(
                        Collectors.groupingBy(Shop::getTypeId)
                );

        //3. 存进redis
        for(Map.Entry<Long, List<Shop>> entry : shopMap.entrySet()){
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;

            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList.size());

            //这样就之用向redis发一次请求
            for (Shop shop : value) {
                //stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }

            stringRedisTemplate.opsForGeo().add(key, locations);
        }


    }


    @Test
    public void testHyperLoglog(){
        String[] users = new String[1000];

        int index = 0;
        for(int i = 0; i < 1000000; i++){
            users[index++] = "user_" + i;

            if(i % 1000 == 999){
                index = 0;
                stringRedisTemplate.opsForHyperLogLog().add("hll1", users);
            }
        }

        Long size = stringRedisTemplate.opsForHyperLogLog().size("hll1");
        System.out.println("size = " + size);

    }

}

