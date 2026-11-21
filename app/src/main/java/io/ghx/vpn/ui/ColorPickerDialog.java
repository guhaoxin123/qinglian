package io.ghx.vpn.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Draft-only picker. Persistence belongs exclusively to the confirmed callback. */
public final class ColorPickerDialog extends Dialog {
    public interface OnConfirmListener { void onConfirm(int color); }

    private final OnConfirmListener listener;
    private final float[] hsv = new float[3];
    private int color;
    private int tab;
    private boolean updating;
    private boolean confirmed;
    private String pendingHex;
    private int pendingScroll;
    private ScrollView scroll;
    private LinearLayout wheelPanel;
    private LinearLayout rgbPanel;
    private Button wheelTab;
    private Button rgbTab;
    private HueSaturationWheel wheel;
    private SeekBar hueSlider;
    private SeekBar saturationSlider;
    private SeekBar brightnessSlider;
    private final SeekBar[] rgbSliders = new SeekBar[3];
    private final TextView[] rgbLabels = new TextView[3];
    private TextView hueLabel;
    private TextView saturationLabel;
    private TextView brightnessLabel;
    private TextView preview;
    private TextView previewCaption;
    private View brightnessTrack;
    private EditText hex;

    public ColorPickerDialog(Context context, int initialColor, OnConfirmListener listener) {
        super(context);
        this.listener = listener;
        color = ColorMath.opaque(initialColor);
        setHsvFromColor(color);
    }

