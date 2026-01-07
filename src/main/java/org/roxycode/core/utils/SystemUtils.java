package org.roxycode.core.utils;

public class SystemUtils {

    public static String getSystemUser() {
        return System.getProperty("user.name");
    }

    public static String getUserDir() {
        return System.getProperty("user.dir");
    }
}
