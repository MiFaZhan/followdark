package com.mifazhan.followdark;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 入口（assets/xposed_init 指向本类）。
 *
 * 两个进程分别装钩子：
 *  - com.android.settings  ：在「极暗」页面注入「跟随深色模式」开关
 *  - android (system_server)：深色模式变化时事件驱动地同步极暗状态
 */
public class Main implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (lpparam == null) {
            return;
        }
        if ("com.android.settings".equals(lpparam.packageName)) {
            Core.log("loaded in com.android.settings");
            SettingsHooks.install(lpparam.classLoader);
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            // 只在 system_server 里装钩子，避免装到其它进程
            boolean isSystemServer = Process.myUid() == Process.SYSTEM_UID
                    || "system".equals(lpparam.processName)
                    || "android".equals(lpparam.processName);
            if (!isSystemServer) {
                return;
            }
            Core.log("loaded in system_server");
            SystemHooks.install(lpparam.classLoader);
        }
    }
}
