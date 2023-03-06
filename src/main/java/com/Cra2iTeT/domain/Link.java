package com.Cra2iTeT.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Cra2iTeT
 * @since 2023/3/5 23:35
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("link")
public class Link {
    private Long id;
    private String inetAddress;
    private Long userId;
    private Long belongUserId;
    private Long belongActivityId;
}
