package com.bosi.ykt.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 清册样式公式（对生产管理员手册 1.11，如 补贴金额=补贴标准×月数）：
 * 四则运算 + 括号，操作数为数字常量或其他数值列的标识。递归下降解析，BigDecimal 计算。
 */
public final class FormulaEngine {

    private FormulaEngine() {}

    /** 求值时缺操作数（该行引用列为空）抛出，caller 据 key 提示是哪列没值 */
    public static class MissingVarException extends RuntimeException {
        public final String key;
        public MissingVarException(String key) { super(key); this.key = key; }
    }

    /** 全角运算符/括号归一化 + 去空白 + 转小写（用户习惯输入 ×÷（）、Standard*Months；标识强制小写，转小写无歧义） */
    public static String normalize(String f) {
        return f.replace('×', '*').replace('÷', '/')
                .replace('（', '(').replace('）', ')')
                .replace('＋', '+').replace('－', '-')
                .replaceAll("[\\s　]+", "")
                .toLowerCase();
    }

    /** 语法检查并返回公式引用的列标识（解析失败抛 IllegalArgumentException） */
    public static Set<String> refs(String formula) {
        Parser p = new Parser(formula, null);
        p.parse();
        return p.refs;
    }

    /** 求值：vars=标识→数值。缺变量抛 MissingVarException，除 0 抛 ArithmeticException */
    public static BigDecimal eval(String formula, Map<String, BigDecimal> vars) {
        return new Parser(formula, vars).parse();
    }

    /** expr := term (±term)* ; term := factor (*∕factor)* ; factor := 数字|标识|(expr)|-factor */
    private static final class Parser {
        private final String s;
        private final Map<String, BigDecimal> vars;   // null=仅语法检查/收集引用
        final Set<String> refs = new LinkedHashSet<>();
        private int i;

        Parser(String s, Map<String, BigDecimal> vars) { this.s = s; this.vars = vars; }

        BigDecimal parse() {
            if (s == null || s.isEmpty()) throw new IllegalArgumentException("公式为空");
            BigDecimal v = expr();
            if (i < s.length()) throw new IllegalArgumentException("第" + (i + 1) + "个字符「" + s.charAt(i) + "」无法解析");
            return v;
        }

        private BigDecimal expr() {
            BigDecimal v = term();
            while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                char op = s.charAt(i++);
                BigDecimal r = term();
                v = op == '+' ? v.add(r) : v.subtract(r);
            }
            return v;
        }

        private BigDecimal term() {
            BigDecimal v = factor();
            while (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                char op = s.charAt(i++);
                BigDecimal r = factor();
                v = op == '*' ? v.multiply(r) : v.divide(r, 8, RoundingMode.HALF_UP);
            }
            return v;
        }

        private BigDecimal factor() {
            if (i >= s.length()) throw new IllegalArgumentException("公式在结尾处不完整");
            char c = s.charAt(i);
            if (c == '(') {
                i++;
                BigDecimal v = expr();
                if (i >= s.length() || s.charAt(i) != ')') throw new IllegalArgumentException("括号不匹配");
                i++;
                return v;
            }
            if (c == '-') { i++; return factor().negate(); }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                BigDecimal v;
                try { v = new BigDecimal(s.substring(i, j)); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("数字「" + s.substring(i, j) + "」格式错误"); }
                i = j;
                return v;
            }
            if (c >= 'a' && c <= 'z') {
                int j = i;
                while (j < s.length() && (s.charAt(j) == '_' || Character.isDigit(s.charAt(j))
                        || (s.charAt(j) >= 'a' && s.charAt(j) <= 'z'))) j++;
                String key = s.substring(i, j);
                i = j;
                refs.add(key);
                if (vars == null) return BigDecimal.ONE;   // 语法检查模式：占位值
                BigDecimal v = vars.get(key);
                if (v == null) throw new MissingVarException(key);
                return v;
            }
            throw new IllegalArgumentException("第" + (i + 1) + "个字符「" + c + "」无法解析");
        }
    }
}
