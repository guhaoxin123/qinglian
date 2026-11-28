import io.ghx.vpn.core.LogReader;
import io.ghx.vpn.core.UpdateCache;
import io.ghx.vpn.core.UpdatePolicy;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;

/** Synthetic fixtures only: no Android, real logs, network, signing keys, installer or VPN. */
public final class AuditUpdateEdges {
    private static int checks;
    private interface Test { void run() throws Exception; }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void reject(Test test) throws Exception {
        boolean rejected = false;
        try { test.run(); } catch (Exception expected) { rejected = true; }
        check(rejected, "must reject unsafe or invalid input");
    }
    private static UpdatePolicy.Release release(String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return new UpdatePolicy.Release("v2.2", "qinglian_2.2.apk",
                UpdatePolicy.REPO + "/releases/download/v2.2/qinglian_2.2.apk",
                "sha256:" + UpdatePolicy.hex(MessageDigest.getInstance("SHA-256").digest(data)), data.length, "synthetic");
    }
    private static void publish(File dir, String text) throws Exception {
        UpdatePolicy.Release release = release(text);
        try (UpdateCache.Download slot = UpdateCache.reserve(dir, release)) {
            check(slot.partial != null, "new download has a private partial");
            Files.write(slot.partial.toPath(), text.getBytes(StandardCharsets.UTF_8));
            UpdateCache.verifyBytes(slot.partial, release); slot.publish();
            check(slot.file.isFile() && !slot.partial.exists(), "publish atomically renames only this partial");
        }
    }
    private static void redaction() {
        String[] cases = {
                "{\"password\":\"SYNTHETIC_A\",\"uuid\":\"SYNTHETIC_B\"}",
                "Authorization: Bearer SYNTHETIC_A", "authorization=Basic SYNTHETIC_A",
                "password=\"SYNTHETIC_A SYNTHETIC_B\"", "secret='SYNTHETIC_A SYNTHETIC_B'",
                "{\"token\":\"SYNTHETIC_A\\\" SYNTHETIC_B\"}",
                "password=\"SYNTHETIC_A SYNTHETIC_B", "password=\"SYNTHETIC_A SYNTHETIC_B\\", "'passwd': 'SYNTHETIC_A'",
                "token=SYNTHETIC_A", "access_token=SYNTHETIC_A", "UUID: SYNTHETIC_A", "https://example.invalid/SYNTHETIC_A",
                "trojan://SYNTHETIC_A@example.invalid:443"
        };
        for (String input : cases) check(!LogReader.redact(input).contains("SYNTHETIC_"), "mask " + input);
        String masked = LogReader.redact("{\"password\":\"SYNTHETIC_A\",\"status\":\"ok\"} status=ok");
        check(masked.contains("\"status\":\"ok\"") && masked.contains("status=ok"), "preserve adjacent safe fields");
        check(LogReader.redact("connection ready status=ok").equals("connection ready status=ok"), "safe text unchanged");
        String longValue = new String(new char[8192]).replace('\0', 'A') + " SYNTHETIC_END";
        check(!LogReader.redact("password=\"" + longValue + "\"").contains("SYNTHETIC_END"), "long quoted field without recursive backtracking");
        for (String newline : new String[]{"\n", "\r\n", "\r"}) {
            String broken = "password=\"SYNTHETIC_A SYNTHETIC_B\\" + newline + "status=ok";
            String result = LogReader.redact(broken);
            check(!result.contains("SYNTHETIC_") && result.endsWith(newline + "status=ok"), "truncated quote masks only its own line");
        }
    }
    private static void restoredRelease() throws Exception {
        UpdatePolicy.Release r = release("one");
        UpdatePolicy.Release restored = UpdateCache.restoreRelease(r.version, r.url, r.digest, r.size, r.notes);
        check(restored.url.equals(r.url) && restored.digest.equals(r.digest) && restored.size == r.size, "v-tag roundtrip");
        String noV = r.url.replace("/v2.2/", "/2.2/");
        check(UpdateCache.restoreRelease(r.version, noV, r.digest, r.size, "").url.equals(noV), "plain-tag roundtrip");
        reject(() -> UpdateCache.restoreRelease("v2.2", r.url, r.digest, r.size, ""));
        reject(() -> UpdateCache.restoreRelease(r.version, "https://evil.invalid/a.apk", r.digest, r.size, ""));
        reject(() -> UpdateCache.restoreRelease(r.version, r.url + "?x=1", r.digest, r.size, ""));
        reject(() -> UpdateCache.restoreRelease(r.version, r.url, "bad", r.size, ""));
        reject(() -> UpdateCache.restoreRelease(r.version, r.url, r.digest, 0, ""));
        reject(() -> UpdateCache.restoreRelease(r.version, r.url, r.digest, UpdatePolicy.MAX_APK + 1, ""));
        check(UpdateCache.basename(r).matches("qinglian-[0-9]{1,78}\\.apk"), "Provider-compatible deterministic basename");
        check(UpdateCache.basename(r).equals(UpdateCache.basename(restored)), "restored digest has identical basename");
    }
    private static void cache(Path root) throws Exception {
        File dir = UpdateCache.directory(root.toFile());
        for (String name : new String[]{"../qinglian-1.apk", "/tmp/qinglian-1.apk", "qinglian-1.apk/child",
                "qinglian-a.apk", "qinglian-1.apk.part", "qinglian-1.apk.part.apk", "qinglian-%2f.apk", "", "qinglian--1.apk"})
            reject(() -> UpdateCache.resolve(dir, name));
        reject(() -> UpdateCache.resolve(dir, null));
        Path outside = root.resolve("unrelated.txt"); Files.write(outside, new byte[]{9});
        Path link = dir.toPath().resolve("qinglian-0.apk"); Files.createSymbolicLink(link, outside);
        reject(() -> UpdateCache.resolve(dir, link.getFileName().toString()));
        check(UpdateCache.cleanup(dir, System.currentTimeMillis() + 2 * UpdateCache.SAFE_AGE_MS) == 0
                && Files.exists(outside) && Files.isSymbolicLink(link), "cleanup never follows a symlink");
        Files.delete(link); // This exact synthetic symlink, never its target.
        File unrelated = new File(dir, "notes.txt"); Files.write(unrelated.toPath(), new byte[]{7});
        check(unrelated.setLastModified(System.currentTimeMillis() - 2 * UpdateCache.SAFE_AGE_MS), "age synthetic unrelated file");
        UpdatePolicy.Release one = release("one"), two = release("two"), three = release("three"), four = release("four");
        publish(dir, "one"); File first = UpdateCache.resolve(dir, UpdateCache.basename(one));
        Files.write(first.toPath(), "bad".getBytes(StandardCharsets.UTF_8));
        File restoring = UpdateCache.retain(dir, first.getName());
        try (UpdateCache.Download slot = UpdateCache.reserve(dir, one)) {
            reject(() -> slot.reuseOrRepair(one));
            check(first.isFile() && slot.partial == null, "do not replace corrupt file held by another restore/UI");
        } finally { UpdateCache.release(restoring); }
        try (UpdateCache.Download slot = UpdateCache.reserve(dir, one)) {
            check(!slot.reuseOrRepair(one) && slot.partial != null && !first.exists(), "exclusively held corrupt digest cache becomes fresh partial");
            Files.write(slot.partial.toPath(), "one".getBytes(StandardCharsets.UTF_8));
            UpdateCache.verifyBytes(slot.partial, one); slot.publish(); slot.ready();
        }
        for (int i = 0; i < 4; i++) try (UpdateCache.Download slot = UpdateCache.reserve(dir, one)) {
            check(slot.partial == null && slot.file.equals(first) && slot.reuseOrRepair(one), "repeated download reuses same immutable file");
            UpdateCache.verifyBytes(slot.file, one);
        }
        reject(() -> UpdateCache.verifyBytes(first, release("bad"))); // Same length, wrong SHA-256.
        reject(() -> UpdateCache.verifyBytes(first, release("longer")));
        publish(dir, "two"); publish(dir, "three");
        reject(() -> { try (UpdateCache.Download ignored = UpdateCache.reserve(dir, four)) { } });
        check(first.exists() && unrelated.exists(), "full cache never evicts young/unrelated files");
        long now = System.currentTimeMillis(), old = now - 2 * UpdateCache.SAFE_AGE_MS;
        File second = UpdateCache.resolve(dir, UpdateCache.basename(two));
        File third = UpdateCache.resolve(dir, UpdateCache.basename(three));
        check(first.setLastModified(old) && second.setLastModified(old) && third.setLastModified(old), "age fixtures");
        File held = UpdateCache.retain(dir, first.getName()); UpdateCache.installing(second);
        try (UpdateCache.Download active = UpdateCache.reserve(dir, four)) {
            check(!third.exists() && first.exists() && second.exists(), "evict old orphan only; retain UI and installer files");
            check(active.partial.setLastModified(old), "age active partial");
            check(UpdateCache.cleanup(dir, now + 2 * UpdateCache.SAFE_AGE_MS) == 0, "protect active download and installer beyond age");
            reject(() -> { try (UpdateCache.Download ignored = UpdateCache.reserve(dir, four)) { } });
            UpdateCache.release(held);
            check(UpdateCache.cleanup(dir, now) == 1 && !first.exists(), "release permits only aged orphan removal");
            check(active.partial.exists() && unrelated.exists() && Files.exists(outside), "other fixtures intact");
        }
        check(!new File(dir, UpdateCache.basename(four) + ".part.apk").exists(), "failed/cancelled reservation removes own partial");
        check(second.exists(), "installer file survives page/reservation cleanup");
        Files.write(second.toPath(), "bad".getBytes(StandardCharsets.UTF_8));
        try (UpdateCache.Download slot = UpdateCache.reserve(dir, two)) {
            reject(() -> slot.reuseOrRepair(two));
            check(second.exists() && slot.partial == null, "even corrupt installer file is never invalidated");
        }
        File future = new File(dir, "qinglian-2.apk"); Files.write(future.toPath(), new byte[]{1});
        check(future.setLastModified(now + UpdateCache.SAFE_AGE_MS), "future timestamp fixture");
        check(UpdateCache.cleanup(dir, now) == 0 && future.exists(), "clock rollback cannot expire a file");
    }
    private static void forgetProcessInstallerState() throws Exception {
        // Simulate a fresh process, without touching the real on-disk protection being tested.
        java.lang.reflect.Field field = UpdateCache.class.getDeclaredField("INSTALLING"); field.setAccessible(true);
        ((java.util.Set<?>) field.get(null)).clear();
    }
    private static void persistentInstaller(Path root) throws Exception {
        File dir = UpdateCache.directory(root.resolve("persistent").toFile());
        UpdatePolicy.Release one = release("lease-one"); publish(dir, "lease-one");
        File apk = UpdateCache.resolve(dir, UpdateCache.basename(one));
        File lease = new File(dir, apk.getName() + ".lease");
        UpdateCache.installing(apk);
        check(lease.isFile() && Files.readAllBytes(lease.toPath()).length == 1, "installer persists only a fixed marker");
        forgetProcessInstallerState();
        check(lease.exists(), "process restart simulation preserves actual lease file");
        Files.write(apk.toPath(), "corrupted".getBytes(StandardCharsets.UTF_8));
        try (UpdateCache.Download slot = UpdateCache.reserve(dir, one)) {
            reject(() -> slot.reuseOrRepair(one));
            check(apk.exists() && lease.exists() && slot.partial == null, "persistent lease rejects corrupt repair after restart");
        }
        long now = System.currentTimeMillis(), old = now - 2 * UpdateCache.SAFE_AGE_MS;
        check(apk.setLastModified(old), "age leased APK only");
        check(UpdateCache.cleanup(dir, now) == 0 && apk.exists() && lease.exists(), "young lease protects even an aged APK");
        check(lease.setLastModified(old), "age persistent lease");
        File held = UpdateCache.retain(dir, apk.getName());
        check(UpdateCache.cleanup(dir, now) == 0 && lease.exists(), "held APK retains aged persistent lease");
        UpdateCache.release(held);
        check(UpdateCache.cleanup(dir, now) == 1 && !apk.exists() && !lease.exists(), "expired unheld APK removal also removes its own lease");

        UpdatePolicy.Release two = release("lease-two"); publish(dir, "lease-two");
        File second = UpdateCache.resolve(dir, UpdateCache.basename(two));
        File orphan = new File(dir, second.getName() + ".lease");
        UpdateCache.installing(second); forgetProcessInstallerState();
        Files.delete(second.toPath()); // Simulate OS eviction of this exact synthetic cached APK, not the lease.
        reject(() -> { try (UpdateCache.Download ignored = UpdateCache.reserve(dir, two)) { } });
        check(orphan.exists(), "young orphan lease still prevents replacement after restart");
        check(orphan.setLastModified(old), "age orphan lease");
        UpdateCache.cleanup(dir, now);
        check(!orphan.exists(), "aged unheld orphan lease does not accumulate forever");
        try (UpdateCache.Download slot = UpdateCache.reserve(dir, two)) { check(slot.partial != null, "expired orphan permits new explicit download"); }

        UpdatePolicy.Release three = release("lease-three"); publish(dir, "lease-three");
        File third = UpdateCache.resolve(dir, UpdateCache.basename(three));
        Path outside = root.resolve("lease-unrelated.txt"); Files.write(outside, new byte[]{7});
        Path malicious = new File(dir, third.getName() + ".lease").toPath(); Files.createSymbolicLink(malicious, outside);
        reject(() -> UpdateCache.installing(third));
        Files.write(third.toPath(), new byte[]{0});
        try (UpdateCache.Download slot = UpdateCache.reserve(dir, three)) { reject(() -> slot.reuseOrRepair(three)); }
        check(third.setLastModified(old), "age invalid-lease APK fixture");
        check(UpdateCache.cleanup(dir, now) == 0 && third.exists() && Files.isSymbolicLink(malicious)
                && Files.readAllBytes(outside)[0] == 7, "invalid lease path fails closed without touching APK/target");
    }
    public static void main(String[] args) throws Exception {
        redaction(); restoredRelease();
        Path root = Files.createTempDirectory("qinglian-update-edges-").toRealPath();
        try { cache(root); persistentInstaller(root); }
        finally {
            // Only this run's freshly allocated synthetic tree; Files.walk does not follow symlinks.
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("AuditUpdateEdges: " + checks + " checks passed; synthetic fixtures, no network/install/VPN");
    }
}
