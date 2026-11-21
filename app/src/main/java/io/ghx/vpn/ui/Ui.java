package io.ghx.vpn.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Runtime palette shared by the programmatic screens; BaseActivity applies it before content. */
public final class Ui {
    public static int BG = 0xFF0B0E13;
    public static int BG_2 = 0xFF12171F;
    public static int CARD = 0xFF161C25;
    public static int CARD_2 = 0xFF1E2632;
    public static int LINE = 0xFF26303D;
    public static int TEXT = 0xFFF2F5F9;
    public static int TEXT_MID = 0xFFAAB6C6;
    public static int TEXT_DIM = 0xFF9AA6BA;
    /** Exact chosen color for fills; use ACCENT_TEXT on a neutral surface. */
    public static int ACCENT = 0xFF3D7EFF;
    public static int ACCENT_2 = 0xFF356EE0;
    public static int ACCENT_TEXT = 0xFF85ABFF;
    public static int ON_ACCENT = 0xFF000000;
    public static int OK = 0xFF2FCB7E;
    public static int WARN = 0xFFF0A93B;
    public static int ERR = 0xFFFF5C63;
    public static boolean DARK = true;

    private Ui() { }

    public static void applyPalette(boolean dark, int accent) {
        DARK = dark;
        ACCENT = ColorMath.opaque(accent);
        ON_ACCENT = ColorMath.onColor(ACCENT);
        // Gradient moves away from the foreground so both ends retain AA contrast.
        ACCENT_2 = ColorMath.blend(ACCENT, ON_ACCENT == Color.BLACK ? Color.WHITE : Color.BLACK, .08f);
        if (dark) {
            BG = ColorMath.blend(0xff101013, ACCENT, .035f);
            BG_2 = ColorMath.blend(0xff19181c, ACCENT, .045f);
            CARD = ColorMath.blend(0xff222126, ACCENT, .05f);
            CARD_2 = ColorMath.blend(0xff302e34, ACCENT, .06f);
            LINE = ColorMath.blend(0xff514c56, ACCENT, .10f);
            TEXT = 0xfff7f2f7;
            TEXT_MID = 0xffd0c8d1;
            TEXT_DIM = ColorMath.readable(0xffaba2af, CARD_2, 4.5);
            OK = 0xff6ed7a0;
            WARN = 0xfff3c47a;
            ERR = 0xffff969e;
        } else {
            BG = ColorMath.blend(0xfffffbff, ACCENT, .025f);
            BG_2 = ColorMath.blend(0xfff4eff5, ACCENT, .025f);
            CARD = ColorMath.blend(0xfffcf8fc, ACCENT, .025f);
            CARD_2 = ColorMath.blend(0xffeee8ee, ACCENT, .035f);
            LINE = ColorMath.blend(0xffbdb5c0, ACCENT, .08f);
            TEXT = 0xff242027;
            TEXT_MID = 0xff514b56;
            TEXT_DIM = ColorMath.readable(0xff6c6471, CARD_2, 4.5);
            OK = 0xff17663c;
            WARN = 0xff815000;
            ERR = 0xffb32632;
        }
        ACCENT_TEXT = ColorMath.readable(ACCENT, CARD_2, 4.5);
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color == ACCENT ? ACCENT_TEXT : color);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView title(Context c, String s) { return text(c, s, 15f, TEXT); }
    public static TextView sub(Context c, String s) { return text(c, s, 12f, TEXT_DIM); }

