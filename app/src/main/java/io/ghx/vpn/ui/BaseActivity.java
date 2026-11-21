package io.ghx.vpn.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;

import io.ghx.vpn.R;

/** Locale/theme-only lifecycle glue. No connection, service, process or core operations. */
public class BaseActivity extends Activity {
    private String appearanceSignature;
    private boolean appearanceRecreating;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        setTheme(Appearance.isDark(this) ? R.style.LitLink_Theme_Dark : R.style.LitLink_Theme_Light);
        Appearance.applyPalette(this);
        appearanceSignature = Appearance.signature(this);
        super.onCreate(savedInstanceState);
        applySystemBars();
    }

    @Override public void onContentChanged() {
        super.onContentChanged();
        final View content = findViewById(android.R.id.content);
        if (content == null) return;
        Ui.tintTree(content);
        // Android 15 enforces edge-to-edge at target 35; keep controls out of system bars/IME.
        if (Build.VERSION.SDK_INT >= 35) {
            content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                    return insets;
                }
            });
            content.requestApplyInsets();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (recreateIfChanged()) return;
        // Reassert the palette when returning from another app activity.
        Appearance.applyPalette(this);
        applySystemBars();
        View content = findViewById(android.R.id.content);
        if (content != null) Ui.tintTree(content);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!recreateIfChanged()) applySystemBars();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Wallpaper overlays may update while a foreground activity is paused behind a dialog.
        if (hasFocus && appearanceSignature != null) recreateIfChanged();
    }

    /** Children should return from onResume after super when this is true. */
    public final boolean isAppearanceRecreating() { return appearanceRecreating; }

    /** Called only after a user commits an appearance preference. */
    public final void recreateForAppearance() { recreateIfChanged(); }

    private boolean recreateIfChanged() {
        if (appearanceRecreating) return true;
        if (appearanceSignature == null || isFinishing() || isDestroyed()) return false;
        String current = Appearance.signature(this);
        if (appearanceSignature.equals(current)) return false;
        appearanceRecreating = true; // One recreation per stale instance, including backstack entries.
        recreate();
        return true;
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Ui.DARK) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (Ui.DARK) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else if (!Ui.DARK) {
            // API 24–25 cannot draw dark navigation icons.
            getWindow().setNavigationBarColor(0xff242027);
        }
        decor.setSystemUiVisibility(flags);
    }
}
