package com.example.rag.http;

/**
 * 非预期的 API 响应。
 * statusCode 为 -1 表示没拿到 HTTP 状态码（网络异常 / 序列化失败 / 中断）。
 * 保留服务端原始响应体——排障时它比任何日志都有用。
 */
public class ApiException extends RuntimeException {

    // HTTP 状态码，比如 401、404；-1 表示根本没拿到状态码（网络出问题了）
    public final int statusCode;
    // 服务端返回的原始内容，排错时最有用，可能很长
    public final String responseBody;

    public ApiException(int statusCode, String responseBody) {
        // 先让父类生成一句简短的错误信息
        super("HTTP " + statusCode + ": " + shorten(responseBody));
        // 参数名和字段名一样，用 this 区分是"字段"
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    private static String shorten(String s) {
        // 没有内容就返回空串，免得后面出错
        if (s == null) return "";
        // 超过 500 个字符就只留前面一段，防止报错信息刷屏
        return s.length() <= 500 ? s : s.substring(0, 500) + "...(截断)";
    }
}
