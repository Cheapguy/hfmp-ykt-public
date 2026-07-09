package com.bosi.ykt.security;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.bosi.ykt.common.BizException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防护（内存实现，单实例部署够用）：
 * <ul>
 *   <li>失败计数：连续失败 ≥{@link #CAPTCHA_AFTER} 次要求验证码（渐进式，正常登录/脚本不受影响）；</li>
 *   <li>锁定：连续失败 ≥{@link #LOCK_AFTER} 次锁定 {@link #LOCK_MINUTES} 分钟；</li>
 *   <li>验证码：hutool 线段干扰图，一次性、5 分钟有效。</li>
 * </ul>
 * 需要验证码时抛 {@code BizException(4281,...)}，前端据此展示验证码输入框。
 */
@Component
public class LoginGuard {

    public static final int CODE_CAPTCHA_REQUIRED = 4281;

    private static final int CAPTCHA_AFTER = 2;
    private static final int LOCK_AFTER = 5;
    private static final int LOCK_MINUTES = 10;
    private static final long LOCK_MS = LOCK_MINUTES * 60_000L;
    private static final long CAPTCHA_TTL_MS = 5 * 60_000L;
    private static final int MAX_ENTRIES = 10_000;   // 防恶意撑爆内存，超限先清过期

    private static final class Fail { volatile int count; volatile long lockUntil; }
    private static final class Code { final String code; final long expireAt;
        Code(String c, long e) { this.code = c; this.expireAt = e; } }

    private final ConcurrentHashMap<String, Fail> fails = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Code> captchas = new ConcurrentHashMap<>();

    /** 锁定校验：锁定期内直接拒。 */
    public void checkLock(String username) {
        Fail f = fails.get(username);
        if (f != null && f.lockUntil > System.currentTimeMillis())
            throw new BizException("失败次数过多，请 " + LOCK_MINUTES + " 分钟后再试");
    }

    /** 该账号当前是否要求验证码（连续失败 ≥2 次）。 */
    public boolean captchaRequired(String username) {
        Fail f = fails.get(username);
        return f != null && f.count >= CAPTCHA_AFTER;
    }

    /** 生成验证码：返回 {key, img(base64 data uri)}。 */
    public Map<String, String> newCaptcha() {
        evictIfNeeded(captchas);
        LineCaptcha c = CaptchaUtil.createLineCaptcha(130, 44, 4, 24);
        String key = UUID.randomUUID().toString().replace("-", "");
        captchas.put(key, new Code(c.getCode().toLowerCase(), System.currentTimeMillis() + CAPTCHA_TTL_MS));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("img", c.getImageBase64Data());
        return out;
    }

    /** 校验验证码（一次性：无论对错都作废）。 */
    public boolean verifyCaptcha(String key, String input) {
        if (key == null || input == null || input.isBlank()) return false;
        Code c = captchas.remove(key);
        return c != null && c.expireAt > System.currentTimeMillis()
                && c.code.equals(input.trim().toLowerCase());
    }

    /** 登录失败：计数 +1，达到阈值即锁定。 */
    public void onFail(String username) {
        evictIfNeeded(fails);
        Fail f = fails.computeIfAbsent(username, k -> new Fail());
        synchronized (f) {
            f.count++;
            if (f.count >= LOCK_AFTER) {
                f.lockUntil = System.currentTimeMillis() + LOCK_MS;
                f.count = 0;   // 锁定后重新计数
            }
        }
    }

    /** 登录成功：清计数。 */
    public void onSuccess(String username) { fails.remove(username); }

    /** 简易护栏：条目超限时清掉已过期/已解锁的（攻击者刷随机用户名不至于撑爆内存）。 */
    private void evictIfNeeded(ConcurrentHashMap<String, ?> map) {
        if (map.size() < MAX_ENTRIES) return;
        long now = System.currentTimeMillis();
        if (map == captchas) captchas.entrySet().removeIf(e -> e.getValue().expireAt <= now);
        else fails.entrySet().removeIf(e -> e.getValue().lockUntil <= now && e.getValue().count == 0);
        if (map.size() >= MAX_ENTRIES) map.clear();   // 仍超限=纯恶意流量，整体重置最安全
    }
}
