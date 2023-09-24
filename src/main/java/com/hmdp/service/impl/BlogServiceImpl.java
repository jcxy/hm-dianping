package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        //查询博客用户
        queryBlogUser(blog);
        //是否被当前用户点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        Boolean member = stringRedisTemplate.opsForSet().isMember(RedisConstants.BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(member));
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlod(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(v->{
            queryBlogUser(v);
            isBlogLiked(v);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1、获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2、判断当前用户是否点过赞
        Boolean member = stringRedisTemplate.opsForSet().isMember(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        if (member) {
            boolean successCancel = update().setSql("liked = liked-1").eq("id", id).update();
            if (successCancel) {
                stringRedisTemplate.opsForSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
            return Result.ok();
        }
        //3、如果未点赞可以点赞
        //3.1 数据库点赞数加1
        boolean successAdd = update().setSql("liked = liked+1").eq("id", id).update();
        //3.2 保存用户点赞到redis
        if (successAdd) {
            stringRedisTemplate.opsForSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        }
        return Result.ok();
    }
}
