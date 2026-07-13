package com.bosi.ykt.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;

/**
 * 鉴权覆盖面启动自检（Default-Deny 的编译期替身）。
 *
 * {@link AuthorizationInterceptor} 对 RULES 未命中的控制器默认放行——新增控制器忘登记就是越权盲区
 * （/dept/tpl 上线时就漏过一回）。硬切 Default-Deny 会砸掉故意开放的公共/字典接口，
 * 折中：启动时扫描本工程全部控制器基路径，既不在 RULES 也不在 PUBLIC 白名单的直接拒绝启动，
 * 把"新增控制器必须显式决定鉴权归属"从人工纪律变成启动硬约束，运行时零开销。
 */
@Component
public class AuthCoverageCheck implements ApplicationRunner {

    /** 刻意无需菜单授权的公共控制器（登录态由 JwtInterceptor 保证，数据面由 DataScopeResolver 收窄） */
    private static final Set<String> PUBLIC = Set.of(
            "/health",      // 健康探针
            "/auth",        // 登录/登出/改密
            "/files",       // 附件预览下载（公告附件免登录）
            "/dashboard",   // 工作台（全角色）
            "/agency",      // 机构/部门字典下拉
            "/dept/query",  // 惠民查询（多角色，县域由 DataScope 收窄）
            "/report"       // 惠民报表（多角色，县域由 DataScope 收窄）
    );

    private final RequestMappingHandlerMapping mapping;

    public AuthCoverageCheck(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
        this.mapping = mapping;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> uncovered = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, hm) -> {
            // 只查本工程控制器；框架自带处理器（BasicErrorController 等）不归 RULES 管
            if (!hm.getBeanType().getPackageName().startsWith("com.bosi.ykt")) return;
            RequestMapping rm = hm.getBeanType().getAnnotation(RequestMapping.class);
            String base = (rm != null && rm.value().length > 0) ? rm.value()[0] : "";
            if (!AuthorizationInterceptor.ruleBasePaths().contains(base) && !PUBLIC.contains(base)) {
                uncovered.add(base.isEmpty() ? hm.getBeanType().getSimpleName() : base);
            }
        });
        if (!uncovered.isEmpty()) {
            throw new IllegalStateException("鉴权覆盖面自检失败：以下控制器基路径既不在 AuthorizationInterceptor.RULES，"
                    + "也不在 AuthCoverageCheck.PUBLIC 白名单，新增控制器必须显式登记二者之一：" + uncovered);
        }
    }
}
