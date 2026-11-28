package io.ghx.vpn.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Only the current app process, a bounded snapshot, no READ_LOGS / root / shell privileges. */
public final class LogReader {
    private volatile Process process;
    private volatile boolean cancelled;
    public void cancel() { cancelled = true; Process p = process; if (p != null) p.destroy(); }
    public String read(int pid) throws Exception {
        if (pid <= 0 || cancelled) throw new IllegalStateException("操作已取消");
        Process p = new ProcessBuilder("logcat", "-d", "-b", "main", "-b", "crash", "-t", "400", "-v", "threadtime", "--pid=" + pid)
                .redirectErrorStream(true).start();
        process = p;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        timer.schedule(new Runnable() { @Override public void run() { p.destroy(); } }, 4, TimeUnit.SECONDS);
        try {
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line; int lines = 0;
                while (!cancelled && (line = reader.readLine()) != null && lines++ < 450 && text.length() < 192 * 1024)
                    text.append(redact(line)).append('\n');
            }
            if (cancelled) throw new IllegalStateException("操作已取消");
            if (text.length() == 0) return "暂无可读日志；部分系统可能限制 logcat。\nNo readable logs; this device may restrict logcat.";
            return text.toString();
        } finally { p.destroy(); process = null; timer.shutdownNow(); }
    }
    /** Best effort, not a guarantee. The UI always warns before sharing. */
    public static String redact(String text) {
        return text.replaceAll("(?i)(?:https?|vmess|vless|trojan|ssr?|hy2|hysteria2|tuic|socks5?|ql)://[^\\s\\\"<>]+", "[链接已隐藏]")
                // Match a whole quoted value (including escapes), or the entire auth scheme + credential.
                // End-of-line also closes a truncated quoted value: never expose its remaining words.
                .replaceAll("(?i)([\"']?(?:password|passwd|secret|token|authorization|uuid)[\"']?[ \\t]*[:=][ \\t]*)"
                        + "(?:\"(?:\\\\[^\\r\\n]|[^\"\\\\\\r\\n])*+(?:\"|\\\\?(?=\\r|\\n|$))"
                        + "|'(?:\\\\[^\\r\\n]|[^'\\\\\\r\\n])*+(?:'|\\\\?(?=\\r|\\n|$))"
                        + "|(?:Bearer|Basic)[ \\t]+[^\\s,}]+|[^\\s,}]+)", "$1[已隐藏]");
    }
}
