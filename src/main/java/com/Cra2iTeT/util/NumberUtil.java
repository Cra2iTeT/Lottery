package com.Cra2iTeT.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * @author Cra2iTeT
 * @date 2022/10/13 17:02
 */
public class NumberUtil {

    private NumberUtil() {
    }

    /**
     * 生成指定长度的随机数
     *
     * @param length
     * @return
     */
    public static int genRandomNum(int length) {
        int num = 1;
        double random = Math.random();
        if (random < 0.1) {
            random = random + 0.1;
        }
        for (int i = 0; i < length; i++) {
            num = num * 10;
        }
        return (int) ((random * num));
    }

    /**
     * 生成32位随机token
     *
     * @param id
     * @return
     */
    public static String genToken(Long id) {
        try {
            String src = System.currentTimeMillis() + "" + id + genRandomNum(6);
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(src.getBytes());
            String token = new BigInteger(1, md.digest()).toString(16);
            if (token.length() == 31) {
                token = token + "-";
            }
            return token;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException();
        }
    }
}