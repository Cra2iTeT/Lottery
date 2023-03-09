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
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss",timezone="GMT+8")
    private Date startTime;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss",timezone="GMT+8")
    private Date endTime;
    private Byte level;
}
