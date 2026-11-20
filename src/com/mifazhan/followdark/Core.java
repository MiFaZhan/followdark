package com.mifazhan.followdark;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Calendar;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 公共逻辑：四个触发源的开关读写、时间窗口计算、反射工具。
 *
 * 开关全部存 Settings.Secure（跨进程共享，Settings 与 system_server 都能读写，
 * 也能用 `adb shell settings put secure <key> 1` 直接改）。
 */
final class Core {

    static final String TAG = "[FollowDark] ";
    static final String LOGCAT_TAG = "FollowDark";

    /** 四个触发源开关（都存 secure，1 = 开） */
    static final String SETTING_FOLLOW_DARK = "reduce_bright_colors_follow_dark";
    static final String SETTING_FOLLOW_EYECARE = "reduce_bright_colors_follow_eyecare";
    static final String SETTING_FOLLOW_SCHEDULE = "reduce_bright_colors_follow_schedule";
    /** 旧：独立的日出日落开关，现在降级成「定时」的一种模式，保留只为兼容旧值 */
    static final String SETTING_FOLLOW_SUNRISE = "reduce_bright_colors_follow_sunrise";
    /** 定时模式：sun = 日出日落，custom = 自定义时间段 */
    static final String SETTING_SCHEDULE_MODE = "reduce_bright_colors_schedule_mode";
    static final String MODE_SUN = "sun";
    static final String MODE_CUSTOM = "custom";
    /** 定时时间段，格式 HH:mm */
    static final String SETTING_SCHEDULE_START = "reduce_bright_colors_schedule_start";
    static final String SETTING_SCHEDULE_END = "reduce_bright_colors_schedule_end";
    static final String DEFAULT_SCHEDULE_START = "22:00";
    static final String DEFAULT_SCHEDULE_END = "07:00";
    /** 系统「极暗」开关（ColorDisplayService 监听此键） */
    static final String SETTING_ACTIVATED = "reduce_bright_colors_activated";
    /** 系统护眼模式（HyperOS 内部叫 paper mode）在 Settings.System 里 */
    static final String SETTING_EYECARE = "screen_paper_mode_enabled";

    /** 注入到极暗页面的偏好项 key */
    static final String PREF_KEY_DARK = "follow_dark_mode_preference";
    static final String PREF_KEY_EYECARE = "follow_eyecare_preference";
    static final String PREF_KEY_SCHEDULE = "follow_schedule_preference";
    static final String PREF_KEY_SCHEDULE_RANGE = "follow_schedule_range_preference";
    static final String PREF_KEY_MODE_GROUP = "follow_schedule_mode_group";
    static final String PREF_KEY_MODE_SUN = "follow_schedule_mode_sun";
    static final String PREF_KEY_MODE_CUSTOM = "follow_schedule_mode_custom";
    static final String PREF_KEY_SUNRISE = "follow_sunrise_preference";

    /** 极暗页面原生开关的 key（实测：OS4.0.6.0 上是 reduce_bright_colors_switch） */
    static final String KEY_MAIN_SWITCH = "reduce_bright_colors_switch";
    /** 老版本 AOSP 用过这个 key，留着兜底 */
    static final String KEY_MAIN_SWITCH_LEGACY = "reduce_bright_colors_preference";

    private Core() {
    }

    static void log(String msg) {
        String line = TAG + msg;
        try {
            XposedBridge.log(line);
        } catch (Throwable ignore) {
        }
        try {
            Log.i(LOGCAT_TAG, msg);
        } catch (Throwable ignore) {
        }
    }

    // ---------------- 开关 ----------------

    static boolean flag(ContentResolver cr, String key) {
        try {
            return Settings.Secure.getInt(cr, key, 0) != 0;
        } catch (Throwable t) {
            log("read " + key + " failed: " + t);
            return false;
        }
    }

    static boolean setFlag(ContentResolver cr, String key, boolean enabled) {
        try {
            Settings.Secure.putInt(cr, key, enabled ? 1 : 0);
            log(key + " -> " + (enabled ? 1 : 0));
            return true;
        } catch (Throwable t) {
            log("write " + key + " failed: " + t);
            return false;
        }
    }

    static int anyEnabledSources(ContentResolver cr) {
        int n = 0;
        if (flag(cr, SETTING_FOLLOW_DARK)) n++;
        if (flag(cr, SETTING_FOLLOW_EYECARE)) n++;
        if (flag(cr, SETTING_FOLLOW_SCHEDULE)) n++;
        if (flag(cr, SETTING_FOLLOW_SUNRISE)) n++;
        return n;
    }

