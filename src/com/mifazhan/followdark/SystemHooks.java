package com.mifazhan.followdark;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server 侧：把四个触发源合成一个结论，决定极暗开不开。
 *
 *   want = (跟随深色 && 当前是夜间)
 *        || (跟随护眼 && 护眼模式开着)
 *        || (定时开启 && 现在落在时间段内)
 *        || (日出日落 && 系统认为现在是日落之后/日出之前)
 *
 * 事件来源全部是「推送」，没有轮询：
 *   - 挂 com.android.server.UiModeManagerService 的夜间模式重算方法（深色、日出日落都从这走）
 *   - ContentObserver 监听我们自己的 4 个开关 + 时间段 + 护眼模式(settings.system)
 *   - 定时窗口/日落边界用一次性 postDelayed 定时器，只在边界醒一次
 */
final class SystemHooks {

    private static Handler sWorker = null;
    private static boolean sObserversRegistered = false;
    private static Object sService = null;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            recomputeNow(sService, "timer");
        }
    };

    private SystemHooks() {
    }

    static void install(ClassLoader cl) {
        Class<?> service;
        try {
            service = XposedHelpers.findClass("com.android.server.UiModeManagerService", cl);
        } catch (Throwable t) {
            Core.log("UiModeManagerService not found: " + t);
            return;
        }

        XC_MethodHook syncHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                postRecompute(param.thisObject, param.method.getName());
            }
        };

        hookSafely(service, "updateConfigurationLocked", syncHook);
        hookSafely(service, "updateComputedNightModeLocked", syncHook);
        hookSafely(service, "updateNightModeFromSettingsLocked", syncHook);
        hookSafely(service, "applyConfigurationExternallyLocked", syncHook);

        hookSafely(service, "onBootPhase", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int phase = param.args != null && param.args.length > 0
                        ? (Integer) param.args[0] : -1;
                if (phase < 1000) { // SystemService.PHASE_BOOT_COMPLETED
                    return;
                }
                final Object instance = param.thisObject;
                postRecompute(instance, "boot-completed");
                // 开机后 ColorDisplayService 会因为「设备重启后保持」关闭而把极暗复位成 0，
                // 这两次复查排在它后面再纠正一次。
                scheduleDelayed(instance, 20);
                scheduleDelayed(instance, 60);
            }
        });

        Core.log("system_server hooks installed");
    }

    private static void hookSafely(Class<?> clazz, String method, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllMethods(clazz, method, hook);
            Core.log("hooked " + clazz.getSimpleName() + "#" + method);
        } catch (Throwable t) {
            Core.log("hook " + method + " failed: " + t);
        }
    }

    private static void scheduleDelayed(final Object service, int delaySeconds) {
        worker().postDelayed(new Runnable() {
            @Override
            public void run() {
                recomputeNow(service, "boot-recheck");
            }
        }, delaySeconds * 1000L);
    }

    private static void postRecompute(final Object service, final String from) {
        sService = service;
        worker().post(new Runnable() {
            @Override
            public void run() {
                recomputeNow(service, from);
            }
        });
    }

    // ---------------- 核心 ----------------

    private static void recomputeNow(Object service, String from) {
        if (service == null) {
            return;
        }
        try {
            Context ctx = (Context) Core.call(service, "getContext");
            ContentResolver cr = ctx.getContentResolver();
            ensureObservers(ctx, service);

            boolean followDark = Core.flag(cr, Core.SETTING_FOLLOW_DARK);
            boolean followEyecare = Core.flag(cr, Core.SETTING_FOLLOW_EYECARE);
            boolean followSchedule = Core.flag(cr, Core.SETTING_FOLLOW_SCHEDULE);
            String scheduleMode = Core.scheduleMode(cr);
            boolean followSun = followSchedule && Core.MODE_SUN.equals(scheduleMode);

            Boolean want = null;
            StringBuilder why = new StringBuilder();

            if (followDark) {
                boolean dark = Core.getBooleanField(service, "mComputedNightMode");
                want = or(want, dark);
                why.append("dark=").append(dark).append(' ');
            }
            if (followEyecare) {
                boolean eyecare = Core.isEyecareOn(ctx);
                want = or(want, eyecare);
                why.append("eyecare=").append(eyecare).append(' ');
            }
            if (followSchedule) {
                if (followSun) {
                    boolean night = twilightIsNight(service);
                    want = or(want, night);
                    why.append("scheduleMode=sun(").append(night).append(") ");
                } else {
                    int start = Core.parseMinutes(Core.scheduleStart(cr), 22 * 60);
                    int end = Core.parseMinutes(Core.scheduleEnd(cr), 7 * 60);
                    boolean inWindow = Core.inWindow(Core.nowMinutes(), start, end);
                    want = or(want, inWindow);
                    why.append("scheduleMode=custom(").append(inWindow).append(',')
                            .append(Core.formatMinutes(start)).append('-')
                            .append(Core.formatMinutes(end)).append(") ");
                }
            }

            if (want == null) {
                Core.log("no source enabled, skip (" + from + ")");
            } else {
                Core.log("recompute(" + from + "): " + why.toString().trim()
                        + " => want=" + want);
                Core.applyIfNeeded(cr, want, from);
            }

            scheduleNextTick(service, cr);
        } catch (Throwable t) {
            Core.log("recompute failed(" + from + "): " + t);
        }
    }

    private static Boolean or(Boolean current, boolean value) {
        if (current == null) {
            return value;
        }
        return current || value;
    }

    /** 系统日落/日出状态（TwilightState.isNight()）。 */
    private static boolean twilightIsNight(Object service) {
        try {
            Object manager = Core.getField(service, "mTwilightManager");
            if (manager == null) {
                return false;
            }
            Object state = Core.call(manager, "getLastTwilightState");
            if (state == null) {
                return false;
            }
            return (Boolean) Core.call(state, "isNight");
        } catch (Throwable t) {
            Core.log("twilight read failed: " + t);
            return false;
        }
    }

    /** 距离日落/日出边界还有多久（毫秒），读不到就返回 -1。 */
    private static long millisToTwilightEdge(Object service) {
        try {
            Object manager = Core.getField(service, "mTwilightManager");
            if (manager == null) {
                return -1;
            }
            Object state = Core.call(manager, "getLastTwilightState");
            if (state == null) {
                return -1;
            }
            long end = ((Number) Core.getField(state, "endTimeMillis")).longValue();
            long delta = end - System.currentTimeMillis();
            return delta > 0 ? delta : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 只在下一个边界醒一次：定时窗口的起止点、或者日落/日出点。 */
    private static void scheduleNextTick(Object service, ContentResolver cr) {
        long next = Long.MAX_VALUE;

        if (Core.flag(cr, Core.SETTING_FOLLOW_SCHEDULE)) {
            if (Core.MODE_SUN.equals(Core.scheduleMode(cr))) {
                long edge = millisToTwilightEdge(service);
                next = Math.min(next, edge > 0 ? edge : 15 * 60_000L);
            } else {
                int start = Core.parseMinutes(Core.scheduleStart(cr), 22 * 60);
                int end = Core.parseMinutes(Core.scheduleEnd(cr), 7 * 60);
                next = Math.min(next, Core.millisToNextBoundary(Core.nowMinutes(), start, end));
            }
        }

        Handler handler = worker();
        handler.removeCallbacks(TICK);
        if (next == Long.MAX_VALUE) {
            return;
        }
        long clamped = Math.max(1_000L, Math.min(next, 30 * 60_000L));
        handler.postDelayed(TICK, clamped);
    }

    private static void ensureObservers(Context ctx, final Object service) {
        if (sObserversRegistered) {
            return;
        }
        sObserversRegistered = true;
        try {
            ContentObserver secureObserver = new ContentObserver(worker()) {
                @Override
                public void onChange(boolean selfChange) {
                    postRecompute(service, "secure-changed");
                }
            };
            ContentResolver cr = ctx.getContentResolver();
            String[] secureKeys = {
                    Core.SETTING_FOLLOW_DARK,
                    Core.SETTING_FOLLOW_EYECARE,
                    Core.SETTING_FOLLOW_SCHEDULE,
                    Core.SETTING_SCHEDULE_MODE,
                    Core.SETTING_SCHEDULE_START,
                    Core.SETTING_SCHEDULE_END,
            };
            for (String key : secureKeys) {
                cr.registerContentObserver(Settings.Secure.getUriFor(key), false, secureObserver);
            }

            ContentObserver systemObserver = new ContentObserver(worker()) {
                @Override
                public void onChange(boolean selfChange) {
                    postRecompute(service, "eyecare-changed");
                }
            };
            cr.registerContentObserver(Settings.System.getUriFor(Core.SETTING_EYECARE),
                    false, systemObserver);

            Core.log("content observers registered");
        } catch (Throwable t) {
            Core.log("register observers failed: " + t);
        }
    }

    private static synchronized Handler worker() {
        if (sWorker == null) {
            HandlerThread thread = new HandlerThread("FollowDarkSync");
            thread.start();
            sWorker = new Handler(thread.getLooper());
        }
        return sWorker;
    }
}
