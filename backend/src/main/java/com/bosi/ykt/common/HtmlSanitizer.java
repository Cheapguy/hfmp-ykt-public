package com.bosi.ykt.common;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 富文本正文消毒。
 *
 * <p>政策正文用 wangEditor 编辑、存 CLOB、再原样渲染给所有下级单位看。编辑器本身不做任何过滤，
 * 而「只在前端消毒」拦不住直连 POST——一条带 {@code onerror} 的 img 存进去，之后每个打开这条
 * 政策的账号都会中招（存储型 XSS，受害面正好是全州各县乡镇的业务账号）。所以必须在入库这一侧做。
 *
 * <p>白名单基于 jsoup 的 {@link Safelist#relaxed()}：保留排版类标签，丢弃 script/iframe/object、
 * 所有 {@code on*} 事件属性，以及 {@code javascript:} 之类的伪协议链接。额外放开编辑器实际会产出的
 * 少量样式属性（对齐/字号/颜色/表格合并），否则正文一存就掉格式。
 */
public final class HtmlSanitizer {

    private static final Safelist RICH_TEXT = Safelist.relaxed()
            // wangEditor 的对齐/缩进/字号/颜色都落在行内 style 上，不放开会把排版洗掉
            .addAttributes(":all", "style", "class")
            .addTags("hr", "figure", "figcaption")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            // 外链一律新窗口打开，且切断 window.opener 反向控制
            .addAttributes("a", "target", "rel")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https", "data");

    private HtmlSanitizer() { }

    /** 消毒富文本；null/空白原样返回。 */
    public static String clean(String html) {
        if (html == null || html.isBlank()) return html;
        return Jsoup.clean(html, RICH_TEXT);
    }
}
