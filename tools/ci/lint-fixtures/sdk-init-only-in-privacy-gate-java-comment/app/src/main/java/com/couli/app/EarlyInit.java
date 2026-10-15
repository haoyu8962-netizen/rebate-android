package com.couli.app;

import android.content.Context;

// 违反样例（FU-AND-0715 第 1 轮评审 R1-04）：Java 块注释不嵌套，下面一行在第一个注释结束符之后的 SentryAndroid 初始化是真实代码，必须被拦
final class EarlyInit {
    private EarlyInit() {}

    static void start(Context context) {
        /* /* */ SentryAndroid.init(context); // */
    }
}
