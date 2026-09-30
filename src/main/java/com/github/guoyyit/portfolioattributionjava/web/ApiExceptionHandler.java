package com.github.guoyyit.portfolioattributionjava.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 将业务拒绝与非法请求映射为稳定的错误响应。
 *
 * <p>错误响应固定为 {"errorCode": "...", "message": "..."}：
 * 业务拒绝返回 422，请求格式/枚举非法返回 400。错误原因只取决于请求内容，
 * 不随请求先后顺序或当前存储状态而变化（UNKNOWN_SEGMENT 等也一样，
 * 因为“板块是否存在”本身就是该请求与已提交数据的确定函数）。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException ex) {
        HttpStatus status = ex.getErrorCode() == ErrorCode.INVALID_REQUEST
                ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .body(Map.of("errorCode", ex.getErrorCode().name(), "message", ex.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("errorCode", ErrorCode.INVALID_REQUEST.name(),
                        "message", "请求体无法解析或参数非法: " + ex.getMessage()));
    }
}
