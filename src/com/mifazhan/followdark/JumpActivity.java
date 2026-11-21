package com.mifazhan.followdark;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * 桌面入口：点一下直接跳到系统「极暗」设置页，没有界面（Theme.NoDisplay，onCreate 里就 finish）。
 * 模块本身的开关都在极暗页里，这个 Activity 只负责导航。
 */
public class JumpActivity extends Activity {

    private static final String ACTION_REDUCE_BRIGHT_COLORS =
            "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent intent = new Intent(ACTION_REDUCE_BRIGHT_COLORS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignore) {
                // 打不开就算了，别崩
            }
        }
        finish();
    }
}
