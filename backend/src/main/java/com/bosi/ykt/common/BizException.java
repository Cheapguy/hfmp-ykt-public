package com.bosi.ykt.common;

public class BizException extends RuntimeException {
    private final int code;

    // 仅传消息 = 业务规则失败（越权/不存在/查重/状态机拒绝等，属"客户端可预期错误"），
    // 归 400 而非 500——让系统监控的 5xx 只反映真·服务器异常，不被正常业务拒绝污染。
    // 需要更精确语义（401/403/404）的调用方显式传 code。
    public BizException(String msg) { this(400, msg); }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() { return code; }
}
