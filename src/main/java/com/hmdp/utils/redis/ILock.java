package com.hmdp.utils.redis;

/**
 * @author CVToolMan
 * @create 2023/9/10 14:16
 */
public interface ILock {

    boolean tryLock(long timeOutSec);

    void unLock();
}