    private String t(String zh, String en) { return L10n.t(getContext(), zh, en); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        LinearLayout box = Ui.column(getContext());
        int p = Ui.dp(getContext(), 20);
        box.setPadding(p, p, p, Ui.dp(getContext(), 16));
        box.setBackground(Ui.stroke(getContext(), Ui.CARD, Ui.LINE, 28f, 1f));
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(getContext(), t("自定义配色", "Custom palette"), 23f, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, Ui.dp(getContext(), 6));
        box.addView(title);
        TextView subtitle = Ui.text(getContext(), t("先预览，确认后才会保存", "Preview freely. Only Confirm saves changes."),
                13f, Ui.TEXT_DIM);
        subtitle.setPadding(0, 0, 0, Ui.dp(getContext(), 16));
        box.addView(subtitle);

        LinearLayout tabs = Ui.row(getContext());
        wheelTab = button(t("色盘", "Wheel"));
        rgbTab = button("RGB");
        tabs.addView(wheelTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabParams.leftMargin = Ui.dp(getContext(), 8);
        tabs.addView(rgbTab, tabParams);
        box.addView(tabs);
        wheelTab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tab = 0; selectTab(); }
        });
        rgbTab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tab = 1; selectTab(); }
        });

        wheelPanel = Ui.column(getContext());
        wheelPanel.setPadding(0, Ui.dp(getContext(), 8), 0, 0);
        wheel = new HueSaturationWheel(getContext());
        LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wheelParams.gravity = Gravity.CENTER_HORIZONTAL;
        wheelPanel.addView(wheel, wheelParams);
        hueLabel = label("");
        wheelPanel.addView(hueLabel);
        hueSlider = slider(359, t("色相，0 到 359 度", "Hue, 0 to 359 degrees"));
        wheelPanel.addView(hueSlider);
        saturationLabel = label("");
        wheelPanel.addView(saturationLabel);
        saturationSlider = slider(100, t("饱和度，0 到 100%", "Saturation, 0 to 100 percent"));
        wheelPanel.addView(saturationSlider);
        box.addView(wheelPanel);
        hueSlider.setOnSeekBarChangeListener(new SliderListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !updating) { hsv[0] = progress; updateFromHsv(); }
            }
        });
        saturationSlider.setOnSeekBarChangeListener(new SliderListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !updating) { hsv[1] = progress / 100f; updateFromHsv(); }
            }
        });

        rgbPanel = Ui.column(getContext());
        rgbPanel.setPadding(0, Ui.dp(getContext(), 20), 0, 0);
        String[] rgbNames = {t("红色", "Red"), t("绿色", "Green"), t("蓝色", "Blue")};
        for (int i = 0; i < 3; i++) {
            final int channel = i;
            rgbLabels[i] = label(rgbNames[i]);
            rgbPanel.addView(rgbLabels[i]);
            rgbSliders[i] = slider(255, rgbNames[i] + t("，0 到 255", ", 0 to 255"));
            rgbPanel.addView(rgbSliders[i]);
            rgbSliders[i].setOnSeekBarChangeListener(new SliderListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updating) return;
                    int shift = (2 - channel) * 8;
                    color = (color & ~(255 << shift)) | (progress << shift);
                    setHsvFromColor(color);
                    sync(true);
                }
            });
        }
        box.addView(rgbPanel);

        brightnessLabel = label("");
        brightnessLabel.setPadding(0, Ui.dp(getContext(), 8), 0, 0);
        box.addView(brightnessLabel);
        brightnessTrack = new View(getContext());
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 8));
        trackParams.topMargin = Ui.dp(getContext(), 8);
        trackParams.leftMargin = Ui.dp(getContext(), 16);
        trackParams.rightMargin = Ui.dp(getContext(), 16);
        box.addView(brightnessTrack, trackParams);
        brightnessSlider = slider(100, t("亮度，0 到 100%", "Brightness, 0 to 100 percent"));
        box.addView(brightnessSlider);
        brightnessSlider.setOnSeekBarChangeListener(new SliderListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !updating) { hsv[2] = progress / 100f; updateFromHsv(); }
            }
        });

        TextView hexLabel = label(t("Hex 颜色（#RRGGBB）", "Hex color (#RRGGBB)"));
        hexLabel.setPadding(0, Ui.dp(getContext(), 6), 0, 0);
        box.addView(hexLabel);
        hex = new EditText(getContext());
        hex.setId(View.generateViewId());
        hexLabel.setLabelFor(hex.getId());
        hex.setTextSize(18f);
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setSingleLine(true);
        hex.setSelectAllOnFocus(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setImeOptions(EditorInfo.IME_ACTION_DONE);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});
        hex.setHint("#RRGGBB");
        hex.setContentDescription(t("Hex 颜色，输入六位十六进制颜色", "Hex color, enter six hexadecimal digits"));
        Ui.tintControl(hex);
        box.addView(hex, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 56)));
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updating) return;
                Integer parsed = ColorMath.parseHex(s.toString());
                if (parsed != null) {
                    hex.setError(null);
                    color = parsed;
                    setHsvFromColor(color);
                    sync(false); // Leave typing/cursor intact; every valid Hex updates RGB and HSV.
                } else {
                    hex.setError(t("请输入 #RRGGBB 格式的六位颜色", "Enter a six-digit color in #RRGGBB format"));
                }
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        hex.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                if (!validateHex()) return true;
                android.view.inputmethod.InputMethodManager manager =
                        (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) manager.hideSoftInputFromWindow(hex.getWindowToken(), 0);
                hex.clearFocus();
                return true;
            }
        });

        previewCaption = label(t("实时预览", "Live preview"));
        previewCaption.setPadding(0, Ui.dp(getContext(), 14), 0, Ui.dp(getContext(), 8));
        box.addView(previewCaption);
        preview = Ui.text(getContext(), t("LitLink · 按钮预览", "LitLink · Button preview"), 15f, Ui.TEXT);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(Ui.dp(getContext(), 12), Ui.dp(getContext(), 16), Ui.dp(getContext(), 12), Ui.dp(getContext(), 16));
        box.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button reset = button(t("重置为默认蓝色", "Reset to default blue"));
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = Ui.dp(getContext(), 14);
        box.addView(reset, resetParams);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                color = Appearance.DEFAULT_ACCENT;
                setHsvFromColor(color);
                sync(true); // Draft only: Reset followed by Cancel writes nothing.
            }
        });
        LinearLayout actions = Ui.row(getContext());
        actions.setPadding(0, Ui.dp(getContext(), 8), 0, 0);
        Button cancel = button(t("取消", "Cancel"));
        Button confirm = button(t("确认", "Confirm"));
        confirm.setBackground(Ui.rounded(getContext(), Ui.ACCENT, 24f));
        confirm.setTextColor(Ui.ON_ACCENT);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        confirmParams.leftMargin = Ui.dp(getContext(), 8);
        actions.addView(confirm, confirmParams);
        box.addView(actions);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancel(); }
        });
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (confirmed || !validateHex()) return;
                confirmed = true;
                int result = color;
                dismiss();
                if (listener != null) listener.onConfirm(result);
            }
        });
        setContentView(scroll);
        setCanceledOnTouchOutside(true);
        selectTab();
        sync(true);
        if (pendingHex != null) {
            hex.setText(pendingHex);
            hex.setSelection(hex.length());
        }
        scroll.post(new Runnable() {
            @Override public void run() { scroll.scrollTo(0, pendingScroll); }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        resize();
        Window window = getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    /** All controls live inside one scroll view for small screens, landscape and large fonts. */
    public void resize() {
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int width = Math.min(Ui.dp(getContext(), 440),
                getContext().getResources().getDisplayMetrics().widthPixels - Ui.dp(getContext(), 24));
        int height = Math.min(Ui.dp(getContext(), 800),
                getContext().getResources().getDisplayMetrics().heightPixels - Ui.dp(getContext(), 64));
        window.setLayout(Math.max(1, width), Math.max(1, height));
    }

    private TextView label(String text) {
        TextView label = Ui.text(getContext(), text, 13f, Ui.TEXT_MID);
        label.setPadding(0, Ui.dp(getContext(), 4), 0, 0);
        return label;
    }

    private Button button(String title) {
        Button button = new Button(getContext());
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTextColor(Ui.ACCENT_TEXT);
        button.setMinHeight(Ui.dp(getContext(), 48));
        button.setMinimumHeight(Ui.dp(getContext(), 48));
        button.setPadding(Ui.dp(getContext(), 12), Ui.dp(getContext(), 10),
                Ui.dp(getContext(), 12), Ui.dp(getContext(), 10));
        button.setBackground(Ui.stroke(getContext(), Ui.CARD_2, Ui.LINE, 24f, 1f));
        return button;
    }

    private SeekBar slider(int max, String description) {
        SeekBar slider = new SeekBar(getContext());
        slider.setMax(max);
        slider.setContentDescription(description);
        slider.setMinimumHeight(Ui.dp(getContext(), 48));
        slider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 48)));
        Ui.tintControl(slider);
        return slider;
    }

    private void selectTab() {
        wheelPanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        rgbPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        wheelTab.setSelected(tab == 0);
        rgbTab.setSelected(tab == 1);
        styleTab(wheelTab, tab == 0);
        styleTab(rgbTab, tab == 1);
    }

    private void styleTab(Button view, boolean selected) {
        view.setBackground(selected ? Ui.rounded(getContext(), Ui.ACCENT, 24f)
                : Ui.stroke(getContext(), Ui.CARD_2, Ui.LINE, 24f, 1f));
        view.setTextColor(selected ? Ui.ON_ACCENT : Ui.TEXT_MID);
    }

    private void setHsvFromColor(int value) {
        float[] next = ColorMath.toHsv(value);
        // Preserve the user's hue through gray/black, so raising saturation/brightness is useful.
        if (next[1] > 0f) hsv[0] = next[0];
        if (next[2] > 0f) hsv[1] = next[1];
        hsv[2] = next[2];
    }

    private void updateFromHsv() {
        color = ColorMath.hsv(hsv[0], hsv[1], hsv[2]);
        sync(true);
    }

    private void sync(boolean replaceHex) {
        if (hex == null) return;
        updating = true;
        try {
            hueSlider.setProgress(Math.min(359, Math.round(hsv[0])));
            saturationSlider.setProgress(Math.round(hsv[1] * 100));
            brightnessSlider.setProgress(Math.round(hsv[2] * 100));
            hueLabel.setText(t("色相", "Hue") + " · " + Math.round(hsv[0]) + "°");
            saturationLabel.setText(t("饱和度", "Saturation") + " · " + Math.round(hsv[1] * 100) + "%");
            brightnessLabel.setText(t("亮度", "Brightness") + " · " + Math.round(hsv[2] * 100) + "%");
            String[] names = {t("红色", "Red"), t("绿色", "Green"), t("蓝色", "Blue")};
            for (int i = 0; i < 3; i++) {
                int channel = (color >> ((2 - i) * 8)) & 255;
                rgbSliders[i].setProgress(channel);
                rgbLabels[i].setText(names[i] + " · " + channel);
            }
            GradientDrawable track = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Color.BLACK, ColorMath.hsv(hsv[0], hsv[1], 1f)});
            track.setCornerRadius(Ui.dp(getContext(), 8));
            track.setStroke(Ui.dp(getContext(), 1), Ui.TEXT_DIM);
            brightnessTrack.setBackground(track);
            if (replaceHex) {
                hex.setText(ColorMath.hex(color));
                hex.setError(null);
            }
            preview.setBackground(Ui.stroke(getContext(), color, Ui.TEXT_DIM, 24f, 1.5f));
            preview.setTextColor(ColorMath.onColor(color));
            preview.setContentDescription(t("当前颜色预览 ", "Color preview ") + ColorMath.hex(color));
            previewCaption.setText(t("实时预览", "Live preview") + " · " + ColorMath.hex(color));
            wheel.invalidate();
            wheel.setContentDescription(t("色相与饱和度色盘；也可使用下方滑块", "Hue and saturation wheel; sliders below provide an accessible alternative")
                    + ". " + Math.round(hsv[0]) + "°, " + Math.round(hsv[1] * 100) + "%");
        } finally { updating = false; }
    }

    private boolean validateHex() {
        Integer parsed = ColorMath.parseHex(hex.getText().toString());
        if (parsed == null) {
            hex.setError(t("请输入 #RRGGBB 格式的六位颜色", "Enter a six-digit color in #RRGGBB format"));
            hex.requestFocus();
            return false; // Never dismiss invalid input.
        }
        color = parsed;
        setHsvFromColor(color);
        return true;
    }

    public Bundle saveDraft() {
        Bundle state = new Bundle();
        state.putInt("color", color);
        state.putInt("tab", tab);
        state.putFloatArray("hsv", hsv.clone());
        state.putString("hex", hex == null ? pendingHex : hex.getText().toString());
        state.putInt("scroll", scroll == null ? pendingScroll : scroll.getScrollY());
        return state;
    }

    /** Call before show(), e.g. when Activity state is restored. No preference writes. */
    public void restoreDraft(Bundle state) {
        if (state == null) return;
        color = ColorMath.opaque(state.getInt("color", color));
        tab = state.getInt("tab", 0) == 1 ? 1 : 0;
        setHsvFromColor(color);
        float[] restored = state.getFloatArray("hsv");
        if (restored != null && restored.length == 3) {
            hsv[0] = restored[0]; hsv[1] = restored[1]; hsv[2] = restored[2];
        }
        pendingHex = state.getString("hex");
        pendingScroll = state.getInt("scroll", 0);
    }

    private abstract static class SliderListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    /** Full disk: angle controls hue, radius controls saturation; brightness is separate. */
    private final class HueSaturationWheel extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader hueShader;
        private Shader saturationShader;
        private float cx, cy, radius;
        private boolean dragging;

        HueSaturationWheel(Context context) {
            super(context);
            setFocusable(true);
            setClickable(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int preferred = Ui.dp(getContext(), 260);
            int width = resolveSize(preferred, widthSpec);
            int height = resolveSize(Math.min(width, preferred), heightSpec);
            setMeasuredDimension(width, height);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            cx = width / 2f;
            cy = height / 2f;
            radius = Math.max(1, Math.min(width, height) / 2f - Ui.dp(getContext(), 12));
            hueShader = new SweepGradient(cx, cy, new int[]{Color.RED, Color.YELLOW, Color.GREEN,
                    Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED}, null);
            saturationShader = new RadialGradient(cx, cy, radius,
                    new int[]{Color.WHITE, 0x00ffffff}, null, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(hueShader);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(saturationShader);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(Math.round((1f - hsv[2]) * 255), 0, 0, 0));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 1));
            paint.setColor(Ui.TEXT_DIM);
            canvas.drawCircle(cx, cy, radius, paint);
            double angle = Math.toRadians(hsv[0]);
            float x = cx + (float) Math.cos(angle) * radius * hsv[1];
            float y = cy + (float) Math.sin(angle) * radius * hsv[1];
            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(Ui.dp(getContext(), 5));
            canvas.drawCircle(x, y, Ui.dp(getContext(), 8), paint);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(Ui.dp(getContext(), 2.5f));
            canvas.drawCircle(x, y, Ui.dp(getContext(), 8), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float dx = event.getX() - cx, dy = event.getY() - cy;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (Math.hypot(dx, dy) > radius + Ui.dp(getContext(), 12)) return false;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (!dragging) return false;
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP) {
                float distance = (float) Math.hypot(dx, dy);
                if (distance > 1f) hsv[0] = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360);
                hsv[1] = Math.max(0f, Math.min(1f, distance / radius));
                updateFromHsv();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) hsv[0] = (hsv[0] + 359f) % 360f;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) hsv[0] = (hsv[0] + 1f) % 360f;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) hsv[1] = Math.min(1f, hsv[1] + .01f);
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) hsv[1] = Math.max(0f, hsv[1] - .01f);
            else return super.onKeyDown(keyCode, event);
            updateFromHsv();
            return true;
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(SeekBar.class.getName());
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 359, hsv[0]));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                hsv[0] = (hsv[0] + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 5f : 355f)) % 360f;
                updateFromHsv();
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }
}
