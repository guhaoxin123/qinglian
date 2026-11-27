import io.ghx.vpn.core.Profile;
import io.ghx.vpn.sub.B64;
import io.ghx.vpn.sub.SubParser;

/** Exercises the real load-time reconciliation method on synthetic legacy profiles only. */
public final class AuditProfileFormat {
    private static int checks;
    private static void check(String name, boolean ok) {
        checks++; if (!ok) throw new AssertionError(name);
    }
    private static void profile(String content, int expected, int count) {
        for (int old : new int[] {0, 1, 2, 3, 4, 99}) {
            Profile p = new Profile("fixture-id", "Unchanged name", "https://example.invalid/ql.txt", old);
            p.content = content; p.updatedAt = 123456789L; p.autoUpdateMinutes = 180;
            p.enabled = false; p.lastError = "old error";
            p.reconcileFormat();
            check("legacy " + old + " type corrected", p.type == expected);
            check("source object remains untouched", p.content == content);
            check("URL preserved, never used as format evidence", "https://example.invalid/ql.txt".equals(p.url));
            check("id preserved", "fixture-id".equals(p.id));
            check("name preserved", "Unchanged name".equals(p.name));
            check("successful-update timestamp preserved", p.updatedAt == 123456789L);
            check("interval preserved", p.autoUpdateMinutes == 180);
            check("disabled status preserved", !p.enabled);
            check("error preserved", "old error".equals(p.lastError));
            check("nodes still available", p.nodeEstimate() == count);
            check("Clash behaviour agrees", p.isClash() == (expected == Profile.TYPE_CLASH));
            p.reconcileFormat();
            check("idempotent", p.type == expected && p.content == content);
        }
    }
    public static void main(String[] args) {
        String first = "trojan://test@a.example.invalid:443#A";
        String second = "ss://" + B64.encodeUtf8("aes-128-gcm:test") + "@b.example.invalid:8388#B";
        for (String separator : new String[] {"\n", "\r", " ", ",", ";", "|", "\\n", ""}) {
            String uris = first + separator + second;
            profile(uris, Profile.TYPE_URI, 2);
            profile(B64.encodeUtf8(uris), Profile.TYPE_URI, 2);
        }
        profile(B64.encodeUtf8(B64.encodeUtf8(first + " " + second)), Profile.TYPE_URI, 2);
        profile("ql://" + first + " " + second, Profile.TYPE_QL, 2);
        profile("ql:" + B64.encodeUtf8(first + "\n" + second), Profile.TYPE_QL, 2);
        profile(B64.encodeUtf8("ql://" + first), Profile.TYPE_QL, 1);
        String clash = "proxies:\n  - {name: example, type: ss, server: a.example.invalid, port: 443, cipher: aes-128-gcm, password: test}";
        profile(clash, Profile.TYPE_CLASH, -1);
        profile(B64.encodeUtf8(clash), Profile.TYPE_CLASH, -1);
        profile("[Proxy]\nA = trojan, a.example.invalid, 443, password=test", Profile.TYPE_SURFBOARD, 1);
        profile("", Profile.TYPE_UNKNOWN, 0);
        profile(" \n\ufeff ", Profile.TYPE_UNKNOWN, 0);
        profile(null, Profile.TYPE_UNKNOWN, 0);
        profile("not a subscription", Profile.TYPE_UNKNOWN, 0);
        profile("ql://not a node", Profile.TYPE_UNKNOWN, 0);
        System.out.println("AuditProfileFormat: " + checks + " checks passed; synthetic legacy data only");
    }
}
