package com.bosi.ykt.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.bosi.ykt.security.UserContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MybatisMetaHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject m) {
        this.strictInsertFill(m, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(m, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(m, "deleted", Integer.class, 0);
        // 以下三个字段一律以登录上下文为准，不采纳实体上已有的值。
        // strictXxxFill 的语义是「为空才填」——POST body 里带一个 createBy 就能冒名顶替。
        // 这不只是审计脏数据：政策/公告的县域可见性正是按 CREATE_BY 反推作者所在县的，
        // 伪造 createBy = 把自己的记录种到别的县，或伪装成「无县码的公共数据」让全州可见。
        // tenantId 早已这么处理，createBy/updateBy 当时漏了。
        // 无登录上下文（启动期任务等）则不动，保留调用方自己写好的值。
        Long uid = UserContext.currentUserId();
        if (uid != null) {
            if (m.hasSetter("createBy")) this.setFieldValByName("createBy", uid, m);
            if (m.hasSetter("updateBy")) this.setFieldValByName("updateBy", uid, m);
        }
        Long tid = UserContext.currentTenantId();
        if (tid != null && m.hasSetter("tenantId")) this.setFieldValByName("tenantId", tid, m);
    }

    @Override
    public void updateFill(MetaObject m) {
        this.strictUpdateFill(m, "updateTime", LocalDateTime.class, LocalDateTime.now());
        Long uid = UserContext.currentUserId();
        if (uid != null && m.hasSetter("updateBy")) this.setFieldValByName("updateBy", uid, m);
    }
}
