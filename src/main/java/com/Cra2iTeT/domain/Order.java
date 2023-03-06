package com.Cra2iTeT.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private String no;
    private Long userId;
    private Byte isDeleted;
    private Date createTime;
    private Long activityId;
}
