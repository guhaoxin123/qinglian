package io.ghx.vpn.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Plain JDK, synthetic connections only: this test never opens a socket. */
public final class AuditSpeedTest {
    private static int checks;
    private static void check(boolean ok, String text) {
        checks++;
        if (!ok) throw new AssertionError(text);
    }
    private static final class Clock implements SpeedTest.Clock {
        final AtomicLong now = new AtomicLong();
        @Override public long nanoTime() { return now.get(); }
    }
    private static final class Body extends InputStream {
        final Clock clock;
        long remaining = 1_000_000;
        long step = 1_000_000;
        long readBytes;
        long failAfter = Long.MAX_VALUE;
        byte[] text;
        boolean block;
        volatile boolean closed;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Body(Clock clock) { this.clock = clock; }
        @Override public int read() throws IOException { throw new IOException("bulk reads only"); }
        @Override public int read(byte[] dst, int offset, int length) throws IOException {
            entered.countDown();
            if (block) {
                clock.now.addAndGet(step);
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("fixture unblock timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            if (closed) throw new IOException("closed");
            if (readBytes >= failAfter) throw new IOException("synthetic data failure");
            if (remaining == 0) return -1;
            int n = (int) Math.min(length, remaining);
            for (int i = 0; i < n; i++) dst[offset + i] = text == null ? 0 : text[(int) ((readBytes + i) % text.length)];
            remaining -= n;
            readBytes += n;
            clock.now.addAndGet(step);
            return n;
        }
        @Override public void close() { closed = true; release.countDown(); }
    }
    private static final class Connection extends HttpURLConnection {
        final Body body;
        final Map<String, String> headers = new HashMap<>();
        int code = 200;
        volatile boolean disconnected;
        volatile boolean requested;
        Connection(String url, Clock clock) throws IOException {
            super(new URL(url));
            body = new Body(clock);
            headers.put("Content-Type", "application/octet-stream");
        }
        @Override public int getResponseCode() { requested = true; return code; }
        @Override public String getHeaderField(String key) { return headers.get(key); }
        @Override public String getContentType() { return headers.get("Content-Type"); }
        @Override public InputStream getInputStream() { return body; }
        @Override public void disconnect() { disconnected = true; body.release.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    private static class Factory implements SpeedTest.ConnectionFactory {
        final Clock clock;
        final List<Connection> made = new ArrayList<>();
        Factory(Clock clock) { this.clock = clock; }
        void configure(Connection c) { }
        @Override public synchronized HttpURLConnection open(String url, int port, int timeout) throws IOException {
            Connection c = new Connection(url, clock);
            configure(c);
            made.add(c);
            return c;
        }
    }
    private static SpeedTest run(Factory factory, long cap, int streams, String... urls) {
        return SpeedTest.run(0, 1000, cap, null, new SpeedTest.Cancellation(),
                urls.length == 0 ? new String[]{"https://one.invalid/file"} : urls,
                factory, factory.clock, streams);
    }
    private static void closed(Factory f) {
        for (Connection c : f.made) {
            check(c.disconnected, "connection disconnected");
            if (c.body.readBytes > 0 || c.body.block) check(c.body.closed, "opened body closed");
            if (c.requested) {
                check("identity".equals(c.getRequestProperty("Accept-Encoding")), "identity encoding");
                check(!c.getInstanceFollowRedirects() && !c.getUseCaches(), "redirect/cache disabled");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Factory f = new Factory(new Clock());
        SpeedTest r = run(f, 4096, 1);
        check(r.error == null && r.bytes == 4096 && r.bytesTransferred == 4096, "exact tiny cap");
        check(r.byteLimitReached && r.mbpsAvg > 0, "cap flag and measured rate");
        closed(f);
        for (int i = 0; i < 12; i++) {
            f = new Factory(new Clock());
            r = run(f, 200003, 4);
            check(r.error == null && r.bytes == 200003 && r.bytesTransferred == 200003,
                    "parallel cap and accounting race: " + r.bytes + "/" + r.bytesTransferred + " " + r.error);
            closed(f);
        }
        for (final int code : new int[]{302, 416, 500}) {
            f = new Factory(new Clock()) { @Override void configure(Connection c) { c.code = code; } };
            r = run(f, 65536, 1);
            check(r.error != null && r.bytes == 0 && r.warning.contains("HTTP " + code), "reject HTTP " + code);
            closed(f);
        }
        f = new Factory(new Clock()) { @Override void configure(Connection c) { c.headers.put("Content-Type", "text/html"); } };
        r = run(f, 65536, 1);
        check(r.error != null && r.bytesTransferred == 0, "HTML header rejected before reading");
        closed(f);
        f = new Factory(new Clock()) { @Override void configure(Connection c) {
            c.body.text = "<!doctype html><html>login required</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } };
        r = run(f, 65536, 1);
        check(r.error != null && r.bytes == 0 && r.bytesTransferred <= 65536, "mislabeled HTML rejected");
        closed(f);
        f = new Factory(new Clock()) { @Override void configure(Connection c) { c.headers.put("Content-Encoding", "gzip"); } };
        check(run(f, 65536, 1).error != null, "gzip rejected");
        closed(f);
        f = new Factory(new Clock()) { @Override void configure(Connection c) {
            c.code = 206; c.headers.put("Content-Range", "bytes 0-65535/1000000"); c.headers.put("Content-Length", "65536");
        } };
        r = run(f, 65536, 1);
        check(r.error == null && r.bytes == 65536, "valid 206 counted");
        closed(f);
        f = new Factory(new Clock()) { @Override void configure(Connection c) {
            c.code = 206; c.headers.put("Content-Range", "bytes 1-65535/1000000");
        } };
        check(run(f, 65536, 1).bytes == 0, "mismatched 206 rejected");
        closed(f);
        f = new Factory(new Clock()) { @Override void configure(Connection c) {
            if (c.getURL().getHost().equals("bad.invalid")) c.body.failAfter = 0;
        } };
        r = run(f, 65536, 1, "https://bad.invalid/file", "https://good.invalid/file");
        check(r.error == null && r.bytes == 65536 && r.source.equals("good.invalid"), "actual data failure falls back");
        check(r.warning != null && r.warning.contains("synthetic data failure"), "fallback retains failure warning");
        closed(f);
        f = new Factory(new Clock()) { @Override void configure(Connection c) { c.body.step = 2_000_000_000L; } };
        r = run(f, 65536, 1);
        check(r.bytes == 0 && r.bytesTransferred == 32768 && r.timeLimitReached && r.costMs == 1000,
                "late read charged but excluded from rate");
        closed(f);
        final Factory cancelFactory = new Factory(new Clock()) { @Override void configure(Connection c) { c.body.block = true; } };
        final SpeedTest.Cancellation cancellation = new SpeedTest.Cancellation();
        final SpeedTest[] cancelled = new SpeedTest[1];
        Thread caller = new Thread(new Runnable() { @Override public void run() {
            cancelled[0] = SpeedTest.run(0, 1000, 65536, null, cancellation,
                    new String[]{"https://one.invalid/file"}, cancelFactory, cancelFactory.clock, 1);
        } });
        caller.start();
        Connection blocked = waitConnection(cancelFactory);
        check(blocked.body.entered.await(2, TimeUnit.SECONDS), "cancel fixture reading");
        cancellation.cancel();
        caller.join(3000);
        check(!caller.isAlive() && cancelled[0].cancelled && cancelled[0].error != null, "manual cancellation joins");
        closed(cancelFactory);
        final Factory interruptFactory = new Factory(new Clock()) { @Override void configure(Connection c) { c.body.block = true; } };
        final boolean[] interruptRestored = new boolean[1];
        final SpeedTest[] interruptResult = new SpeedTest[1];
        Thread interruptedCaller = new Thread(new Runnable() { @Override public void run() {
            interruptResult[0] = SpeedTest.run(0, 1000, 65536, null, new SpeedTest.Cancellation(),
                    new String[]{"https://one.invalid/file"}, interruptFactory, interruptFactory.clock, 1);
            interruptRestored[0] = Thread.currentThread().isInterrupted();
        } });
        interruptedCaller.start();
        check(waitConnection(interruptFactory).body.entered.await(2, TimeUnit.SECONDS), "interrupt fixture reading");
        interruptedCaller.interrupt();
        interruptedCaller.join(3000);
        check(!interruptedCaller.isAlive() && interruptResult[0].cancelled && interruptRestored[0],
                "caller interruption cancels, joins, and restores interrupt");
        closed(interruptFactory);
        final Factory callbackFactory = new Factory(new Clock()) { @Override void configure(Connection c) {
            c.body.block = true; c.body.step = 600_000_000L;
        } };
        r = SpeedTest.run(0, 1000, 65536, new SpeedTest.Progress() {
            @Override public void onProgress(double now, double avg, long bytes, long cost) {
                throw new IllegalStateException("synthetic callback failure");
            }
        }, new SpeedTest.Cancellation(), new String[]{"https://one.invalid/file"}, callbackFactory, callbackFactory.clock, 1);
        check(r.error != null && r.error.contains("synthetic callback failure"), "callback failure reported");
        closed(callbackFactory);
        cancellation.cancel();
        f = new Factory(new Clock());
        r = SpeedTest.run(0, 1000, 4096, null, cancellation, new String[]{"https://one.invalid/file"}, f, f.clock, 1);
        check(r.cancelled && f.made.isEmpty(), "pre-cancel makes no connection");
        System.out.println("AuditSpeedTest: " + checks + " checks passed (no network)");
    }
    private static Connection waitConnection(Factory f) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) {
            synchronized (f) { if (!f.made.isEmpty()) return f.made.get(0); }
            Thread.sleep(1);
        }
        throw new AssertionError("fixture connection not created");
    }
}
