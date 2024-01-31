package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author CVToolMan
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result<List<ShopType>> queryTypeList() {
        //1、查缓存是否存在
        List<String> shopTypeListStr = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_LIST_KEY, 0L, -1L);
        if (CollectionUtil.isEmpty(shopTypeListStr)) {
            //2、不存在查询数据库
            List<ShopType> shopTypes = query().orderByAsc("sort").list();
            if (CollectionUtil.isEmpty(shopTypes)) {
                return Result.fail("店铺类型为空");
            }
            //3、保存到缓存
            List<String> shopTypeStrList = shopTypes.stream().map(v -> JSONUtil.toJsonStr(v)).collect(Collectors.toList());
            stringRedisTemplate.opsForList().leftPushAll(CACHE_SHOP_TYPE_LIST_KEY, shopTypeStrList);
            shopTypeListStr = shopTypeStrList;
        }
        List<ShopType> shopTypes = shopTypeListStr.stream().map(v -> JSONUtil.toBean(v, ShopType.class)).collect(Collectors.toList());
        return Result.ok(shopTypes);
    }
}
