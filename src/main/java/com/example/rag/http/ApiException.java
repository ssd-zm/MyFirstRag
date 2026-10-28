package com.example.rag.http;

/**
 * 非预期的 API 响应。
 * statusCode 为 -1 表示没拿到 HTTP 状态码（网络异常 / 序列化失败 / 中断）。
 * 保留服务端原始响应体——排障时它比任何日志都有用。
 */
public class ApiException extends RuntimeException {

    public final int statusCode;
    public final String responseBody;

    public ApiException(int statusCode, String responseBody) {
        super("HTTP " + statusCode + ": " + shorten(responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500) + "...(截断)";
    }
}