    public static TextView sectionLabel(Context c, String s) {
        TextView t = text(c, s, 11f, TEXT_DIM);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), 0, 0, dp(c, 8));
        return t;
    }

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable stroke(Context c, int fill, int strokeColor,
                                          float radiusDp, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return d;
    }

    public static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{from, to});
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(stroke(c, CARD, LINE, 18f, 1f));
        int p = dp(c, 15);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout cardFlat(Context c) {
        LinearLayout l = column(c);
        l.setBackground(rounded(c, CARD, 18f));
        int p = dp(c, 15);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static void margin(Context c, View v, float left, float top, float right, float bottom) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        ViewGroup.MarginLayoutParams lp;
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            lp = (ViewGroup.MarginLayoutParams) raw;
        } else {
            lp = new LinearLayout.LayoutParams(
                    raw == null ? ViewGroup.LayoutParams.MATCH_PARENT : raw.width,
                    raw == null ? ViewGroup.LayoutParams.WRAP_CONTENT : raw.height);
        }
        lp.setMargins(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
        v.setLayoutParams(lp);
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f)));
        lp.topMargin = dp(c, 12);
        lp.bottomMargin = dp(c, 12);
        v.setLayoutParams(lp);
        return v;
    }

    public static TextView chip(Context c, String s, boolean on) {
        TextView t = text(c, s, 13f, on ? ON_ACCENT : TEXT_MID);
        if (on) t.setTextColor(ON_ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 48));
        t.setPadding(dp(c, 18), dp(c, 9), dp(c, 18), dp(c, 9));
        t.setBackground(on ? gradient(c, ACCENT, ACCENT_2, 22f)
                : stroke(c, CARD_2, LINE, 22f, 1f));
        return t;
    }

    public static float density(Context c) { return c.getResources().getDisplayMetrics().density; }

    public static int alpha(int color, float a) {
        return Color.argb((int) (255 * Math.max(0f, Math.min(1f, a))),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static ColorStateList activatedColors(int active, int idle) {
        return new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_checked},
                new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{alpha(idle, .45f), active, active, idle});
    }

    /** Tint native controls without changing their semantics or listeners. */
    public static void tintControl(View view) {
        if (view instanceof EditText) {
            EditText e = (EditText) view;
            e.setTextColor(TEXT);
            e.setHintTextColor(TEXT_DIM);
            e.setHighlightColor(alpha(ACCENT_TEXT, .30f));
            e.setBackgroundTintList(activatedColors(ACCENT_TEXT, LINE));
            if (Build.VERSION.SDK_INT >= 29) {
                Drawable cursor = e.getTextCursorDrawable();
                if (cursor != null) { cursor = cursor.mutate(); cursor.setTint(ACCENT_TEXT); e.setTextCursorDrawable(cursor); }
                Drawable left = e.getTextSelectHandleLeft();
                Drawable right = e.getTextSelectHandleRight();
                Drawable center = e.getTextSelectHandle();
                if (left != null) { left = left.mutate(); left.setTint(ACCENT_TEXT); e.setTextSelectHandleLeft(left); }
                if (right != null) { right = right.mutate(); right.setTint(ACCENT_TEXT); e.setTextSelectHandleRight(right); }
                if (center != null) { center = center.mutate(); center.setTint(ACCENT_TEXT); e.setTextSelectHandle(center); }
            }
        }
        if (view instanceof CheckedTextView) {
            CheckedTextView checked = (CheckedTextView) view;
            checked.setTextColor(TEXT);
            checked.setCheckMarkTintList(activatedColors(ACCENT_TEXT, TEXT_DIM));
        }
        if (view instanceof CompoundButton) {
            CompoundButton b = (CompoundButton) view;
            b.setTextColor(TEXT);
            b.setButtonTintList(activatedColors(ACCENT_TEXT, TEXT_DIM));
            if (b instanceof Switch) {
                Switch s = (Switch) b;
                s.setThumbTintList(activatedColors(ACCENT_TEXT, TEXT_DIM));
                s.setTrackTintList(activatedColors(ColorMath.blend(ACCENT, CARD_2, .45f), LINE));
            }
        }
        if (view instanceof ProgressBar) {
            ProgressBar p = (ProgressBar) view;
            p.setProgressTintList(ColorStateList.valueOf(ACCENT_TEXT));
            p.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
            p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT_TEXT));
            if (p instanceof SeekBar) ((SeekBar) p).setThumbTintList(ColorStateList.valueOf(ACCENT_TEXT));
        }
    }

    public static void tintTree(View view) {
        tintControl(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintTree(group.getChildAt(i));
        }
    }

    /** Call after show(): native dialogs now share the selected surface and accent. */
    public static void tintDialog(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(rounded(dialog.getContext(), CARD, 24f));
            tintDialogTree(dialog.getWindow().getDecorView());
        }
        final ListView list = dialog.getListView();
        if (list != null) {
            list.setBackgroundColor(CARD);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int state) { }
                @Override public void onScroll(AbsListView view, int first, int count, int total) {
                    for (int i = 0; i < view.getChildCount(); i++) tintDialogTree(view.getChildAt(i));
                }
            });
            list.post(new Runnable() {
                @Override public void run() { tintDialogTree(list); }
            });
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) button.setTextColor(ACCENT_TEXT);
        }
    }

    private static void tintDialogTree(View view) {
        if (view instanceof TextView) ((TextView) view).setTextColor(TEXT);
        tintControl(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintDialogTree(group.getChildAt(i));
        }
    }
}
