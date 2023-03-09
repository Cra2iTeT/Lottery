package com.Cra2iTeT.domain;

import cn.hutool.core.date.DateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @author Cra2iTeT
 * @since 2023/3/5 23:33
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("order")
public class Order {
    private Long id;
    private Long userId;
    private Byte isDeleted;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss",timezone="GMT+8")
    private Date createTime;
    private Long activityId;
}
