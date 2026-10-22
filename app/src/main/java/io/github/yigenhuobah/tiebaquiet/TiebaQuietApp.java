package io.github.yigenhuobah.tiebaquiet;

import android.app.Application;

/**
 * 模块 App 进程入口：尽早注册 XposedService 监听，让设置页首帧就能拿到框架状态。
 */
public class TiebaQuietApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceBridge.bind(this);
    }
}
