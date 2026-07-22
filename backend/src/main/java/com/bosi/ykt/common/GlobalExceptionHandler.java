package com.bosi.ykt.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

/**
 * 全局异常分级：HTTP 一律 200，靠 {@link R#getCode()} 区分类别（前端 axios 拦截器按 code 分支，见 request.js）。
 * <ul>
 *   <li>业务失败（{@link BizException}）→ 其自带 code（默认 400），message 原样透传给用户。</li>
 *   <li>客户端请求本身有误（错误 JSON / 缺参 / 类型不符 / 方法不支持 / 上传超限）→ 4xx，
 *       不再混进系统异常 500，避免误导用户「系统崩了」、也让 5xx 监控纯净。</li>
 *   <li>未预期异常 → 500 + 通用文案 + 追踪码；细节（ORA 错误/SQL 片段/堆栈）只进日志，
 *       用户拿到的追踪码可让运维 grep 到那一次（log 与响应共用同一 traceId）。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务失败：code 由 BizException 定（默认 400，401/403/404 由调用方显式传），message 直接透传。 */
    @ExceptionHandler(BizException.class)
    public R<?> biz(BizException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    /** @Valid / 表单绑定校验失败：取首条字段错误，别把整个 BindingResult 甩给用户。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<?> valid(Exception e) {
        String detail = (e instanceof MethodArgumentNotValidException m && m.getBindingResult().getFieldError() != null)
                ? m.getBindingResult().getFieldError().getDefaultMessage()
                : (e instanceof BindException b && b.getFieldError() != null ? b.getFieldError().getDefaultMessage() : null);
        return R.fail(400, detail == null ? "参数校验失败" : "参数校验失败: " + detail);
    }

    /** 请求体/参数本身有问题（错误 JSON、缺必填参、路径变量类型不符）→ 400，属客户端错误。 */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public R<?> badRequest(Exception e) {
        return R.fail(400, "请求参数有误");
    }

    /** 方法不支持（GET/POST 用错）→ 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<?> methodNotAllowed(Exception e) {
        return R.fail(405, "请求方法不支持");
    }

    /** 上传文件超限（公告附件等）→ 413，给明确文案而非「系统异常」。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<?> tooLarge(Exception e) {
        return R.fail(413, "上传文件过大");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public R<?> notFound(Exception e) {
        return R.fail(404, "接口不存在");
    }

    /** 兜底：未预期的系统异常。细节只进日志，前端只拿到通用文案 + 追踪码。 */
    @ExceptionHandler(Exception.class)
    public R<?> ex(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        log.error("system error [{}]", traceId, e);
        return R.fail(500, "系统繁忙，请稍后重试（追踪码 " + traceId + "）");
    }
}
