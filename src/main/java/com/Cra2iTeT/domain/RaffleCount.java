package com.Cra2iTeT.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Cra2iTeT
 * @since 2023/3/6 19:39
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("raffleCount")
public class RaffleCount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer count;
    private Long userId;
    private Long activityId;
    private Integer totalCount;
}
