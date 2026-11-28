package io.ghx.vpn;

import android.app.Application;

import com.github.kr328.clash.core.bridge.Bridge;

import io.ghx.vpn.core.Log;
import io.ghx.vpn.core.SelfTest;

public class VpnApp extends Application {

    public static final String TAG = "LitLink";

    @Override
    public void onCreate() {
        // Deliberately uncaught: a different signer must terminate at startup, before JNI or networking.
        io.ghx.vpn.core.SignatureGuard.requireTrusted(this);
        super.onCreate();
        Log.init(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Log.e("未捕获异常 @" + t.getName(), e);
                android.util.Log.e(TAG, "uncaught in " + t, e);
                if (previous != null) previous.uncaughtException(t, e);
                else android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        Log.i("轻连 启动 v2.1 sdk=" + android.os.Build.VERSION.SDK_INT
                + " abi=" + android.os.Build.SUPPORTED_ABIS[0]);
        Bridge.attach(this);
        // 不从外部文件隐式执行调试任务或网络测试。
    }

    /** 内核数据目录（geo 缓存、provider 日志都在这里）。 */
    public static java.io.File coreHome(android.content.Context ctx) {
        java.io.File f = new java.io.File(ctx.getFilesDir(), "core");
        if (!f.exists()) f.mkdirs();
        return f;
    }
}
