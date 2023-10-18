package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        //1、判断关注还是取关
        if (isFollow) {
            //2.关注增加
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOW_KEY + userId, followUserId.toString());
            }
        } else {
            //3.取关删除
            boolean removeSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if (removeSuccess) {
                stringRedisTemplate.opsForSet().remove(RedisConstants.FOLLOW_KEY + userId, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_Id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommon(Long id) {
        Long currentUserId = UserHolder.getUser().getId();
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(RedisConstants.FOLLOW_KEY + currentUserId, RedisConstants.FOLLOW_KEY + id);
        if (CollectionUtils.isEmpty(intersect)){
            return Result.ok(Collections.emptyList());
        }
        List<Long> commonFollowUserIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(commonFollowUserIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
