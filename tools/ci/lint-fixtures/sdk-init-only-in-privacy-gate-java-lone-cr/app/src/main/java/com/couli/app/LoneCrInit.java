package com.couli.app;

import android.content.Context;

// 违反样例（FU-AND-0715 第 2 轮评审 R2-02）：下面 start 里的行注释后面紧跟一个单独的回车（U+000D，不跟换行），Java 认它是行终止符，
// 回车之后的 SentryAndroid 初始化是真实代码，必须被拦。本文件必须保留单独回车字节（同目录 .gitattributes 标 -text；verify.sh 运行前核对）。
final class LoneCrInit {
    private LoneCrInit() {}

    static void start(Context context) {
        // comment        SentryAndroid.init(context);
    }
}
