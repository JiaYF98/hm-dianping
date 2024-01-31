package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1. 从 redis 中查找
        String key = CACHE_SHOP_TYPE_KEY;
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 2. 有则返回
        if (shopTypeJsonList != null && !shopTypeJsonList.isEmpty()) {
            List<ShopType> shopTypeList = new ArrayList<>();
            for (String shopTypeJson : shopTypeJsonList) {
                shopTypeList.add(JSONUtil.toBean(shopTypeJson, ShopType.class));
            }
            return Result.ok(shopTypeList);
        }

        // 3. 没有，则从数据库中查找
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        // 4. 存入 redis
        for (ShopType shopType : shopTypeList) {
            stringRedisTemplate.opsForList().rightPush(key, JSONUtil.toJsonStr(shopType));
        }

        // 5. 返回 list
        return Result.ok(shopTypeList);
    }
}
