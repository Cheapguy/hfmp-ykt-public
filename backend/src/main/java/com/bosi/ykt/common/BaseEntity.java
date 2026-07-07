package com.bosi.ykt.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class BaseEntity implements Serializable {

    @TableField(value = "TENANT_ID", fill = FieldFill.INSERT)
    private Long tenantId;

    @TableField(value = "CREATE_TIME", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "UPDATE_TIME", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(value = "CREATE_BY", fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(value = "UPDATE_BY", fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableLogic
    @TableField(value = "DELETED", fill = FieldFill.INSERT)
    private Integer deleted;
}
