package com.Cra2iTeT.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Cra2iTeT
 * @since 2023/3/6 16:51
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("linkClick")
public class LinkClick {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long belongUserId;
    private Long createTime;
}
