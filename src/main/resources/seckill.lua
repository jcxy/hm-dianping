-- 1.参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户ID
local userId = ARGV[2]

-- 1.3 订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key
local orderKey = 'seckill:order:'..voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足
if (tonumber(redis.call('get',stockKey))<=0) then 
    return 1 -- 库存不足返回1
end

-- 3.2 判断用户是否下单
if (redis.call('sismember',orderKey,userId)==1) then
    return 2
end

-- 3.4 扣库存 incerby stockKey -1
redis.call('incrby',stockKey,-1)
-- 3.5 下单（保存用户）
redis.call("sadd",orderKey,userId)

-- 3.6 确认有资格，发送消息到队列中
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)

return 0