package com.hmdp.Interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * @Author: liuxing
 * @Date: 2023/7/27 15:47
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser()==null){
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        // 没有，需要拦截，设置状态码
        return true;


        //session的方式
//        //1.获取session
//        HttpSession session = request.getSession();
//        //2.获取session中的用户
//        UserDTO user = (UserDTO) session.getAttribute("user");
//        //3.判断用户是否存在
//        if (null==user){
//            //4.不存在，拦截，返回401状态码
//            response.setStatus(HttpStatus.UNAUTHORIZED.value());
//            return false;
//        }
//        //5.存在，保存用户信息到Threadlocal
//        UserHolder.saveUser(user);
//        //6.放行
//        return true;
    }
}
