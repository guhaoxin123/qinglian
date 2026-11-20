package io.ghx.vpn.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 配置列表持久化：files/profiles.json */
public final class ProfileStore {

    /** Shared by every store/manager instance; hold across a complete read-modify-write. */
    static final Object LOCK = new Object();

    private final File file;

    public ProfileStore(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "profiles.json");
    }

    public File file() {
        return file;
    }

    public List<Profile> load() {
        synchronized (LOCK) {
            List<Profile> out = new ArrayList<Profile>();
            if (!file.exists()) return out;
            try {
                String text = readFile(file);
                JSONArray arr = new JSONArray(text);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Profile p = new Profile();
                    p.id = o.optString("id");
                    p.name = o.optString("name");
                    p.url = o.optString("url", "");
                    p.content = o.optString("content", "");
                    p.type = o.optInt("type", Profile.TYPE_CLASH);
                    p.updatedAt = o.optLong("updatedAt", 0L);
                    p.autoUpdateHours = o.optInt("autoUpdateHours", 12);
                    p.enabled = o.optBoolean("enabled", true);
                    p.lastError = o.optString("lastError", null);
                    if (p.id == null || p.id.length() == 0) {
                        throw new JSONException("配置缺少 id");
                    }
                    out.add(p);
                }
                return out;
            } catch (IOException e) {
                throw new IllegalStateException("读取配置失败，原文件未修改", e);
            } catch (JSONException e) {
                // Never present a damaged/partially parsed store as an empty writable list.
                throw new IllegalStateException("配置文件损坏，原文件未修改", e);
            }
        }
    }

    /** Use LOCK around load + save when mutating an existing list. */
    public void save(List<Profile> list) {
        synchronized (LOCK) {
            JSONArray arr = new JSONArray();
            try {
                for (int i = 0; i < list.size(); i++) {
                    Profile p = list.get(i);
                    if (p.id == null || p.id.length() == 0) {
                        throw new JSONException("配置缺少 id");
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", p.id);
                    o.put("name", p.name);
                    o.put("url", p.url == null ? "" : p.url);
                    o.put("content", p.content == null ? "" : p.content);
                    o.put("type", p.type);
                    o.put("updatedAt", p.updatedAt);
                    o.put("autoUpdateHours", p.autoUpdateHours);
                    o.put("enabled", p.enabled);
                    if (p.lastError != null) o.put("lastError", p.lastError);
                    arr.put(o);
                }
                writeFile(file, arr.toString());
            } catch (JSONException e) {
                throw new IllegalStateException("保存配置失败，原文件未修改", e);
            } catch (IOException e) {
                throw new IllegalStateException("保存配置失败，原文件未修改", e);
            }
        }
    }

    public Profile byId(List<Profile> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) return list.get(i);
        }
        return null;
    }

    public static String readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** Write beside the target, fsync, then atomically replace it on Android's filesystem. */
    public static void writeFile(File f, String text) throws IOException {
        File target = f.getAbsoluteFile();
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建配置目录");
        }
        File tmp = File.createTempFile("profile-", ".tmp", dir);
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(text.getBytes("UTF-8"));
                out.getFD().sync();
            } finally {
                out.close();
            }
            // Do not delete the old target first: a failed write/rename must leave it intact.
            if (!tmp.renameTo(target)) throw new IOException("无法替换配置文件");
        } finally {
            if (tmp.exists()) tmp.delete(); // Only this operation's private temporary file.
        }
    }
}
