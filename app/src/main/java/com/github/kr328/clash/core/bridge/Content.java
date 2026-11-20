package com.github.kr328.clash.core.bridge;

/**
 * 对应内核 Kotlin 侧的 Content object：
 *   static <clinit>(), private <init>(), public static native int open(String)
 * JNI_OnLoad 会 FindClass 这个类并注册 open -> open_content_func。
 */
public final class Content {

    public static final Content INSTANCE = new Content();

    private Content() {
    }

    /** 由内核注册实现：打开 assets 里的文件，返回 fd（失败返回负数）。 */
    public static native int open(String name);
}
