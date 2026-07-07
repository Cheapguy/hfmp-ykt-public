package com.bosi.ykt.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<?> biz(BizException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<?> valid(Exception e) {
        return R.fail(400, "参数校验失败: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<?> ex(Exception e) {
        log.error("system error", e);
        return R.fail(500, "系统异常: " + e.getMessage());
    }
}
