package io.ghx.vpn;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.ghx.vpn.core.DocumentImport;
import io.ghx.vpn.core.Prefs;
import io.ghx.vpn.core.Profile;
import io.ghx.vpn.core.Session;
import io.ghx.vpn.core.SubscriptionManager;
import io.ghx.vpn.sub.SubParser;
import io.ghx.vpn.svc.TunnelService;
import io.ghx.vpn.ui.BaseActivity;
import io.ghx.vpn.ui.L10n;
import io.ghx.vpn.ui.Ui;

/** Subscription management. Document import is an explicit, reviewed, offline operation. */
public class SubsActivity extends BaseActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final Handler IMPORT_UI = new Handler(Looper.getMainLooper());
    private static final Set<String> REFRESHING = Collections.synchronizedSet(new HashSet<String>());
    private static final int OPEN_DOCUMENT = 6024;
    private static final String STATE_IMPORT = "document_import_phase";

    private SubscriptionManager subs;
    private LinearLayout list;
    private TextView hint;
    private TextView importStatus;
    private ImportState pending;
    private AlertDialog reviewDialog;
    private EditText reviewName;
    private boolean resumed;

    private enum Phase { IDLE, PICKING, READING, REVIEW, SAVING, SAVED, ERROR }
    private enum Failure { PERMISSION, READ, CONTENT, SAVE, UNAVAILABLE }

    /** Retained only in memory across recreation; never put document text or URI in a Bundle. */
    private static final class ImportState {
        Phase phase = Phase.IDLE;
        int generation;
        DocumentImport.Preview preview;
        DocumentImport.Problem problem;
        Failure failure;
        String name;
        WeakReference<SubsActivity> owner = new WeakReference<SubsActivity>(null);

        void reset() {
            generation++;
            phase = Phase.IDLE;
            preview = null;
            name = null;
            problem = null;
            failure = null;
        }

        void notifyOwner() {
            SubsActivity activity = owner.get();
            if (activity != null) activity.renderImport();
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        subs = new SubscriptionManager(this);
        Object retained = getLastNonConfigurationInstance();
        pending = retained instanceof ImportState ? (ImportState) retained : new ImportState();
        // A picker can still deliver its one result after process death. Reviewed text is not
        // restored or reread; a save interrupted by process death must never be replayed.
        if (!(retained instanceof ImportState) && savedInstanceState != null) {
            int phase = savedInstanceState.getInt(STATE_IMPORT, Phase.IDLE.ordinal());
            if (phase == Phase.PICKING.ordinal()) pending.phase = Phase.PICKING;
            else if (phase != Phase.IDLE.ordinal()) {
                Toast.makeText(this, L10n.t(this,
                        "导入已中断。请先检查配置列表，再重新选择文件。",
                        "Import was interrupted. Check the profile list before choosing the file again."),
                        Toast.LENGTH_LONG).show();
            }
        }
        pending.owner = new WeakReference<SubsActivity>(this);
        getWindow().setStatusBarColor(Ui.BG);
        setContentView(build());
        refresh();
        handleImportIntent();
        if (savedInstanceState == null && getIntent() != null
                && getIntent().getBooleanExtra("open_add_subscription", false)) addDialog();
    }

    @Override protected void onResume() {
        super.onResume();
        if (isAppearanceRecreating()) return;
        resumed = true;
        if (list != null) refresh();
        renderImport();
    }

    @Override protected void onPause() {
        resumed = false;
        rememberReviewName();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        rememberReviewName();
        out.putInt(STATE_IMPORT, pending.phase.ordinal());
        super.onSaveInstanceState(out);
    }

    @Override public Object onRetainNonConfigurationInstance() {
        rememberReviewName();
        return pending;
    }

    @Override protected void onDestroy() {
        resumed = false;
        if (pending != null && pending.owner.get() == this) {
            pending.owner.clear();
            if (!isChangingConfigurations()) pending.reset();
        }
        if (reviewDialog != null) {
            reviewDialog.setOnCancelListener(null);
            reviewDialog.dismiss();
            reviewDialog = null;
            reviewName = null;
        }
        super.onDestroy();
    }

    private void rememberReviewName() {
        if (reviewName != null && pending != null && pending.phase == Phase.REVIEW)
            pending.name = reviewName.getText().toString();
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout content = Ui.column(this);
        int p = Ui.dp(this, 16);
        content.setPadding(p, Ui.dp(this, 26), p, Ui.dp(this, 30));
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout head = Ui.row(this);
        TextView back = Ui.text(this, "‹", 22f, Ui.ACCENT_TEXT);
        back.setContentDescription(L10n.t(this, "返回", "Back"));
        back.setPadding(0, 0, Ui.dp(this, 10), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back);
        TextView title = Ui.text(this, L10n.t(this, "配置", "Profiles"), 19f, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title);
        content.addView(head);
        hint = Ui.text(this, L10n.t(this, "点卡片选用配置 · 支持 Clash / v2ray / Surfboard",
                "Tap a card to select · Clash / v2ray / Surfboard"), 12f, Ui.TEXT_DIM);
        hint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 16));
        content.addView(hint);
        list = Ui.column(this);
        content.addView(list);
        LinearLayout buttons = Ui.row(this);
        TextView add = fillBtn(L10n.t(this, "＋ 添加订阅", "+ Add subscription"), true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addDialog(); }
        });
        TextView file = fillBtn(L10n.t(this, "导入文件", "Import file"), false);
        file.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDocument(); }
        });
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        first.rightMargin = Ui.dp(this, 8);
        buttons.addView(add, first);
        buttons.addView(file, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.topMargin = Ui.dp(this, 18);
        content.addView(buttons, row);
        // Keep paste and the original add-subscription action visible even on narrow screens.
        TextView paste = fillBtn(L10n.t(this, "粘贴导入", "Paste import"), false);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { localDialog(); }
        });
        LinearLayout.LayoutParams pasteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pasteLp.topMargin = Ui.dp(this, 8);
        content.addView(paste, pasteLp);
        importStatus = Ui.text(this, "", 12f, Ui.TEXT_DIM);
        importStatus.setPadding(0, Ui.dp(this, 8), 0, 0);
        importStatus.setVisibility(View.GONE);
        content.addView(importStatus);
        return sv;
    }

    private TextView fillBtn(String label, boolean primary) {
        TextView view = Ui.text(this, label, 14f, primary ? Ui.ON_ACCENT : Ui.TEXT_MID);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Ui.dp(this, 6), Ui.dp(this, 13), Ui.dp(this, 6), Ui.dp(this, 13));
        view.setBackground(primary ? Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_2, 12f)
                : Ui.stroke(this, Ui.CARD_2, Ui.LINE, 12f, 1f));
        return view;
    }

    private void refresh() {
        if (isFinishing() || isDestroyed()) return;
        list.removeAllViews();
        try {
            List<Profile> all = subs.list();
            if (all.isEmpty()) {
                TextView empty = Ui.text(this, L10n.t(this,
                        "还没有配置，点下方「添加订阅」、导入文件或粘贴导入",
                        "No profiles yet. Add a subscription, import a file or paste a configuration below."),
                        13f, Ui.TEXT_DIM);
                empty.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
                list.addView(empty);
                return;
            }
            Profile active = subs.active();
            for (Profile profile : all) list.addView(card(profile, active != null && profile.id.equals(active.id)));
        } catch (RuntimeException error) {
            list.removeAllViews();
            list.addView(Ui.text(this, L10n.t(this, "无法读取配置：", "Cannot read profiles: ")
                    + L10n.error(this, error), 13f, Ui.ERR));
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private void showError(String action, Throwable error) {
        if (!isFinishing() && !isDestroyed()) Toast.makeText(this,
                action + L10n.t(this, "：", ": ") + L10n.error(this, error), Toast.LENGTH_LONG).show();
    }

    private View card(final Profile profile, boolean active) {
        LinearLayout card = Ui.card(this);
        if (active) card.setBackground(Ui.stroke(this, Ui.CARD, Ui.ACCENT, 16f, 1.5f));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { use(profile); }
        });
        LinearLayout top = Ui.row(this);
        TextView name = Ui.text(this, profile.name, 15f, Ui.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (active) {
            TextView on = Ui.text(this, L10n.t(this, "使用中", "Selected"), 10f, Ui.ON_ACCENT);
            on.setBackground(Ui.rounded(this, Ui.ACCENT, 9f));
            on.setPadding(Ui.dp(this, 7), Ui.dp(this, 3), Ui.dp(this, 7), Ui.dp(this, 3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            top.addView(on, lp);
        }
        top.addView(Ui.text(this, typeLabel(profile), 11f, Ui.ACCENT_TEXT));
        card.addView(top);
        int count = -1;
        try { count = subs.nodesOf(profile).size(); } catch (RuntimeException ignored) { }
        String info;
        if (profile.lastError != null) info = L10n.t(this, "错误：", "Error: ") + L10n.message(this, profile.lastError);
        else if (profile.updatedAt > 0) info = L10n.t(this, "更新于 ", "Updated ") + ago(profile.updatedAt)
                + (count > 0 ? L10n.t(this, " · " + count + " 节点", " · " + count + " nodes") : "");
        else info = L10n.t(this, "尚未更新", "Not updated yet");
        TextView sub = Ui.text(this, info, 12f, profile.lastError != null ? Ui.ERR : Ui.TEXT_DIM);
        sub.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 11));
        card.addView(sub);
        LinearLayout actions = Ui.row(this);
        if (!active) {
            TextView select = action(L10n.t(this, "选用", "Select"), Ui.ACCENT_TEXT);
            select.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { use(profile); }
            });
            actions.addView(select);
        } else {
            TextView nodes = action(L10n.t(this, "节点", "Nodes"), Ui.ACCENT_TEXT);
            nodes.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    subs.select(profile.id);
                    startActivity(new Intent(SubsActivity.this, NodesActivity.class));
                }
            });
            actions.addView(nodes);
        }
        if (profile.hasUrl()) {
            TextView update = action(L10n.t(this, "刷新", "Refresh"), Ui.TEXT_MID);
            update.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { doRefresh(profile); }
            });
            actions.addView(update);
        }
        TextView auto = action(profile.autoUpdateHours > 0
                ? L10n.t(this, "自动 " + profile.autoUpdateHours + "h", "Auto " + profile.autoUpdateHours + "h")
                : L10n.t(this, "不自动", "Manual"), Ui.TEXT_MID);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int next = profile.autoUpdateHours == 0 ? 6 : profile.autoUpdateHours == 6 ? 12
                        : profile.autoUpdateHours == 12 ? 24 : 0;
                try { subs.setAutoUpdateHours(profile.id, next); refresh(); }
                catch (RuntimeException error) { showError(L10n.t(SubsActivity.this, "保存失败", "Save failed"), error); }
            }
        });
        actions.addView(auto);
        TextView delete = action(L10n.t(this, "删除", "Delete"), Ui.ERR);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AlertDialog dialog = new AlertDialog.Builder(SubsActivity.this)
                        .setTitle(L10n.t(SubsActivity.this, "删除配置", "Delete profile"))
                        .setMessage(L10n.t(SubsActivity.this, "确定删除「" + profile.name + "」？", "Delete “" + profile.name + "”?"))
                        .setNegativeButton(L10n.t(SubsActivity.this, "取消", "Cancel"), null)
                        .setPositiveButton(L10n.t(SubsActivity.this, "删除", "Delete"), new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                try { subs.remove(profile.id); refresh(); }
                                catch (RuntimeException error) { showError(L10n.t(SubsActivity.this, "删除失败", "Delete failed"), error); }
                            }
                        }).show();
                Ui.tintDialog(dialog);
            }
        });
        actions.addView(delete);
        // Horizontal scrolling preserves every existing action with larger English labels.
        android.widget.HorizontalScrollView actionScroll = new android.widget.HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(actions);
        card.addView(actionScroll);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);
        return card;
    }

    private String typeLabel(Profile profile) {
        if (profile.type == Profile.TYPE_CLASH) return L10n.t(this, "Clash", "Clash");
        if (profile.type == Profile.TYPE_SURFBOARD) return L10n.t(this, "Surfboard", "Surfboard");
        return L10n.t(this, "v2ray", "v2ray");
    }

    private TextView action(String label, int color) {
        TextView view = Ui.text(this, label, 12f, color);
        view.setBackground(Ui.rounded(this, Ui.CARD_2, 16f));
        view.setPadding(Ui.dp(this, 13), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 7);
        view.setLayoutParams(lp);
        return view;
    }

    /** Only an explicit profile-selection action may reload an already-running core. */
    private void use(final Profile profile) {
        try {
            if (subs.store().byId(subs.list(), profile.id) == null)
                throw new IllegalStateException(L10n.t(this, "配置已删除", "Profile was deleted"));
            subs.select(profile.id);
            Toast.makeText(this, L10n.t(this, "已选用「" + profile.name + "」", "Selected “" + profile.name + "”"), Toast.LENGTH_SHORT).show();
            refresh();
            if (!TunnelService.isRunning() && !TunnelService.isStarting()) return;
            final Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    Session.CORE_LOCK.lock();
                    try {
                        if (!TunnelService.isRunning() || !Session.isKernelRunning()
                                || !profile.id.equals(new Prefs(app).activeProfile())) return;
                        Session.reload(app, new SubscriptionManager(app));
                    } catch (final Exception error) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                showError(L10n.t(SubsActivity.this, "配置已保存，但应用失败", "Profile saved, but applying failed"), error);
                            }
                        });
                    } finally { Session.CORE_LOCK.unlock(); }
                }
            }, "sub-apply").start();
        } catch (RuntimeException error) { showError(L10n.t(this, "选用失败", "Selection failed"), error); }
    }

    private EditText nameInput(String value) {
        EditText name = new EditText(this);
        name.setHint(L10n.t(this, "名称（可留空）", "Name (optional)"));
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.TEXT_DIM);
        name.setSingleLine(true);
        name.setFilters(new InputFilter[] { new InputFilter.LengthFilter(256) });
        name.setSaveEnabled(false);
        if (value != null) name.setText(value);
        Ui.tintControl(name);
        return name;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = Ui.column(this);
        int padding = Ui.dp(this, 18);
        box.setPadding(padding, Ui.dp(this, 6), padding, 0);
        return box;
    }

    /** Preserve the original Add subscription shortcut and behavior. */
    private void addDialog() {
        LinearLayout box = dialogBox();
        final EditText name = nameInput(null);
        box.addView(name);
        final EditText url = new EditText(this);
        url.setHint(L10n.t(this, "订阅链接 http(s)://…", "Subscription URL http(s)://…"));
        url.setTextColor(Ui.TEXT);
        url.setHintTextColor(Ui.TEXT_DIM);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        Ui.tintControl(url);
        box.addView(url);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(L10n.t(this, "添加订阅", "Add subscription"))
                .setView(box)
                .setNegativeButton(L10n.t(this, "取消", "Cancel"), null)
                .setPositiveButton(L10n.t(this, "添加", "Add"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            String value = url.getText().toString().trim();
                            if (value.length() == 0) return;
                            String scheme = Uri.parse(value).getScheme();
                            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
                                throw new IllegalArgumentException(L10n.t(SubsActivity.this,
                                        "订阅地址需以 http:// 或 https:// 开头", "Subscription URLs must start with http:// or https://"));
                            String label = name.getText().toString().trim();
                            if (label.length() == 0) label = L10n.t(SubsActivity.this,
                                    "订阅 " + (subs.list().size() + 1), "Subscription " + (subs.list().size() + 1));
                            Profile profile = subs.add(label, value, new Prefs(SubsActivity.this).autoUpdateHours());
                            subs.select(profile.id);
                            refresh();
                            doRefresh(profile);
                        } catch (RuntimeException error) { showError(L10n.t(SubsActivity.this, "添加失败", "Add failed"), error); }
                    }
                }).show();
        Ui.tintDialog(dialog);
    }

    private boolean importBusy() {
        if (pending.phase == Phase.IDLE) return false;
        Toast.makeText(this, L10n.t(this, "请先完成或取消当前导入", "Finish or cancel the current import first"), Toast.LENGTH_SHORT).show();
        return true;
    }

    private void openDocument() {
        if (importBusy()) return;
        pending.phase = Phase.PICKING;
        pending.generation++;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        // Some providers report YAML/conf/txt as application/octet-stream or an unknown MIME.
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(picker, OPEN_DOCUMENT); }
        catch (ActivityNotFoundException error) {
            pending.reset();
            Toast.makeText(this, L10n.t(this, "未找到系统文件选择器", "No system document picker is available"), Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            pending.reset();
            Toast.makeText(this, L10n.t(this, "无法打开文件选择器，请重试", "Cannot open the document picker. Please try again."), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != OPEN_DOCUMENT || pending.phase != Phase.PICKING) return;
        // Claim the result before starting any work: duplicate callbacks cannot stage/save twice.
        pending.phase = Phase.READING;
        if (resultCode != RESULT_OK) { pending.reset(); renderImport(); return; }
        Uri uri = result == null ? null : result.getData();
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            pending.failure = Failure.READ;
            pending.phase = Phase.ERROR;
            renderImport();
            return;
        }
        startDocumentRead(pending, getApplicationContext(), uri);
        renderImport();
    }

    /** No activity reference is captured by provider I/O, including a slow/cloud provider. */
    private static void startDocumentRead(final ImportState state, final Context app, final Uri uri) {
        final int generation = state.generation;
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    DocumentImport.Preview preview = null;
                    String name = null;
                    Failure failure = null;
                    DocumentImport.Problem problem = null;
                    try {
                        ContentResolver resolver = app.getContentResolver();
                        name = displayName(resolver, uri);
                        preview = DocumentImport.inspect(DocumentImport.read(resolver.openInputStream(uri)));
                    } catch (DocumentImport.ImportException error) {
                        failure = Failure.CONTENT;
                        problem = error.problem;
                    } catch (SecurityException error) { failure = Failure.PERMISSION; }
                    catch (IOException error) { failure = Failure.READ; }
                    catch (RuntimeException error) { failure = Failure.READ; }
                    catch (OutOfMemoryError error) { failure = Failure.CONTENT; problem = DocumentImport.Problem.TOO_LARGE; }
                    finishRead(state, generation, preview, name, failure, problem);
                }
            }, "document-read").start();
        } catch (RuntimeException error) {
            finishRead(state, generation, null, null, Failure.UNAVAILABLE, null);
        }
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        // Metadata is optional and untrusted; never fall back to a URI/path containing secrets.
        try (Cursor cursor = resolver.query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String name = column < 0 ? null : cursor.getString(column);
                if (name != null) {
                    StringBuilder safe = new StringBuilder();
                    for (int i = 0; i < name.length() && safe.length() < 256; i++) {
                        char c = name.charAt(i);
                        if (!Character.isISOControl(c) && c != '\u202e' && c != '\u202d') safe.append(c);
                    }
                    return safe.toString();
                }
            }
        } catch (RuntimeException ignored) { /* The stream may still be readable. */ }
        return null;
    }

    private static void finishRead(final ImportState state, final int generation,
            final DocumentImport.Preview preview, final String name, final Failure failure,
            final DocumentImport.Problem problem) {
        IMPORT_UI.post(new Runnable() {
            @Override public void run() {
                if (state.generation != generation || state.phase != Phase.READING) return;
                state.preview = preview;
                state.name = name;
                state.failure = failure;
                state.problem = problem;
                state.phase = failure == null ? Phase.REVIEW : Phase.ERROR;
                state.notifyOwner();
            }
        });
    }

    /** Paste remains a separate visible action, but now shares the validation/review boundary. */
    private void localDialog() {
        if (importBusy()) return;
        LinearLayout box = dialogBox();
        final EditText name = nameInput(null);
        box.addView(name);
        final EditText body = new EditText(this);
        body.setHint(L10n.t(this, "粘贴 Clash YAML、Surfboard 配置或 v2ray 链接（每行一条）",
                "Paste Clash YAML, Surfboard configuration or v2ray links (one per line)"));
        body.setTextColor(Ui.TEXT);
        body.setHintTextColor(Ui.TEXT_DIM);
        body.setMinLines(5);
        body.setMaxLines(10);
        body.setGravity(Gravity.TOP);
        body.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.setSaveEnabled(false);
        Ui.tintControl(body);
        box.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(L10n.t(this, "粘贴导入", "Paste import"))
                .setView(box)
                .setNegativeButton(L10n.t(this, "取消", "Cancel"), null)
                .setPositiveButton(L10n.t(this, "下一步", "Review"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (pending.phase != Phase.IDLE) return;
                        final String text = body.getText().toString();
                        final String label = name.getText().toString();
                        final ImportState state = pending;
                        final int generation = ++state.generation;
                        state.phase = Phase.READING;
                        try {
                            new Thread(new Runnable() {
                                @Override public void run() {
                                    try { finishRead(state, generation, DocumentImport.inspect(text), label, null, null); }
                                    catch (DocumentImport.ImportException error) {
                                        finishRead(state, generation, null, null, Failure.CONTENT, error.problem);
                                    } catch (RuntimeException error) {
                                        finishRead(state, generation, null, null, Failure.CONTENT, DocumentImport.Problem.MALFORMED);
                                    } catch (OutOfMemoryError error) {
                                        finishRead(state, generation, null, null, Failure.CONTENT, DocumentImport.Problem.TOO_LARGE);
                                    }
                                }
                            }, "paste-review").start();
                        } catch (RuntimeException error) {
                            finishRead(state, generation, null, null, Failure.UNAVAILABLE, null);
                        }
                        renderImport();
                    }
                }).show();
        Ui.tintDialog(dialog);
    }

    private void renderImport() {
        if (pending == null || !resumed || isFinishing() || isDestroyed()) return;
        if (importStatus != null) {
            boolean working = pending.phase == Phase.READING || pending.phase == Phase.SAVING;
            importStatus.setVisibility(working ? View.VISIBLE : View.GONE);
            if (working) importStatus.setText(pending.phase == Phase.READING
                    ? L10n.t(this, "正在读取并检查文件…", "Reading and checking configuration…")
                    : L10n.t(this, "正在保存配置…", "Saving profile…"));
        }
        if (pending.phase == Phase.REVIEW && reviewDialog == null) showReview();
        else if (pending.phase == Phase.ERROR) {
            String error = importError();
            pending.reset();
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        } else if (pending.phase == Phase.SAVED) {
            pending.reset();
            refresh();
            Toast.makeText(this, L10n.t(this, "配置已导入；未切换配置或启动 VPN",
                    "Profile imported. No profile switch or VPN start was requested."), Toast.LENGTH_LONG).show();
        }
    }

    private void showReview() {
        final ImportState state = pending;
        final int generation = state.generation;
        if (state.preview == null) { state.reset(); return; }
        LinearLayout box = dialogBox();
        if (state.name == null || state.name.trim().length() == 0)
            state.name = L10n.t(this, "本地配置", "Local profile");
        final EditText name = nameInput(state.name);
        reviewName = name;
        box.addView(name);
        int format = state.preview.format;
        String formatName = format == SubParser.FMT_CLASH ? L10n.t(this, "Clash YAML", "Clash YAML")
                : format == SubParser.FMT_SURFBOARD ? L10n.t(this, "Surfboard", "Surfboard")
                : L10n.t(this, "v2ray 链接", "v2ray links");
        int count = state.preview.nodeCount;
        String summary = L10n.t(this, "格式：", "Format: ") + formatName + "\n"
                + (count >= 0 ? L10n.t(this, "可识别节点：" + count, "Recognized nodes: " + count)
                : L10n.t(this, "节点数：暂无法确定（提供器或高级 YAML）", "Node count: unknown (providers or advanced YAML)"))
                + "\n\n" + L10n.t(this, "只保存配置，不选用、不重载内核、不启动 VPN。",
                        "Only saves this profile. Does not select it, reload the core or start the VPN.");
        if (format == SubParser.FMT_CLASH) summary += "\n" + L10n.t(this,
                "仅做基础检查，不代表已通过完整 YAML/内核校验；不会下载提供器。",
                "Basic checks only, not full YAML/core validation. Providers are not downloaded.");
        TextView details = Ui.text(this, summary, 13f, Ui.TEXT_MID);
        details.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        box.addView(details);
        reviewDialog = new AlertDialog.Builder(this)
                .setTitle(L10n.t(this, "确认导入", "Review import"))
                .setView(box)
                .setNegativeButton(L10n.t(this, "取消", "Cancel"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (state.generation == generation && state.phase == Phase.REVIEW) state.reset();
                        reviewDialog = null;
                        reviewName = null;
                        renderImport();
                    }
                })
                .setPositiveButton(L10n.t(this, "导入", "Import"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (state.generation != generation || state.phase != Phase.REVIEW) return;
                        String label = name.getText().toString().trim();
                        if (label.length() == 0) label = L10n.t(SubsActivity.this, "本地配置", "Local profile");
                        // Claim confirmation before dispatch; repeated taps/callbacks cannot save twice.
                        state.phase = Phase.SAVING;
                        reviewDialog = null;
                        reviewName = null;
                        saveDocument(state, generation, getApplicationContext(), label, state.preview.text);
                        renderImport();
                    }
                }).create();
        reviewDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dialog) {
                if (state.generation == generation && state.phase == Phase.REVIEW) state.reset();
                reviewDialog = null;
                reviewName = null;
                renderImport();
            }
        });
        reviewDialog.show();
        Ui.tintDialog(reviewDialog);
    }

    private static void saveDocument(final ImportState state, final int generation, final Context app,
            final String name, final String text) {
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    boolean saved = false;
                    try {
                        // This is the ONLY persistence boundary for file/paste import. In particular,
                        // do not call select(), use(), Session, TunnelService, refresh(url) or intents.
                        new SubscriptionManager(app).addRaw(name, text, 0);
                        saved = true;
                    } catch (RuntimeException ignored) { }
                    catch (OutOfMemoryError ignored) { }
                    final boolean result = saved;
                    IMPORT_UI.post(new Runnable() {
                        @Override public void run() {
                            if (state.generation != generation || state.phase != Phase.SAVING) return;
                            state.preview = null;
                            state.name = null;
                            state.failure = result ? null : Failure.SAVE;
                            state.phase = result ? Phase.SAVED : Phase.ERROR;
                            state.notifyOwner();
                        }
                    });
                }
            }, "document-save").start();
        } catch (RuntimeException error) {
            state.preview = null;
            state.failure = Failure.SAVE;
            state.phase = Phase.ERROR;
            state.notifyOwner();
        }
    }

    private String importError() {
        if (pending.failure == Failure.PERMISSION) return L10n.t(this,
                "没有读取此文件的权限，请重新选择文件。", "Read permission was denied. Please choose the file again.");
        if (pending.failure == Failure.SAVE) return L10n.t(this,
                "无法完成保存。请检查配置列表及可用空间，确认后再重试。",
                "Could not complete the save. Check the profile list and free storage before retrying.");
        if (pending.failure == Failure.UNAVAILABLE) return L10n.t(this,
                "暂时无法开始导入，请稍后重试。", "Cannot start importing right now. Please try again later.");
        if (pending.failure == Failure.CONTENT && pending.problem != null) {
            switch (pending.problem) {
                case TOO_LARGE: return L10n.t(this,
                        "文件或解码后文本超过 8 MiB，请使用较小的配置。", "The file or decoded text exceeds 8 MiB. Use a smaller configuration.");
                case EMPTY: return L10n.t(this, "文件或粘贴内容为空。", "The document or pasted text is empty.");
                case ENCODING: return L10n.t(this,
                        "文本编码无效或不受支持。请使用 UTF-8，或带 BOM 的 UTF-16。",
                        "Invalid or unsupported encoding. Use UTF-8, or UTF-16 with a BOM.");
                case BINARY: return L10n.t(this,
                        "不支持二进制文件或压缩包，请选择未压缩的文本配置。",
                        "Binary files and archives are not supported. Choose an uncompressed text configuration.");
                case FORMAT: return L10n.t(this,
                        "无法识别配置格式。支持 Clash YAML、Surfboard 和 v2ray 链接列表。",
                        "Unrecognized format. Use Clash YAML, Surfboard or a v2ray link list.");
                case NO_NODES: return L10n.t(this,
                        "配置没有可识别节点或静态代理列表为空。", "No recognizable nodes, or the static proxy list is empty.");
                case MALFORMED: return L10n.t(this,
                        "配置内容不完整或静态代理列表格式有误，请检查后重试。",
                        "The configuration is incomplete or its static proxy list is malformed. Check it and retry.");
                default: break;
            }
        }
        return L10n.t(this, "无法读取文件。文件可能已移动、权限已失效或提供器暂不可用，请重新选择。",
                "Cannot read this file. It may have moved, permission may have expired or the provider may be unavailable. Choose it again.");
    }

    private void doRefresh(final Profile profile) {
        if (!REFRESHING.add(profile.id)) {
            Toast.makeText(this, L10n.t(this, "此订阅正在更新", "This subscription is already updating"), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, L10n.t(this, "正在更新…", "Updating…"), Toast.LENGTH_SHORT).show();
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    final int[] result = new int[1];
                    final String[] errorText = new String[1];
                    try {
                        Profile current = subs.store().byId(subs.list(), profile.id);
                        if (current == null) throw new IllegalStateException(L10n.t(SubsActivity.this, "配置已删除", "Profile was deleted"));
                        try { result[0] = subs.refresh(current); }
                        catch (Exception error) { errorText[0] = message(error); current.lastError = errorText[0]; }
                        subs.update(current);
                    } catch (Exception error) { errorText[0] = message(error); }
                    finally { REFRESHING.remove(profile.id); }
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            refresh();
                            if (errorText[0] != null) Toast.makeText(SubsActivity.this,
                                    L10n.t(SubsActivity.this, "更新失败：", "Update failed: ") + L10n.message(SubsActivity.this, errorText[0]),
                                    Toast.LENGTH_LONG).show();
                            else Toast.makeText(SubsActivity.this, result[0] < 0
                                    ? L10n.t(SubsActivity.this, "已更新（Clash 配置）", "Updated (Clash configuration)")
                                    : L10n.t(SubsActivity.this, "已更新 " + result[0] + " 个节点", "Updated " + result[0] + " nodes"),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }, "sub-refresh").start();
        } catch (RuntimeException error) {
            REFRESHING.remove(profile.id);
            showError(L10n.t(this, "更新失败", "Update failed"), error);
        }
    }

    /** No sharing entry point this iteration. Ignore untrusted payloads, even for internal intents. */
    private void handleImportIntent() {
        Intent intent = getIntent();
        if (intent == null) return;
        boolean payload = intent.hasExtra(Intent.EXTRA_TEXT) || intent.hasExtra(Intent.EXTRA_STREAM)
                || intent.getData() != null || intent.getClipData() != null;
        intent.removeExtra(Intent.EXTRA_TEXT);
        intent.removeExtra(Intent.EXTRA_STREAM);
        intent.setData(null);
        intent.setClipData(null);
        if (payload) Toast.makeText(this, L10n.t(this,
                "请使用「导入文件」或「粘贴导入」并确认后保存；不会自动导入传入内容。",
                "Use Import file or Paste import, then confirm to save. Incoming content is not imported automatically."),
                Toast.LENGTH_LONG).show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleImportIntent();
    }

    private String ago(long time) {
        long delta = Math.max(0, System.currentTimeMillis() - time);
        if (delta < 60_000) return L10n.t(this, "刚刚", "just now");
        if (delta < 3_600_000) return L10n.t(this, (delta / 60_000) + " 分钟前", (delta / 60_000) + " min ago");
        if (delta < 86_400_000) return L10n.t(this, (delta / 3_600_000) + " 小时前", (delta / 3_600_000) + " hr ago");
        return L10n.t(this, (delta / 86_400_000) + " 天前", (delta / 86_400_000) + " days ago");
    }
}
