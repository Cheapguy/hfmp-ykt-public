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
        this.strictInsertFill(m, "tenantId", Long.class, UserContext.currentTenantId());
        this.strictInsertFill(m, "deleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject m) {
        this.strictUpdateFill(m, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(m, "updateBy", Long.class, UserContext.currentUserId());
    }
}
