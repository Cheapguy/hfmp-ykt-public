package com.bosi.ykt.common;

/**
 * 居民身份证号校验（GB 11643-1999 的 ISO 7064:1983 MOD 11-2 校验位）。
 *
 * <p>原先只查「是不是 18 位」。18 位这个条件挡不住录入时最常见的错误——
 * 打错一位数字、两位数字调换，位数照样是 18。而身份证是这套系统里贯穿全流程的业务主键：
 * 花名册按它回查补贴对象库、送审按它比对、银行按它认人。错一位的后果是这笔钱发不出去
 * （或者发给了另一个真实存在的人），而错误要到银行退回那一步才暴露。
 * 校验位能当场挡掉绝大多数手误，成本只有十几次乘加。
 */
public final class IdCard {

    /** 前 17 位的加权因子 */
    private static final int[] W = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    /** 余数 -> 校验码 */
    private static final char[] CHECK = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdCard() { }

    /**
     * 18 位号码的校验位是否正确。
     * 非 18 位、含非数字（末位 X 除外）一律返回 false——位数由调用方另行报错，这里只答校验位。
     */
    public static boolean checksumOk(String id) {
        if (id == null || id.length() != 18) return false;
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') return false;
            sum += (c - '0') * W[i];
        }
        char last = Character.toUpperCase(id.charAt(17));
        return last == CHECK[sum % 11];
    }
}
