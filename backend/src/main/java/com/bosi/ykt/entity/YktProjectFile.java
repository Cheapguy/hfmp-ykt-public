package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 补贴项目政策文件附件（migrate V35）。一个项目可挂多份，对应生产表单里的附件表格
 * （序号/文件名称/文件大小/上传人/下载/重新上传/预览/删除）。
 *
 * <p>写入时机：新增项目时还没有 PROJECT_ID，所以不是即传即挂——前端先传文件拿到落盘信息，
 * 随项目表单一起提交，保存项目后按 PROJECT_ID 整体重写附件行。故本表不存在孤儿附件。
 *
 * <p>不继承 BaseEntity：附件是项目的从属明细，生命周期跟着项目走，不需要独立的软删与租户列；
 * 上传人另存 UPLOAD_BY/UPLOAD_NAME（要在表格里显示名字，不能只留 id）。
 */
@Data
@TableName("YKT_PROJECT_FILE")
public class YktProjectFile {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    /** 原始文件名（含扩展名），表格「文件名称」列 */
    private String fileName;
    /** 字节数；0 表示未记录（历史回迁数据），前端显示「—」 */
    private Long fileSize;
    /** /files/preview 下载地址 */
    private String fileUrl;
    private Long uploadBy;
    /** 上传人姓名快照：用户改名或停用后，附件表格仍要显示当时是谁传的 */
    private String uploadName;
    private LocalDateTime uploadTime;
    private Integer sortNo;
}