    /** 系统护眼模式是否开着。 */
    static boolean isEyecareOn(Context ctx) {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), SETTING_EYECARE, 0) != 0;
        } catch (Throwable t) {
            log("read eyecare failed: " + t);
            return false;
        }
    }

    static boolean isActivated(ContentResolver cr) {
        try {
            return Settings.Secure.getInt(cr, SETTING_ACTIVATED, 0) != 0;
        } catch (Throwable t) {
            log("read activated failed: " + t);
            return false;
        }
    }

    static void applyIfNeeded(ContentResolver cr, boolean want, String from) {
        boolean current = isActivated(cr);
        if (current == want) {
            return;
        }
        try {
            Settings.Secure.putInt(cr, SETTING_ACTIVATED, want ? 1 : 0);
            log("synced from " + from + ": activated=" + (want ? 1 : 0));
        } catch (Throwable t) {
            log("write activated failed: " + t);
        }
    }

    // ---------------- 定时时间窗口 ----------------

    static String readSchedule(ContentResolver cr, String key, String def) {
        try {
            String v = Settings.Secure.getString(cr, key);
            return (v == null || parseMinutes(v, -1) < 0) ? def : v;
        } catch (Throwable ignore) {
            return def;
        }
    }

    static String scheduleStart(ContentResolver cr) {
        return readSchedule(cr, SETTING_SCHEDULE_START, DEFAULT_SCHEDULE_START);
    }

    static String scheduleEnd(ContentResolver cr) {
        return readSchedule(cr, SETTING_SCHEDULE_END, DEFAULT_SCHEDULE_END);
    }

    /** 定时模式：没写过就按自定义时间段。 */
    static String scheduleMode(ContentResolver cr) {
        try {
            String v = Settings.Secure.getString(cr, SETTING_SCHEDULE_MODE);
            if (MODE_SUN.equals(v) || MODE_CUSTOM.equals(v)) {
                return v;
            }
            // 兼容旧版本：以前日出日落是独立开关
            if (flag(cr, SETTING_FOLLOW_SUNRISE)) {
                return MODE_SUN;
            }
        } catch (Throwable ignore) {
        }
        return MODE_CUSTOM;
    }

    static void setScheduleMode(ContentResolver cr, String mode) {
        try {
            Settings.Secure.putString(cr, SETTING_SCHEDULE_MODE, mode);
            log("schedule mode -> " + mode);
        } catch (Throwable t) {
            log("write schedule mode failed: " + t);
        }
    }

    static String scheduleSummary(ContentResolver cr) {
        return scheduleStart(cr) + " – " + scheduleEnd(cr);
    }

    static void writeSchedule(ContentResolver cr, String start, String end) {
        try {
            Settings.Secure.putString(cr, SETTING_SCHEDULE_START, start);
            Settings.Secure.putString(cr, SETTING_SCHEDULE_END, end);
            log("schedule -> " + start + " – " + end);
        } catch (Throwable t) {
            log("write schedule failed: " + t);
        }
    }

    /** "HH:mm" -> 当天分钟数；解析失败返回 def。 */
    static int parseMinutes(String value, int def) {
        if (value == null) {
            return def;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 2) {
            return def;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return def;
            }
            return h * 60 + m;
        } catch (Throwable ignore) {
            return def;
        }
    }

    static String formatMinutes(int minutes) {
        int m = ((minutes % 1440) + 1440) % 1440;
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    static int nowMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** 支持跨零点（22:00 → 07:00）。 */
    static boolean inWindow(int now, int start, int end) {
        if (start == end) {
            return false;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        return now >= start || now < end;
    }

    /** 距离下一次窗口边界还有多少毫秒。 */
    static long millisToNextBoundary(int nowMin, int start, int end) {
        int nextMin;
        if (start == end) {
            nextMin = start;
        } else if (start < end) {
            if (nowMin < start) {
                nextMin = start;
            } else if (nowMin < end) {
                nextMin = end;
            } else {
                nextMin = start;
            }
        } else {
            if (nowMin < end) {
                nextMin = end;
            } else if (nowMin < start) {
                nextMin = start;
            } else {
                nextMin = end;
            }
        }
        int delta = nextMin - nowMin;
        if (delta <= 0) {
            delta += 1440;
        }
        return (delta - 1) * 60_000L + 500L;
    }

    // ---------------- 反射 ----------------

    static Object call(Object target, String name, Object... args) {
        if (target == null) {
            throw new IllegalArgumentException("target is null for " + name);
        }
        Throwable last = null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length) {
                    continue;
                }
                boolean match = true;
                for (int i = 0; i < params.length; i++) {
                    if (!paramAccepts(params[i], args[i])) {
                        match = false;
                        break;
                    }
                }
                if (!match) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (InvocationTargetException e) {
                    last = e.getCause() != null ? e.getCause() : e;
                } catch (Throwable t) {
                    last = t;
                }
            }
        }
        throw new IllegalStateException("call " + name + " on "
                + target.getClass().getName() + " failed: " + last, last);
    }

    private static boolean paramAccepts(Class<?> param, Object arg) {
        if (arg == null) {
            return !param.isPrimitive();
        }
        Class<?> actual = arg.getClass();
        if (param.isAssignableFrom(actual)) {
            return true;
        }
        if (param == int.class) return actual == Integer.class;
        if (param == boolean.class) return actual == Boolean.class;
        if (param == long.class) return actual == Long.class;
        if (param == float.class) return actual == Float.class;
        if (param == double.class) return actual == Double.class;
        if (param == short.class) return actual == Short.class;
        if (param == byte.class) return actual == Byte.class;
        if (param == char.class) return actual == Character.class;
        return false;
    }

    static Object getField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignore) {
                // 继续找父类
            } catch (Throwable t) {
                throw new IllegalStateException("read " + name + " failed: " + t, t);
            }
        }
        throw new IllegalStateException("no field " + name + " on " + target.getClass().getName());
    }

    static boolean getBooleanField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(target);
            } catch (NoSuchFieldException ignore) {
                // 继续找父类
            } catch (Throwable t) {
                throw new IllegalStateException("read " + name + " failed: " + t, t);
            }
        }
        throw new IllegalStateException("no field " + name + " on " + target.getClass().getName());
    }

    // ---------------- 偏好项 ----------------

    /** 极暗页面：判断这个 fragment / activity 是不是极暗页面。 */
    static boolean isReduceBrightColorsPage(Object fragment) {
        try {
            String cn = fragment.getClass().getName();
            if (cn.contains("ReduceBrightColors")) {
                return true;
            }
            Object activity = call(fragment, "getActivity");
            if (activity instanceof android.app.Activity) {
                return activity.getClass().getName().contains("ReduceBrightColors");
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** 按可用性从新到旧挑一个双态开关控件。 */
    static Object newSwitchPreference(ClassLoader cl, Context ctx) {
        String[] candidates = {
                "androidx.preference.SwitchPreferenceCompat",
                "androidx.preference.SwitchPreference",
                "androidx.preference.CheckBoxPreference",
        };
        Throwable last = null;
        for (String name : candidates) {
            try {
                return XposedHelpers.newInstance(XposedHelpers.findClass(name, cl), ctx);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("no switch preference available: " + last);
    }

    /** 纯展示/点击项（不是开关）。 */
    static Object newPlainPreference(ClassLoader cl, Context ctx) {
        Class<?> cls = XposedHelpers.findClass("androidx.preference.Preference", cl);
        return XposedHelpers.newInstance(cls, ctx);
    }

    /** 开关的 OnPreferenceChangeListener：只负责写标志位 + 更新摘要，真正的联动在 system_server。 */
    static Object newFlagListener(ClassLoader cl, Context ctx,
                                  final String settingKey,
                                  final ContentResolver cr,
                                  final Runnable afterChange) {
        return newListener(cl, "androidx.preference.Preference$OnPreferenceChangeListener",
                new Handler() {
                    @Override
                    public Object handle(Object proxy, Method method, Object[] args) {
                        if (!"onPreferenceChange".equals(method.getName())) {
                            return null;
                        }
                        boolean value = Boolean.TRUE.equals(args[1]);
                        if (!setFlag(cr, settingKey, value)) {
                            return Boolean.FALSE;
                        }
                        if (afterChange != null) {
                            afterChange.run();
                        }
                        return Boolean.TRUE;
                    }
                });
    }

    /** 点击项：只关心 onPreferenceClick。 */
    static Object newClickListener(ClassLoader cl, final Runnable action) {
        return newListener(cl, "androidx.preference.Preference$OnPreferenceClickListener",
                new Handler() {
                    @Override
                    public Object handle(Object proxy, Method method, Object[] args) {
                        if (!"onPreferenceClick".equals(method.getName())) {
                            return null;
                        }
                        action.run();
                        return Boolean.TRUE;
                    }
                });
    }

    interface Handler {
        Object handle(Object proxy, Method method, Object[] args);
    }

    private static Object newListener(ClassLoader cl, String ifaceName, final Handler handler) {
        final Class<?> iface = XposedHelpers.findClass(ifaceName, cl);
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("toString".equals(name)) {
                    return "FollowDarkListener";
                }
                return handler.handle(proxy, method, args);
            }
        });
    }
}
