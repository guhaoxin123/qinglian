package io.ghx.vpn.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.security.MessageDigest;

/** Certificate pin is public, never a private signing key. Fail closed before core initialization. */
public final class SignatureGuard {
    public static final String CERT_SHA256 = "0a9a6adfe1f9d72ea7acb819dbfb84723c427b10ab3128f7fff8517388eec951";
    private SignatureGuard() { }
    private static int flags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }
    public static String fingerprint(PackageInfo info) throws Exception {
        if (info == null) throw new SecurityException("无法读取签名");
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) throw new SecurityException("签名缺失");
            // Check current signers, not historical certificates in a rotation lineage.
            signatures = info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        if (signatures == null || signatures.length != 1) throw new SecurityException("签名数量异常");
        return UpdatePolicy.hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }
    public static void requireTrusted(Context context) {
        try {
            PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(), flags());
            if (!"io.ghx.vpn".equals(own.packageName) || !CERT_SHA256.equals(fingerprint(own)))
                throw new SecurityException("签名不匹配");
        } catch (Exception error) {
            throw new SecurityException("轻连签名校验失败", error);
        }
    }
    public static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
    public static void verifyUpdate(Context ctx, File file, UpdatePolicy.Release release) throws Exception {
        requireTrusted(ctx);
        PackageInfo candidate = ctx.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), flags());
        PackageInfo current = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        if (candidate == null || !ctx.getPackageName().equals(candidate.packageName)) throw new SecurityException("安装包包名不匹配");
        if (!CERT_SHA256.equals(fingerprint(candidate))) throw new SecurityException("安装包签名不匹配");
        if (code(candidate) <= code(current) || UpdatePolicy.compare(candidate.versionName, current.versionName) <= 0)
            throw new SecurityException("拒绝旧版或相同版本");
        if (UpdatePolicy.compare(candidate.versionName, release.version) != 0)
            throw new SecurityException("安装包版本与发行版不一致");
    }
}
