package io.ghx.vpn.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** App-only preferences: deliberately independent of proxy/core preferences. */
public final class Appearance {
    public static final String PREFS_NAME = "litlink_appearance";
    public static final int DEFAULT_ACCENT = 0xff3d7eff;
    private static final String LANGUAGE = "language";
    private static final String THEME = "theme";
    private static final String MONET = "monet";
    private static final String ACCENT = "accent";

    private Appearance() { }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String language(Context context) {
        String value = preferences(context).getString(LANGUAGE, "system");
        return "en".equals(value) || "zh".equals(value) ? value : "system";
    }

    public static void setLanguage(Context context, String mode) {
        if (!"system".equals(mode) && !"en".equals(mode) && !"zh".equals(mode)) {
            throw new IllegalArgumentException("Unsupported language mode");
        }
        preferences(context).edit().putString(LANGUAGE, mode).apply();
    }

    public static String theme(Context context) {
        String value = preferences(context).getString(THEME, "system");
        return "light".equals(value) || "dark".equals(value) ? value : "system";
    }

    public static void setTheme(Context context, String mode) {
        if (!"system".equals(mode) && !"light".equals(mode) && !"dark".equals(mode)) {
            throw new IllegalArgumentException("Unsupported theme mode");
        }
        preferences(context).edit().putString(THEME, mode).apply();
    }

    public static boolean supportsDynamic() { return Build.VERSION.SDK_INT >= 31; }

    public static boolean isDynamic(Context context) {
        return supportsDynamic() && preferences(context).getBoolean(MONET, false);
    }

    public static void setDynamic(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(MONET, enabled && supportsDynamic()).apply();
    }

    public static int customAccent(Context context) {
        return ColorMath.opaque(preferences(context).getInt(ACCENT, DEFAULT_ACCENT));
    }

    /** Only invoked by the color picker's confirmed callback, never while previewing. */
    public static void setCustomAccent(Context context, int color) {
        preferences(context).edit().putInt(ACCENT, ColorMath.opaque(color))
                .putBoolean(MONET, false).apply();
    }

    public static Locale systemLocale() {
        LocaleList locales = Resources.getSystem().getConfiguration().getLocales();
        return locales.isEmpty() ? Locale.ENGLISH : locales.get(0);
    }

    public static Locale locale(Context context) {
        String mode = language(context);
        if ("en".equals(mode)) return Locale.ENGLISH;
        if ("zh".equals(mode)) return Locale.SIMPLIFIED_CHINESE;
        // Unsupported system languages intentionally fall back to the existing Chinese UI.
        return "en".equalsIgnoreCase(systemLocale().getLanguage())
                ? systemLocale() : Locale.SIMPLIFIED_CHINESE;
    }

    public static boolean isDark(Context context) {
        String mode = theme(context);
        if ("dark".equals(mode)) return true;
        if ("light".equals(mode)) return false;
        return (Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int accent(Context context) {
        if (isDynamic(context)) {
            try {
                // Android 12 public Monet tonal resources, re-resolved after wallpaper changes.
                return ColorMath.opaque(context.getColor(isDark(context)
                        ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600));
            } catch (RuntimeException unavailable) {
                // Some OEM resource sets do not expose wallpaper colors. Keep a usable palette.
            }
        }
        return customAccent(context);
    }

    public static Context wrap(Context base) {
        // Override only appearance fields: copying the entire configuration would freeze
        // orientation, density and screen size in this context after a multi-window resize.
        Configuration config = new Configuration();
        Locale locale = locale(base);
        config.setLocales(new LocaleList(locale));
        config.setLayoutDirection(locale);
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (isDark(base) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        // Do not change Locale.setDefault(): parsers, core, service and system stay untouched.
        return base.createConfigurationContext(config);
    }

    /** Includes effective system values, not just persisted choices. */
    public static String signature(Context context) {
        return language(context) + '|' + locale(context).toLanguageTag() + '|'
                + theme(context) + '|' + isDark(context) + '|'
                + isDynamic(context) + '|' + accent(context);
    }

    public static void applyPalette(Context context) {
        Ui.applyPalette(isDark(context), accent(context));
    }
}
