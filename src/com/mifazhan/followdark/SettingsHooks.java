package com.mifazhan.followdark;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.widget.TimePicker;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Settings 侧：往「极暗」页面注入一个「自动开启」分组。
 *
 *   跟随深色模式        [开关]
 *   跟随护眼模式        [开关]
 *   定时开启            [开关]
 *     日出日落模式       自动识别日出日落时间      ┐ 只在「定时开启」开着时出现
 *     自定义时间         22:00 – 07:00          ┘ 二选一；点自定义时间同时改时间段
 *
 * 页面结构（OS4.0.6.0 / Android 17 实测 preference 树）：
 *   PreferenceScreen
 *     0 top_intro                    TopIntroPreference
 *     1 animated_image               IllustrationPreference
 *     2 reduce_bright_colors_switch  MainSwitchPreference   ← 原生「将屏幕调成极暗」
 *     3 general_categories           PreferenceCategory（选项）
 *     4 html_description             order=2147483646
 */
final class SettingsHooks {

    private static final String CATEGORY_KEY = "follow_sources_category";
    private static boolean sDumped = false;

    private SettingsHooks() {
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> dashboard = XposedHelpers.findClass(
                    "com.android.settings.dashboard.DashboardFragment", cl);

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ensureInjected(param.thisObject);
                    } catch (Throwable t) {
                        Core.log("inject failed: " + t);
                    }
                }
            };

            XposedBridge.hookAllMethods(dashboard, "onCreatePreferences", hook);
            XposedBridge.hookAllMethods(dashboard, "onStart", hook);
            XposedBridge.hookAllMethods(dashboard, "onResume", hook);
            Core.log("settings hooks installed");
        } catch (Throwable t) {
            Core.log("settings hook install failed: " + t);
        }
    }

    private static void ensureInjected(Object fragment) {
        if (fragment == null || !Core.isReduceBrightColorsPage(fragment)) {
            return;
        }
        Object screen = Core.call(fragment, "getPreferenceScreen");
        if (screen == null) {
            return;
        }
        Object category = findPreference(screen, CATEGORY_KEY);
        if (category == null) {
            category = createCategoryShell(fragment);
            if (category == null) {
                return;
            }
            insertCategory(screen, category);
        }
        // 必须先挂到页面上（拿到 PreferenceManager）才能往里 addPreference
        if (findPreference(screen, Core.PREF_KEY_DARK) == null) {
            populateCategory(fragment, category);
        }
        refreshState(fragment, screen);
    }

    // ---------------- 建组 ----------------

    private static Object createCategoryShell(Object fragment) {
        Context ctx = prefContext(fragment);
        ClassLoader cl = fragment.getClass().getClassLoader();
        try {
            Object category = XposedHelpers.newInstance(
                    XposedHelpers.findClass("androidx.preference.PreferenceCategory", cl), ctx);
            Core.call(category, "setKey", CATEGORY_KEY);
            Core.call(category, "setTitle", "自动开启");
            Core.call(category, "setPersistent", false);
            try {
                Core.call(category, "setIconSpaceReserved", false);
            } catch (Throwable ignore) {
            }
            return category;
        } catch (Throwable t) {
            Core.log("create category failed: " + t);
            return null;
        }
    }

    private static void populateCategory(Object fragment, Object category) {
        Context ctx = prefContext(fragment);
        ClassLoader cl = fragment.getClass().getClassLoader();
        ContentResolver cr = ctx.getContentResolver();

        List<Object> items = new ArrayList<>();
        items.add(newSwitch(cl, ctx, cr, Core.PREF_KEY_DARK, Core.SETTING_FOLLOW_DARK,
                "跟随深色模式", fragment));
        items.add(newSwitch(cl, ctx, cr, Core.PREF_KEY_EYECARE, Core.SETTING_FOLLOW_EYECARE,
                "跟随护眼模式", fragment));
        items.add(newSwitch(cl, ctx, cr, Core.PREF_KEY_SCHEDULE, Core.SETTING_FOLLOW_SCHEDULE,
                "定时开启", fragment));
        items.add(newModeRow(fragment, cl, ctx, cr, Core.PREF_KEY_MODE_SUN,
                "日出日落模式", "自动识别日出日落时间", Core.MODE_SUN));
        items.add(newModeRow(fragment, cl, ctx, cr, Core.PREF_KEY_MODE_CUSTOM,
                "自定义时间", Core.scheduleSummary(cr), Core.MODE_CUSTOM));

        for (int i = 0; i < items.size(); i++) {
            Core.call(items.get(i), "setOrder", i);
            Core.call(category, "addPreference", items.get(i));
        }
        Core.log("category populated with " + items.size() + " items");
    }

    private static Object newSwitch(final ClassLoader cl, final Context ctx, final ContentResolver cr,
                                    String prefKey, String settingKey, String title,
                                    final Object fragment) {
        Object preference = Core.newSwitchPreference(cl, ctx);
        Core.call(preference, "setKey", prefKey);
        Core.call(preference, "setTitle", title);
        Core.call(preference, "setPersistent", false);
        try {
            Core.call(preference, "setOnPreferenceChangeListener",
                    Core.newFlagListener(cl, ctx, settingKey, cr, new Runnable() {
                        @Override
                        public void run() {
                            // 开关一变立刻刷新页面（「定时开启」控制下面两行的显隐）
                            try {
                                Object screen = Core.call(fragment, "getPreferenceScreen");
                                if (screen != null) {
                                    refreshState(fragment, screen);
                                }
                            } catch (Throwable t) {
                                Core.log("refresh after toggle failed: " + t);
                            }
                        }
                    }));
        } catch (Throwable t) {
            Core.log("listener for " + prefKey + " failed: " + t);
        }
        return preference;
    }

    /**
     * 定时模式的一行（日出日落模式 / 自定义时间），跟系统深色模式页一样是互斥单选。
     * 直接放在「自动开启」分组里：嵌套一个 miuix RadioButtonPreferenceCategory 会把外层卡片渲染搞坏。
     */
    private static Object newModeRow(final Object fragment, ClassLoader cl, Context ctx,
                                     final ContentResolver cr, String key, String title,
                                     String summary, final String mode) {
        Object row;
        try {
            row = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "miuix.preference.RadioButtonPreference", cl), ctx);
        } catch (Throwable t) {
            row = Core.newSwitchPreference(cl, ctx);
        }
        final Object result = row;
        Core.call(row, "setKey", key);
        Core.call(row, "setTitle", title);
        Core.call(row, "setSummary", summary);
        Core.call(row, "setPersistent", false);
        try {
            Core.call(row, "setOnPreferenceClickListener",
                    Core.newClickListener(cl, new Runnable() {
                        @Override
                        public void run() {
                            Core.setScheduleMode(cr, mode);
                            applyChecked(fragment, mode);
                            if (Core.MODE_CUSTOM.equals(mode)) {
                                // 时间段就挂在「自定义时间」这一项上：点它就改时间
                                openRangePickers(fragment, cr, result);
                            }
                        }
                    }));
        } catch (Throwable t) {
            Core.log("mode row listener failed: " + t);
        }
        return row;
    }

    /** 勾选态自己维护（不用 miuix 的互斥分组）。 */
    private static void applyChecked(Object fragment, String mode) {
        try {
            Object screen = Core.call(fragment, "getPreferenceScreen");
            Object sun = findPreference(screen, Core.PREF_KEY_MODE_SUN);
            Object custom = findPreference(screen, Core.PREF_KEY_MODE_CUSTOM);
            setCheckedState(sun, Core.MODE_SUN.equals(mode));
            setCheckedState(custom, Core.MODE_CUSTOM.equals(mode));
        } catch (Throwable t) {
            Core.log("apply checked failed: " + t);
        }
    }

    private static void setCheckedState(Object pref, boolean checked) {
        if (pref == null) {
            return;
        }
        try {
            Core.call(pref, "setChecked", checked);
        } catch (Throwable t) {
            try {
                Core.call(pref, "setButtonChecked", checked);
            } catch (Throwable ignore) {
            }
        }
    }

    // ---------------- 时间段 ----------------

    private interface TimePicked {
        void onPicked(String hhmm);
    }

    /** 对话框要跟着深/浅色走，不然深色页面里会弹出一个白底弹窗。 */
    private static Context dialogContext(Activity activity) {
        boolean night = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int theme = night
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        return new android.view.ContextThemeWrapper(activity, theme);
    }

    private static void pickTime(Activity activity, String title, int initial,
                                 final TimePicked callback) {
        TimePickerDialog dialog = new TimePickerDialog(dialogContext(activity),
                new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                        callback.onPicked(Core.formatMinutes(hourOfDay * 60 + minute));
                    }
                },
                initial / 60, initial % 60, true);
        dialog.setTitle(title);
        dialog.show();
    }

    private static void openRangePickers(Object fragment, final ContentResolver cr,
                                         final Object row) {
        final Activity activity;
        try {
            activity = (Activity) Core.call(fragment, "getActivity");
        } catch (Throwable t) {
            Core.log("no activity for time picker: " + t);
            return;
        }
        if (activity == null) {
            return;
        }
        pickTime(activity, "开始时间", Core.parseMinutes(Core.scheduleStart(cr), 22 * 60),
                new TimePicked() {
                    @Override
                    public void onPicked(final String start) {
                        pickTime(activity, "结束时间",
                                Core.parseMinutes(Core.scheduleEnd(cr), 7 * 60),
                                new TimePicked() {
                                    @Override
                                    public void onPicked(String end) {
                                        Core.writeSchedule(cr, start, end);
                                        try {
                                            Core.call(row, "setSummary", Core.scheduleSummary(cr));
                                        } catch (Throwable ignore) {
                                        }
                                    }
                                });
                    }
                });
    }

    // ---------------- 插入 ----------------

    private static Object nativeSwitch(Object screen) {
        Object anchor = findPreference(screen, Core.KEY_MAIN_SWITCH);
        if (anchor == null) {
            anchor = findPreference(screen, Core.KEY_MAIN_SWITCH_LEGACY);
        }
        if (anchor == null && !sDumped) {
            sDumped = true;
            dumpTree(screen, 0);
        }
        return anchor;
    }

    /**
     * 插到原生开关正下方：先把后面各项的 order 整体后移，再给分组排 order，最后 addPreference。
     * （这页每一项都显式设了 order，直接 add 会被扔到末尾。）
     */
    private static void insertCategory(Object screen, Object category) {
        Object anchor = nativeSwitch(screen);
        Object parent = screen;
        if (anchor != null) {
            try {
                Object p = Core.call(anchor, "getParent");
                if (p != null) {
                    parent = p;
                }
            } catch (Throwable ignore) {
            }
        }
        try {
            int base = anchor == null ? 0 : (Integer) Core.call(anchor, "getOrder");
            int count = (Integer) Core.call(parent, "getPreferenceCount");
            for (int i = 0; i < count; i++) {
                Object item = Core.call(parent, "getPreference", i);
                if (item == anchor) {
                    continue;
                }
                int order = (Integer) Core.call(item, "getOrder");
                if (order > base && order < Integer.MAX_VALUE - 1) {
                    Core.call(item, "setOrder", order + 1);
                }
            }
            Core.call(category, "setOrder", base + 1);
            Core.call(parent, "addPreference", category);
            moveRightAfter(parent, anchor, category);
            Core.log("category inserted after the native switch");
        } catch (Throwable t) {
            Core.log("insert category failed: " + t);
        }
    }

    /** Settings 里的 androidx 被 R8 混淆过，只能按「类型是 List 且装着 anchor」找字段。 */
    private static void moveRightAfter(Object parent, Object anchor, Object preference) {
        if (parent == null || anchor == null) {
            return;
        }
        try {
            for (Class<?> c = parent.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (!List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object raw = f.get(parent);
                    if (!(raw instanceof List)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) raw;
                    int anchorIndex = list.indexOf(anchor);
                    if (anchorIndex < 0) {
                        continue;
                    }
                    list.remove(preference);
                    list.add(anchorIndex + 1, preference);
                    Core.call(parent, "notifyChanged");
                    return;
                }
            }
        } catch (Throwable t) {
            Core.log("move category failed: " + t);
        }
    }

    // ---------------- 刷新 ----------------

    private static void refreshState(Object fragment, Object screen) {
        try {
            Context ctx = prefContext(fragment);
            ContentResolver cr = ctx.getContentResolver();
            boolean scheduleOn = Core.flag(cr, Core.SETTING_FOLLOW_SCHEDULE);

            applySwitch(screen, Core.PREF_KEY_DARK, Core.flag(cr, Core.SETTING_FOLLOW_DARK));
            applySwitch(screen, Core.PREF_KEY_EYECARE, Core.flag(cr, Core.SETTING_FOLLOW_EYECARE));
            applySwitch(screen, Core.PREF_KEY_SCHEDULE, scheduleOn);

            String mode = Core.scheduleMode(cr);
            Object sun = findPreference(screen, Core.PREF_KEY_MODE_SUN);
            Object custom = findPreference(screen, Core.PREF_KEY_MODE_CUSTOM);
            if (sun != null) {
                Core.call(sun, "setVisible", scheduleOn);
            }
            if (custom != null) {
                Core.call(custom, "setVisible", scheduleOn);
                Core.call(custom, "setSummary", Core.scheduleSummary(cr));
            }
            setCheckedState(sun, Core.MODE_SUN.equals(mode));
            setCheckedState(custom, Core.MODE_CUSTOM.equals(mode));
        } catch (Throwable t) {
            Core.log("refresh state failed: " + t);
        }
    }

    private static void applySwitch(Object screen, String key, boolean checked) {
        Object preference = findPreference(screen, key);
        if (preference == null) {
            return;
        }
        Core.call(preference, "setChecked", checked);
        Core.call(preference, "setVisible", true);
    }

    // ---------------- 遍历 ----------------

    private static Object findPreference(Object group, String key) {
        try {
            int count = (Integer) Core.call(group, "getPreferenceCount");
            for (int i = 0; i < count; i++) {
                Object item = Core.call(group, "getPreference", i);
                if (key.equals(Core.call(item, "getKey"))) {
                    return item;
                }
                Object nested = findPreference(item, key); // 叶子节点会抛异常，被下面的 catch 吞掉
                if (nested != null) {
                    return nested;
                }
            }
        } catch (Throwable ignore) {
            // 不是 PreferenceGroup
        }
        return null;
    }

    private static Context prefContext(Object fragment) {
        try {
            Object manager = Core.call(fragment, "getPreferenceManager");
            if (manager != null) {
                Object ctx = Core.call(manager, "getContext");
                if (ctx instanceof Context) {
                    return (Context) ctx;
                }
            }
        } catch (Throwable ignore) {
        }
        return (Context) Core.call(fragment, "getContext");
    }

    private static void dumpTree(Object group, int depth) {
        if (depth > 3) {
            return;
        }
        try {
            int count = (Integer) Core.call(group, "getPreferenceCount");
            for (int i = 0; i < count; i++) {
                Object item = Core.call(group, "getPreference", i);
                StringBuilder sb = new StringBuilder("pref|");
                for (int d = 0; d < depth; d++) {
                    sb.append("  ");
                }
                sb.append(i)
                        .append(" key=").append(safe(item, "getKey"))
                        .append(" order=").append(safe(item, "getOrder"))
                        .append(" title=").append(safe(item, "getTitle"))
                        .append(" cls=").append(item.getClass().getSimpleName());
                Core.log(sb.toString());
                dumpTree(item, depth + 1);
            }
        } catch (Throwable ignore) {
            // 叶子节点
        }
    }

    private static String safe(Object target, String method) {
        try {
            return String.valueOf(Core.call(target, method));
        } catch (Throwable t) {
            return "-";
        }
    }
}
