package com.bosi.ykt.controller;

import com.bosi.ykt.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 上传文件预览/下载。{@code /files/preview/**} 已在 WebConfig 拦截器 excludes 中放行（免登录，供乡镇端下载）。
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    @Value("${ykt.upload.base-dir}")
    private String baseDir;

    /** name=存储文件名(uuid.ext)；fn=原始文件名(用于下载另存的显示名) */
    @GetMapping("/preview/{name}")
    public ResponseEntity<Resource> preview(@PathVariable String name,
                                            @RequestParam(required = false) String fn) throws Exception {
        // 防目录穿越：只接受纯文件名
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BizException(400, "非法文件名");
        }
        Path root = Paths.get(baseDir).normalize();
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root) || !Files.exists(path)) {
            throw new BizException(404, "文件不存在");
        }
        String downloadName = (fn != null && !fn.isBlank()) ? fn : name;
        String encoded = URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");
        InputStream in = Files.newInputStream(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .body(new InputStreamResource(in));
    }
}
