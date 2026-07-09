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

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public R<?> notFound(Exception e) {
        return R.fail(404, "接口不存在");
    }

    @ExceptionHandler(Exception.class)
    public R<?> ex(Exception e) {
        // 细节只进日志：e.getMessage() 可能带 ORA 错误/SQL 片段等内部信息，不透传前端
        log.error("system error", e);
        return R.fail(500, "系统异常，请稍后重试或联系管理员");
    }
}
