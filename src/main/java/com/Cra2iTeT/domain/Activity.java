package com.Cra2iTeT.domain;

import cn.hutool.core.date.DateTime;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Cra2iTeT
 * @since 2023/3/5 23:31
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("activity")
public class Activity {
    private Long id;
    private String context;
    private Integer stock;
    private DateTime startTime;
    private DateTime endTime;
    private Byte level;
}
