package com.Cra2iTeT.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Cra2iTeT
 * @since 2023/3/5 23:29
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("user")
public class User {
    private Long id;
    private String accNum;
    private String pwd;
    private Byte isDeleted;
    private Byte level;
}
