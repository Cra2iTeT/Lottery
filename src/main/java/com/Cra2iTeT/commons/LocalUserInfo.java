package com.Cra2iTeT.commons;

import com.Cra2iTeT.domain.User;

/**
 * @author Cra2iTeT
 * @since 2023/3/5 23:45
 */
public class LocalUserInfo {
    private LocalUserInfo(){}

    private static final ThreadLocal<User> USER_THREAD_LOCAL = new ThreadLocal<>();

    public static void put(User user) {
        USER_THREAD_LOCAL.set(user);
    }

    public static User get() {
        return USER_THREAD_LOCAL.get();
    }

    public static void remove() {
        USER_THREAD_LOCAL.remove();
    }
}
