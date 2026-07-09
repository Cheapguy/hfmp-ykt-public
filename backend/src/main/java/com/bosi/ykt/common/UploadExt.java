package com.bosi.ykt.common;

import java.util.Set;

/**
 * 上传扩展名白名单：文档/图片/压缩包；可执行与脚本类一律拒。
 * 上传的附件经 /files/preview 对外提供下载（公告免登录可达），不加白名单等于开放挂马再分发。
 */
public final class UploadExt {

    private static final Set<String> ALLOWED = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".wps", ".ofd", ".txt", ".csv",
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp",
            ".zip", ".rar", ".7z");

    private UploadExt() { }

    /** 返回小写扩展名（含点）；无扩展名或不在白名单抛 BizException。 */
    public static String checkedExt(String filename) {
        String ext = "";
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) ext = filename.substring(dot).toLowerCase();
        }
        if (ext.isEmpty() || !ALLOWED.contains(ext))
            throw new BizException("不支持的文件类型" + (ext.isEmpty() ? "（缺少扩展名）" : "：" + ext)
                    + "，仅允许文档/图片/压缩包");
        return ext;
    }
}
