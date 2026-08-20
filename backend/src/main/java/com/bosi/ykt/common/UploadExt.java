package com.bosi.ykt.common;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 上传文件校验：扩展名白名单 + 文件头（magic bytes）比对。
 *
 * <p>上传的附件经 {@code /files/preview} 提供下载再分发给各下级单位，不加白名单等于开放挂马。
 * 但**只看扩展名是可以绕过的**——把 exe 改名成 .pdf 就能存进来，别人下载后双击照样执行；
 * 扩展名只是文件名的一部分，跟内容毫无关系。所以再比一次文件头：声称是 pdf 的必须真以 %PDF 开头。
 *
 * <p>只对「头部特征稳定且值得校验」的类型做比对（PDF / OLE 老 Office / OOXML+压缩包 / 常见图片）。
 * txt、csv、ofd、wps 这类没有可靠魔数的放行，不做无意义的猜测。
 */
public final class UploadExt {

    private static final Set<String> ALLOWED = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".wps", ".ofd", ".txt", ".csv",
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp",
            ".zip", ".rar", ".7z");

    // ===== 魔数 =====
    private static final byte[] PDF  = {0x25, 0x50, 0x44, 0x46};                                     // %PDF
    private static final byte[] OLE  = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};                // 老版 Office 复合文档
    private static final byte[] ZIP  = {0x50, 0x4B};                                                 // PK：ooxml/zip/jar
    private static final byte[] RAR  = {0x52, 0x61, 0x72, 0x21};                                     // Rar!
    private static final byte[] SZ   = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};           // 7z
    private static final byte[] PNG  = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPG  = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF  = {0x47, 0x49, 0x46, 0x38};                                     // GIF8
    private static final byte[] BMP  = {0x42, 0x4D};                                                 // BM
    private static final byte[] RIFF = {0x52, 0x49, 0x46, 0x46};                                     // RIFF....WEBP

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

    /**
     * 扩展名 + 文件头一起校验，返回小写扩展名。上传入口一律用这个方法。
     */
    public static String checkedExt(MultipartFile file) {
        String ext = checkedExt(file == null ? null : file.getOriginalFilename());
        byte[] head = readHead(file);
        if (head.length == 0) throw new BizException("上传文件为空");
        if (!headMatches(ext, head))
            throw new BizException("文件内容与扩展名(" + ext + ")不符，疑似被改过后缀，已拒绝");
        return ext;
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[16];
            int n = in.readNBytes(buf, 0, buf.length);
            if (n == buf.length) return buf;
            byte[] cut = new byte[Math.max(n, 0)];
            System.arraycopy(buf, 0, cut, 0, cut.length);
            return cut;
        } catch (IOException e) {
            throw new BizException("读取上传文件失败");
        }
    }

    /** 无可靠魔数的类型返回 true（放行），其余必须头部对得上。 */
    private static boolean headMatches(String ext, byte[] head) {
        switch (ext) {
            case ".pdf":  return startsWith(head, PDF);
            // ooxml 本质是 zip；老格式是 OLE 复合文档。两种都允许，覆盖 doc/docx 混叫的历史文件
            case ".doc": case ".xls": case ".ppt":
            case ".docx": case ".xlsx": case ".pptx":
                          return startsWith(head, OLE) || startsWith(head, ZIP);
            case ".zip":  return startsWith(head, ZIP);
            case ".rar":  return startsWith(head, RAR);
            case ".7z":   return startsWith(head, SZ);
            case ".png":  return startsWith(head, PNG);
            case ".jpg": case ".jpeg": return startsWith(head, JPG);
            case ".gif":  return startsWith(head, GIF);
            case ".bmp":  return startsWith(head, BMP);
            case ".webp": return startsWith(head, RIFF);
            // .txt/.csv/.ofd/.wps 没有稳定魔数，不猜
            default:      return true;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }
}
