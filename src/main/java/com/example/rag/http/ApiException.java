package com.example.rag.http;

/**
 * 带状态码和原始响应体的异常，方便直接看到服务端的报错内容。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String responseBody;

    public ApiException(int status, String responseBody) {
        super("HTTP " + status + " -> " + abbreviate(responseBody));
        this.status = status;
        this.responseBody = responseBody;
    }

    public int status() {
        return status;
    }

    public String responseBody() {
        return responseBody;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 400 ? oneLine : oneLine.substring(0, 400) + "...";
    }
}
