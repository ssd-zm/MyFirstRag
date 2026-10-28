# Java 并发与 HTTP 客户端笔记

## HttpClient 的复用

JDK 11 引入的 java.net.http.HttpClient 是线程安全的，内部维护连接池，全应用复用一个实例就够了。每次请求新建一个 HttpClient 会不断创建线程和连接，高并发下很快耗尽资源。

## 两种超时

connectTimeout 只控制建立 TCP 连接的时间，写法在 Builder 上。请求本身的超时要写在 HttpRequest.timeout() 上，两者不能互相替代。调用大模型的 embedding 接口，批量请求给 30 秒，对话接口给 60 秒比较合适。

## 重试的边界

只有 429 和 5xx 值得重试，以及网络层的 IOException。401 和 400 属于参数或鉴权问题，重试多少次都不会成功，只会白白消耗额度和时间。

429 响应通常带 Retry-After 头，服务端给了具体秒数就照它等待，比固定的指数退避更准。

## 编码问题

发送中文请求体时用 BodyPublishers.ofByteArray 配合 UTF-8 字节数组，接收用 BodyHandlers.ofByteArray 再自己解码。用 ofString 走默认字符集，在 Windows 上默认是 GBK，很容易出现乱码或者 JSON 解析失败。

## 线程池的常见误区

不要为每个请求创建一个线程池，也不要把 HttpClient 放进 ThreadLocal。固定大小的线程池配合批量提交，是调用外部接口更稳的方式。
