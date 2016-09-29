这是一个由akka实现的微信客户端, 可以在同一个进程中登录很多个微信账号, 每一个微信账号的登录对应于不同的Actor实例.


# 本组件特性

支持多账号登录

支持集群方式部署

支持多租户

#启动方式

```
  val system = ActorSystem("wechat")

  val bootstrap = system.actorOf(Props[Bootstrap])

  bootstrap ! ("start", "1"+DateTime.now().toString("MMddHH"))
  
```
  

# wechat API MAP



```
       +--------------+     +---------------+   +---------------+
       |              |     |               |   |               |
       |   Get UUID   |     |  Get Contact  |   | Status Notify |
       |              |     |               |   |               |
       +-------+------+     +-------^-------+   +-------^-------+
               |                    |                   |
               |                    +-------+  +--------+
               |                            |  |
       +-------v------+               +-----+--+------+      +--------------+
       |              |               |               |      |              |
       |  Get QRCode  |               |  Weixin Init  +------>  Sync Check  <----+
       |              |               |               |      |              |    |
       +-------+------+               +-------^-------+      +-------+------+    |
               |                              |                      |           |
               |                              |                      +-----------+
               |                              |                      |
       +-------v------+               +-------+--------+     +-------v-------+
       |              | Confirm Login |                |     |               |
+------>    Login     +---------------> New Login Page |     |  Weixin Sync  |
|      |              |               |                |     |               |
|      +------+-------+               +----------------+     +---------------+
|             |
|QRCode Scaned|
+-------------+
```

#开发过程中参考了 

https://github.com/nodeWechat/wechat4u

https://github.com/Urinx/WeixinBot

