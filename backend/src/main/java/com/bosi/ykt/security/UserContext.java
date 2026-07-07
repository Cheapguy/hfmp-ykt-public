package com.bosi.ykt.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserContext {
    private Long userId;
    private String username;
    private Long tenantId;

    private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();

    public static void set(UserContext c) { CTX.set(c); }
    public static UserContext get() { return CTX.get(); }
    public static void clear() { CTX.remove(); }

    public static Long currentUserId() {
        UserContext c = CTX.get();
        return c == null ? null : c.userId;
    }
    public static String currentUsername() {
        UserContext c = CTX.get();
        return c == null ? null : c.username;
    }
    public static Long currentTenantId() {
        UserContext c = CTX.get();
        return c == null ? null : c.tenantId;
    }
}
