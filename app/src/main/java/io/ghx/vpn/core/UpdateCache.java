package io.ghx.vpn.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Private update files only. Pure Java so paths, integrity and retention can be tested offline. */
public final class UpdateCache {
    public static final int MAX_FILES = 3;
    public static final long SAFE_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final Map<File, Integer> HELD = new HashMap<>();
    // In-process hold plus a private .lease sidecar: failed-byte repair must also survive process death.
    private static final Set<File> INSTALLING = new HashSet<>();
    private UpdateCache() { }

    public static File directory(File cache) throws IOException {
        File dir = new File(cache.getCanonicalFile(), "updates");
        if (!dir.equals(dir.getCanonicalFile())) throw new IOException("更新缓存路径无效");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建更新缓存");
        return dir;
    }
    public static String basename(UpdatePolicy.Release release) {
        // Decimal SHA-256 stays compatible with UpdateProvider's existing numeric APK names.
        return "qinglian-" + new BigInteger(release.digest, 16).toString(10) + ".apk";
    }
    public static File resolve(File directory, String name) throws IOException {
        if (name == null || !name.matches("qinglian-[0-9]{1,78}\\.apk"))
            throw new IOException("更新缓存文件名无效");
        return child(directory, name);
    }
    private static File child(File directory, String name) throws IOException {
        File dir = directory.getCanonicalFile(), file = new File(dir, name);
        if (!file.equals(file.getCanonicalFile()) || !dir.equals(file.getCanonicalFile().getParentFile()))
            throw new IOException("更新缓存路径无效");
        return file;
    }
    private static void hold(File file) { HELD.put(file, HELD.containsKey(file) ? HELD.get(file) + 1 : 1); }
    public static synchronized File retain(File directory, String name) throws IOException {
        File file = resolve(directory, name); hold(file); return file;
    }
    public static synchronized void release(File file) {
        Integer count = HELD.get(file);
        if (count == null) return;
        if (count == 1) HELD.remove(file); else HELD.put(file, count - 1);
    }
    private static File leaseFile(File file) throws IOException {
        File safe = resolve(file.getParentFile(), file.getName());
        if (!safe.equals(file)) throw new IOException("更新缓存路径无效");
        return child(file.getParentFile(), file.getName() + ".lease");
    }
    public static synchronized void installing(File file) throws IOException {
        File lease = leaseFile(file);
        if (!file.isFile()) throw new IOException("无法保留待安装文件");
        if (!lease.createNewFile() && !lease.isFile()) throw new IOException("无法保留安装租约");
        // A fixed marker, no paths/credentials/user data. Sync before ever opening Android's installer.
        try (FileOutputStream out = new FileOutputStream(lease)) { out.write(1); out.getFD().sync(); }
        long now = System.currentTimeMillis();
        if (!lease.setLastModified(now) || !file.setLastModified(now)) throw new IOException("无法保留待安装文件");
        INSTALLING.add(file);
    }
    private static boolean owned(String name) {
        return name.matches("qinglian-[0-9]{1,78}\\.apk(?:\\.part\\.apk)?");
    }
    /** Never evict a young file, a held download/UI file, a symlink, or an installer file. */
    public static synchronized int cleanup(File directory, long now) throws IOException {
        File[] files = directory.listFiles(); int removed = 0;
        if (files == null) throw new IOException("无法读取更新缓存");
        for (File f : files) {
            if (!owned(f.getName())) continue;
            File safe;
            try { safe = child(directory, f.getName()); } catch (IOException e) { continue; }
            long modified = safe.lastModified();
            if (!safe.isFile() || HELD.containsKey(safe) || INSTALLING.contains(safe)
                    || modified <= 0 || now < modified || now - modified < SAFE_AGE_MS) continue;
            File lease = null;
            if (safe.getName().matches("qinglian-[0-9]{1,78}\\.apk")) {
                try { lease = leaseFile(safe); } catch (IOException e) { continue; }
                if (lease.exists()) {
                    long leased = lease.lastModified();
                    if (!lease.isFile() || leased <= 0 || now < leased || now - leased < SAFE_AGE_MS) continue;
                }
            }
            if (safe.delete()) {
                removed++;
                // Never remove the persistent protection before the APK itself is successfully gone.
                if (lease != null && lease.exists() && !lease.delete()) throw new IOException("无法清理过期安装租约");
            }
        }
        // A crash/system cache eviction can leave only the marker. Expire only our aged, unheld orphan.
        for (File f : files) {
            if (!f.getName().matches("qinglian-[0-9]{1,78}\\.apk\\.lease")) continue;
            File apk, lease;
            try {
                apk = resolve(directory, f.getName().substring(0, f.getName().length() - 6));
                lease = leaseFile(apk);
            } catch (IOException e) { continue; }
            long modified = lease.lastModified();
            if (apk.exists() || HELD.containsKey(apk) || INSTALLING.contains(apk) || !lease.isFile()
                    || modified <= 0 || now < modified || now - modified < SAFE_AGE_MS) continue;
            if (!lease.delete()) throw new IOException("无法清理过期安装租约");
        }
        return removed;
    }
    public static synchronized Download reserve(File directory, UpdatePolicy.Release release) throws IOException {
        File file = resolve(directory, basename(release));
        // Reuse even an old matching download; its bytes/signature must still be checked by the caller.
        if (file.isFile()) { hold(file); return new Download(file, null); }
        cleanup(directory, System.currentTimeMillis());
        if (leaseFile(file).exists()) throw new IOException("更新仍有安装租约；未覆盖，请稍后重试");
        // PackageManager archive parsing requires an .apk suffix even for this unshared staging file.
        File partial = child(directory, file.getName() + ".part.apk");
        File[] files = directory.listFiles(); int count = 0;
        if (files == null) throw new IOException("无法读取更新缓存");
        for (File f : files) if (owned(f.getName())) count++;
        if (count >= MAX_FILES) throw new IOException("更新缓存已满；为保护近期下载或安装，请稍后重试");
        if (file.exists() || !partial.createNewFile()) throw new IOException("同一更新正在下载或等待清理，请稍后重试");
        hold(file); hold(partial); return new Download(file, partial);
    }
    public static final class Download implements AutoCloseable {
        public final File file;
        public File partial;
        private boolean closed;
        private Download(File file, File partial) { this.file = file; this.partial = partial; }
        /** Returns false with a fresh private partial only after a proven corrupt, exclusively held cache. */
        public boolean reuseOrRepair(UpdatePolicy.Release release) throws Exception {
            if (partial != null) return false;
            try { verifyBytes(file, release); return true; }
            catch (SecurityException invalidBytes) {
                synchronized (UpdateCache.class) {
                    if (closed || !file.getName().equals(basename(release)) || !file.equals(file.getCanonicalFile())
                            || !Integer.valueOf(1).equals(HELD.get(file)) || INSTALLING.contains(file) || leaseFile(file).exists())
                        throw new IOException("缓存已损坏，但正在使用；未覆盖，请关闭其他更新页面并稍后重试", invalidBytes);
                    File fresh = child(file.getParentFile(), file.getName() + ".part.apk");
                    // No other UI/restore/download lease may exist; caller holds exactly this reservation.
                    if (!fresh.createNewFile()) throw new IOException("缓存损坏且已有下载；未覆盖，请稍后重试", invalidBytes);
                    if (file.exists() && !file.delete()) { fresh.delete(); throw new IOException("无法移除损坏的更新缓存", invalidBytes); }
                    partial = fresh; hold(partial); return false;
                }
            }
        }
        /** A short unheld handoff to the UI remains protected by the retention age, even after process death. */
        public void ready() throws IOException {
            synchronized (UpdateCache.class) {
                if (closed || !file.isFile() || !file.equals(file.getCanonicalFile())
                        || !file.setLastModified(System.currentTimeMillis())) throw new IOException("无法保留已验证的更新");
            }
        }
        public void publish() throws IOException {
            synchronized (UpdateCache.class) {
                if (closed || partial == null || file.exists()
                        || !partial.equals(partial.getCanonicalFile()) || !file.equals(file.getCanonicalFile())
                        || !partial.renameTo(file)) throw new IOException("无法保存已验证的更新");
            }
        }
        @Override public void close() {
            synchronized (UpdateCache.class) {
                if (closed) return; closed = true;
                if (partial != null) {
                    // Only this reservation's own partial file, never a whole cache directory.
                    try { if (partial.equals(partial.getCanonicalFile())) partial.delete(); } catch (IOException ignored) { }
                    release(partial);
                }
                release(file);
            }
        }
    }
    public static void verifyBytes(File file, UpdatePolicy.Release release) throws Exception {
        if (!file.isFile() || !file.equals(file.getCanonicalFile()) || file.length() != release.size)
            throw new SecurityException("安装包大小校验失败");
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); long count = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
                count += n;
                if (count > release.size || count > UpdatePolicy.MAX_APK) throw new SecurityException("安装包大小校验失败");
                hash.update(buffer, 0, n);
            }
        }
        if (count != release.size || !release.digest.equals(UpdatePolicy.hex(hash.digest())))
            throw new SecurityException("安装包 SHA-256 / 大小校验失败");
    }
    /** Rebuild only a release accepted by the normal URL/name/digest/size policy. */
    public static UpdatePolicy.Release restoreRelease(String version, String url, String digest, long size, String notes) {
        if (!UpdatePolicy.normalize(version).equals(version)) throw new IllegalArgumentException("版本号无效");
        String asset = "qinglian_" + version + ".apk";
        String tag = (UpdatePolicy.REPO + "/releases/download/" + version + "/" + asset).equals(url) ? version : "v" + version;
        return new UpdatePolicy.Release(tag, asset, url, "sha256:" + digest, size, notes);
    }
}
