package io.ghx.vpn.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 统一视觉：深色渐变底、圆角卡片、细边框、克制的字阶。 */
public final class Ui {

    // 底色（略偏蓝的深黑，比纯黑更有层次）
    public static final int BG = 0xFF0B0E13;
    public static final int BG_2 = 0xFF12171F;
    public static final int CARD = 0xFF161C25;
    public static final int CARD_2 = 0xFF1E2632;
    public static final int LINE = 0xFF26303D;

    public static final int TEXT = 0xFFF2F5F9;
    public static final int TEXT_MID = 0xFFAAB6C6;
    public static final int TEXT_DIM = 0xFF77839A;

    public static final int ACCENT = 0xFF3D7EFF;
    public static final int ACCENT_2 = 0xFF6E5BFF;
    public static final int OK = 0xFF2FCB7E;
    public static final int WARN = 0xFFF0A93B;
    public static final int ERR = 0xFFFF5C63;

    private Ui() {
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
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView title(Context c, String s) {
        return text(c, s, 15f, TEXT);
    }

    public static TextView sub(Context c, String s) {
        return text(c, s, 12f, TEXT_DIM);
    }

    /** 小节标题：字距拉开的小号灰字，替代分割线。 */
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
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    /** 竖向渐变：用于顶部大卡和主按钮。 */
    public static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{from, to});
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** 卡片：圆角 + 细边框，比纯色块更有边界感。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(stroke(c, CARD, LINE, 16f, 1f));
        int p = dp(c, 15);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout cardFlat(Context c) {
        LinearLayout l = column(c);
        l.setBackground(rounded(c, CARD, 16f));
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

    /** 小胶囊标签，用于「直连/规则/全局」等状态。 */
    public static TextView chip(Context c, String s, boolean on) {
        TextView t = text(c, s, 13f, on ? 0xFFFFFFFF : TEXT_MID);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 18), dp(c, 9), dp(c, 18), dp(c, 9));
        t.setBackground(on ? gradient(c, ACCENT, ACCENT_2, 22f)
                : stroke(c, CARD_2, LINE, 22f, 1f));
        return t;
    }

    public static float density(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    public static int alpha(int color, float a) {
        return Color.argb((int) (255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }
}
