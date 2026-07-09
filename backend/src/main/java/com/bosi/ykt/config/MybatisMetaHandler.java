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
        this.strictInsertFill(m, "createBy", Long.class, UserContext.currentUserId());
        this.strictInsertFill(m, "updateBy", Long.class, UserContext.currentUserId());
        this.strictInsertFill(m, "deleted", Integer.class, 0);
        // tenantId 强制以登录上下文为准：strictInsertFill 遇到实体已带值(如 POST 恶意携带)不覆盖，
        // 会造成跨租户写入；改用 setFieldValByName 强制覆盖（上下文有租户时）。
        Long tid = UserContext.currentTenantId();
        if (tid != null && m.hasSetter("tenantId")) this.setFieldValByName("tenantId", tid, m);
    }

    @Override
    public void updateFill(MetaObject m) {
        this.strictUpdateFill(m, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(m, "updateBy", Long.class, UserContext.currentUserId());
    }
}
