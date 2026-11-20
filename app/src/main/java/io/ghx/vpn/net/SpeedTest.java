package io.ghx.vpn.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded HTTP payload sampling, not a promise of link speed. All source attempts share one
 * monotonic time/body-byte budget; there is no unmetered preflight download. Rates include
 * connection setup and failed attempts. Only validated, identity-encoded 200/206 bodies count.
 *
 * maxBytes limits bytes read from response bodies, including rejected bodies. HTTP/TLS headers,
 * retransmission and OS/HTTP-client read-ahead are not measurable or capped by this API. At the
 * deadline no further bytes are measured. Closing sockets and joining workers can take up to
 * CLEANUP_MS beyond that deadline; a broken transport that outlives this is reported, not hidden.
 */
public final class SpeedTest {
    public double mbpsAvg;
    public double mbpsPeak; // Observed progress windows (nominally 500ms), not an extrapolated peak.
    public long bytes;      // Validated payload delivered before stop/deadline.
    public long costMs;     // Monotonic measurement duration, excludes shutdown.
    public String error;
    public String source;   // All hosts which contributed validated bytes.
    public int streams;     // Maximum download worker count started for a source.
    public long bytesTransferred; // All body bytes read, including rejected or late data.
    public boolean cancelled;
    public boolean timeLimitReached;
    public boolean byteLimitReached;
    public String warning; // Failed/partial source attempts; never silently discarded.

    public interface Progress {
        void onProgress(double mbpsNow, double mbpsAvg, long bytes, long costMs);
    }

    /** One-shot token. cancel() is safe on the UI thread and never waits for network I/O. */
    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }

    private static final String[] SOURCES = {
            "https://proof.ovh.net/files/100Mb.dat",
            "https://cachefly.cachefly.net/100mb.test",
            "https://speed.cloudflare.com/__down?bytes=524288000",
            "http://speedtest.tele2.net/100MB.zip"
    };
    private static final int STREAMS = 4;
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int SNIFF_SIZE = 1024;
    private static final long MIN_SAMPLE = 32 * 1024;
    private static final long WINDOW_NS = 500_000_000L;
    private static final int POLL_MS = 25;
    private static final int IO_TIMEOUT_MS = 1200;
    private static final int CLEANUP_MS = 2000;
    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)", Pattern.CASE_INSENSITIVE);

    // Package-private seams: fake HTTP connections and a monotonic clock need no Android runtime.
    interface Clock { long nanoTime(); }
    interface ConnectionFactory {
        HttpURLConnection open(String url, int proxyPort, int timeoutMs) throws IOException;
    }
    private static final Clock CLOCK = new Clock() {
        @Override public long nanoTime() { return System.nanoTime(); }
    };
    private static final ConnectionFactory CONNECTIONS = new ConnectionFactory() {
        @Override public HttpURLConnection open(String url, int proxyPort, int timeoutMs)
                throws IOException { return Net.open(url, proxyPort, timeoutMs); }
    };

    public static SpeedTest run(int proxyPort, int maxMs, Progress cb) {
        return run(proxyPort, maxMs, 300L * 1024 * 1024, cb);
    }
    public static SpeedTest run(int proxyPort, int maxMs, long maxBytes, Progress cb) {
        return run(proxyPort, maxMs, maxBytes, cb, new Cancellation());
    }
    public static SpeedTest run(int proxyPort, int maxMs, long maxBytes, Progress cb,
                                Cancellation cancellation) {
        return run(proxyPort, maxMs, maxBytes, cb, cancellation,
                SOURCES, CONNECTIONS, CLOCK, STREAMS);
    }

    static SpeedTest run(int proxyPort, int maxMs, long maxBytes, Progress cb,
                         Cancellation cancellation, String[] sources,
                         ConnectionFactory connections, Clock clock, int streamCount) {
        SpeedTest result = new SpeedTest();
        if (maxMs <= 0 || maxBytes <= 0 || proxyPort < 0 || proxyPort > 65535
                || sources == null || sources.length == 0 || connections == null || clock == null
                || streamCount <= 0 || streamCount > STREAMS) {
            result.error = "无效的测速参数";
            return result;
        }
        Cancellation token = cancellation == null ? new Cancellation() : cancellation;
        State state = new State(maxMs, maxBytes, token, clock);
        List<Worker> workers = new ArrayList<>();
        Set<String> hosts = new LinkedHashSet<>();
        List<String> issues = new ArrayList<>();
        long windowAt = state.start;
        long windowBytes = 0;
        long end = state.start;
        double peak = 0;
        String fatal = null;
        boolean interrupted = false;
        int openedStreams = 0;
        boolean cleanupOk;
        try {
            for (int sourceIndex = 0; sourceIndex < sources.length && state.running(); sourceIndex++) {
                String url = sources[sourceIndex];
                try {
                    URL parsed = new URL(url);
                    if (!"http".equalsIgnoreCase(parsed.getProtocol())
                            && !"https".equalsIgnoreCase(parsed.getProtocol())) {
                        throw new IOException("不支持的 URL 协议");
                    }
                } catch (Exception e) {
                    issues.add("测速源: " + message(e));
                    continue;
                }
                // Keep time for later sources, even when a server drips bytes without timing out.
                long remaining = state.remainingNanos();
                int left = sources.length - sourceIndex;
                long attemptBudget = left == 1 ? remaining
                        : Math.max(1, Math.min(remaining, Math.max(250_000_000L, remaining / left)));
                Attempt attempt = new Attempt(url, state.clock.nanoTime() + attemptBudget);
                List<Worker> current = new ArrayList<>();
                // Tiny budgets do not fan out into four sub-sample downloads.
                int count = state.remainingBytes() < MIN_SAMPLE * streamCount ? 1 : streamCount;
                long rangeEnd = state.maxBytes - 1;
                for (int i = 0; i < count && state.running(); i++) {
                    Worker worker = new Worker(state, attempt, connections, proxyPort, rangeEnd, i);
                    current.add(worker);
                    workers.add(worker);
                    worker.thread.start();
                }
                openedStreams = Math.max(openedStreams, current.size());
                while (state.running() && attempt.running(state.clock) && anyAlive(current)) {
                    long total = state.measured();
                    long now = state.clock.nanoTime();
                    if (now - state.start >= state.duration || token.isCancelled()) break;
                    long window = now - windowAt;
                    if (window >= WINDOW_NS) {
                        double instant = rate(total - windowBytes, window);
                        peak = Math.max(peak, instant);
                        if (cb != null) cb.onProgress(instant, rate(total, now - state.start),
                                total, millis(now - state.start));
                        windowAt = now;
                        windowBytes = total;
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    Thread.sleep(POLL_MS);
                }
                if (!state.running()) break;
                if (anyAlive(current)) {
                    attempt.stop();
                    attempt.addIssue("分配给该源的时间已用完");
                    for (Worker worker : current) worker.abort();
                    // Never start another batch while a previous worker is still reading.
                    if (!joinWorkers(current, TimeUnit.MILLISECONDS.toNanos(250))) {
                        fatal = "下载连接未及时停止，已停止后续测速";
                        break;
                    }
                }
                if (attempt.measured() > 0) hosts.add(shortHost(url));
                String issue = attempt.issue();
                if (issue != null) issues.add(shortHost(url) + ": " + issue);
                if (attempt.measured() == 0 && issue == null) {
                    issues.add(shortHost(url) + ": 没有有效下载数据");
                }
                // EOF, rejection or actual data failure all allow another source, within budget.
            }
        } catch (InterruptedException e) {
            interrupted = true;
            token.cancel();
        } catch (RuntimeException e) {
            fatal = "测速中断: " + message(e); // Includes a failing client callback.
        } finally {
            end = state.stop(); // Freezes accounting BEFORE disconnect/close/join.
            for (Worker worker : workers) worker.abort();
            if (Thread.interrupted()) interrupted = true;
            cleanupOk = joinWorkers(workers, TimeUnit.MILLISECONDS.toNanos(CLEANUP_MS));
            if (Thread.currentThread().isInterrupted()) interrupted = true;
            if (interrupted) Thread.currentThread().interrupt();
        }
        // Include the final attempt too (it may have ended on a global limit/cancellation).
        Set<Attempt> attempts = new LinkedHashSet<>();
        for (Worker worker : workers) attempts.add(worker.attempt);
        for (Attempt attempt : attempts) {
            if (attempt.measured() > 0) hosts.add(shortHost(attempt.url));
            String issue = attempt.issue();
            String text = shortHost(attempt.url) + ": " + issue;
            if (issue != null && !issues.contains(text)) issues.add(text);
        }
        long duration = Math.min(state.duration, Math.max(0, end - state.start));
        result.bytes = state.measured();
        result.bytesTransferred = state.transferred();
        result.costMs = millis(duration);
        result.mbpsAvg = rate(result.bytes, duration);
        long tailBytes = result.bytes - windowBytes;
        if (tailBytes > 0) peak = Math.max(peak, rate(tailBytes, duration - (windowAt - state.start)));
        result.mbpsPeak = peak;
        result.streams = openedStreams;
        result.source = join(hosts, ", ");
        result.cancelled = token.isCancelled() || interrupted;
        result.timeLimitReached = duration >= state.duration;
        result.byteLimitReached = result.bytesTransferred >= maxBytes;
        result.warning = issues.isEmpty() ? null : join(issues, " / ");
        if (!cleanupOk) {
            result.error = "下载连接未能在关闭期限内退出";
        } else if (result.cancelled) {
            result.error = "测速已取消";
        } else if (fatal != null) {
            result.error = fatal;
        } else if (result.bytes < Math.min(MIN_SAMPLE, maxBytes)) {
            result.error = "有效下载数据不足" + (result.warning == null ? "" : ": " + result.warning);
        } else if (!result.timeLimitReached && !result.byteLimitReached && result.warning != null) {
            result.error = "下载未完成: " + result.warning;
        }
        return result;
    }

    private static final class State {
        final long start;
        final long duration;
        final long maxBytes;
        final Cancellation cancellation;
        final Clock clock;
        private long consumed;
        private long reserved;
        private long valid;
        private boolean stopped;

        State(int maxMs, long maxBytes, Cancellation cancellation, Clock clock) {
            this.clock = clock;
            this.start = clock.nanoTime();
            this.duration = TimeUnit.MILLISECONDS.toNanos(maxMs);
            this.maxBytes = maxBytes;
            this.cancellation = cancellation;
        }
        synchronized boolean running() { return accepting() && consumed < maxBytes; }
        private boolean accepting() {
            return !stopped && !cancellation.isCancelled() && clock.nanoTime() - start < duration;
        }
        synchronized long remainingNanos() {
            return Math.max(0, duration - (clock.nanoTime() - start));
        }
        synchronized long remainingBytes() { return maxBytes - consumed - reserved; }
        synchronized int reserve(int wanted) {
            if (!running()) return 0;
            int amount = (int) Math.min(wanted, maxBytes - consumed - reserved);
            reserved += amount;
            return amount;
        }
        // Release, charge and commit together: the controller must not freeze the sample between
        // consuming the last reserved bytes and crediting their validated payload.
        synchronized boolean received(int reservation, int read, long measured, Attempt attempt) {
            reserved -= reservation;
            if (read > 0) consumed += read;
            if (!accepting() || !attempt.running(clock)) return false;
            valid += measured;
            attempt.addMeasured(measured);
            return true;
        }
        synchronized long measured() { return valid; }
        synchronized long transferred() { return consumed; }
        synchronized long stop() { stopped = true; return clock.nanoTime(); }
    }

    private static final class Attempt {
        final String url;
        final long deadline;
        private volatile boolean stopped;
        private long valid;
        private final Set<String> issues = new LinkedHashSet<>();
        Attempt(String url, long deadline) { this.url = url; this.deadline = deadline; }
        boolean running(Clock clock) { return !stopped && clock.nanoTime() - deadline < 0; }
        void stop() { stopped = true; }
        synchronized void addMeasured(long n) { valid += n; }
        synchronized long measured() { return valid; }
        synchronized void addIssue(String issue) { issues.add(issue); }
        synchronized String issue() { return issues.isEmpty() ? null : join(issues, "; "); }
    }

    private static final class Worker implements Runnable {
        final State state;
        final Attempt attempt;
        final ConnectionFactory connections;
        final int proxyPort;
        final long rangeEnd;
        final Thread thread;
        private final AtomicBoolean abortStarted = new AtomicBoolean();
        private volatile Thread closer;
        private volatile HttpURLConnection connection;
        private volatile InputStream input;

        Worker(State state, Attempt attempt, ConnectionFactory connections, int proxyPort,
               long rangeEnd, int index) {
            this.state = state;
            this.attempt = attempt;
            this.connections = connections;
            this.proxyPort = proxyPort;
            this.rangeEnd = rangeEnd;
            thread = new Thread(this, "speed-download-" + index);
            thread.setDaemon(true);
        }
        boolean running() {
            return !Thread.currentThread().isInterrupted() && state.running()
                    && attempt.running(state.clock);
        }
        int timeout() throws IOException {
            long remaining = Math.min(state.remainingNanos(), attempt.deadline - state.clock.nanoTime());
            if (!running() || remaining <= 0) throw new IOException("已到测速期限");
            return (int) Math.max(1, Math.min(IO_TIMEOUT_MS, (remaining + 999_999) / 1_000_000));
        }
        @Override public void run() {
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                if (!running()) return;
                c = connections.open(attempt.url, proxyPort, timeout());
                connection = c;
                if (!running() || abortStarted.get()) return;
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setRequestProperty("Cache-Control", "no-cache, no-store");
                c.setRequestProperty("Pragma", "no-cache");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Range", "bytes=0-" + rangeEnd);
                c.setConnectTimeout(timeout());
                c.setReadTimeout(timeout());
                int code = c.getResponseCode();
                if (!running()) return;
                long expected = validateHeaders(c, code, rangeEnd);
                in = c.getInputStream();
                input = in;
                if (in == null) throw new IOException("空响应体");
                if (!running() || abortStarted.get()) return;
                byte[] buffer = new byte[BUFFER_SIZE];
                byte[] prefix = new byte[SNIFF_SIZE];
                int prefixSize = 0;
                long pending = 0;
                long bodyBytes = 0;
                boolean validated = false;
                while (running()) {
                    int wanted = buffer.length;
                    if (expected >= 0) {
                        if (bodyBytes == expected) {
                            if (bodyBytes == 0) throw new IOException("空响应体");
                            break;
                        }
                        wanted = (int) Math.min(wanted, expected - bodyBytes);
                    }
                    int reservation = state.reserve(wanted);
                    if (reservation == 0) break;
                    int n = -1;
                    long measured = 0;
                    boolean accepted;
                    try {
                        c.setReadTimeout(timeout());
                        if (!running()) break;
                        n = in.read(buffer, 0, reservation);
                        if (n > reservation || n < -1) throw new IOException("无效的响应读取长度");
                        if (n == -1) {
                            if (expected >= 0 && bodyBytes < expected) {
                                throw new IOException("响应体提前结束 (" + bodyBytes + "/" + expected + ")");
                            }
                            if (bodyBytes == 0) throw new IOException("空响应体");
                            if (!validated && pending > 0) {
                                validatePrefix(prefix, prefixSize);
                                measured = pending;
                            }
                        } else {
                            if (n == 0) throw new IOException("响应体没有读取进展");
                            bodyBytes += n;
                            if (!validated) {
                                int copy = Math.min(n, prefix.length - prefixSize);
                                System.arraycopy(buffer, 0, prefix, prefixSize, copy);
                                prefixSize += copy;
                                pending += n;
                                if (prefixSize == prefix.length || (expected >= 0 && bodyBytes == expected)
                                        || (state.remainingBytes() == 0 && n == reservation)) {
                                    validatePrefix(prefix, prefixSize);
                                    validated = true;
                                    measured = pending;
                                    pending = 0;
                                }
                            } else {
                                measured = n;
                            }
                        }
                    } finally {
                        accepted = state.received(reservation, Math.max(0, n), measured, attempt);
                    }
                    if (n == -1 || !accepted) break;
                }
            } catch (IOException | RuntimeException e) {
                if (!state.cancellation.isCancelled() && !abortStarted.get()
                        && attempt.running(state.clock) && state.remainingNanos() > 0) {
                    attempt.addIssue(message(e));
                }
            } finally {
                try {
                    close(in);
                } finally {
                    disconnect(c);
                    input = null;
                    connection = null;
                }
            }
        }
        void abort() {
            if (!thread.isAlive() || !abortStarted.compareAndSet(false, true)) return;
            thread.interrupt();
            final HttpURLConnection c = connection;
            final InputStream in = input;
            // disconnect may itself wait for a read. Join both threads with a common deadline.
            Thread t = new Thread(new Runnable() {
                @Override public void run() { disconnect(c); close(in); }
            }, "speed-close");
            t.setDaemon(true);
            closer = t;
            t.start();
        }
    }

    private static long validateHeaders(HttpURLConnection c, int code, long requestedEnd)
            throws IOException {
        if (code != 200 && code != 206) throw new IOException("HTTP " + code);
        String encoding = c.getHeaderField("Content-Encoding");
        if (encoding != null && !encoding.trim().isEmpty()
                && !"identity".equalsIgnoreCase(encoding.trim())) {
            throw new IOException("非 identity 编码: " + encoding);
        }
        String type = c.getContentType();
        if (type != null) {
            type = type.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            if (type.startsWith("text/") || type.contains("html") || type.contains("json")
                    || type.contains("xml") || type.startsWith("multipart/")) {
                throw new IOException("非下载内容: " + type);
            }
        }
        long length = -1;
        String contentLength = c.getHeaderField("Content-Length");
        if (contentLength != null) {
            try {
                length = Long.parseLong(contentLength.trim());
                if (length < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) { throw new IOException("无效 Content-Length"); }
        }
        if (code == 200) return length;
        String value = c.getHeaderField("Content-Range");
        Matcher match = CONTENT_RANGE.matcher(value == null ? "" : value.trim());
        if (!match.matches()) throw new IOException("无效 Content-Range");
        try {
            long first = Long.parseLong(match.group(1));
            long last = Long.parseLong(match.group(2));
            if (first != 0 || last < first || last > requestedEnd || last == Long.MAX_VALUE) {
                throw new NumberFormatException();
            }
            if (!"*".equals(match.group(3)) && Long.parseLong(match.group(3)) <= last) {
                throw new NumberFormatException();
            }
            long rangeLength = last - first + 1;
            if (length >= 0 && length != rangeLength) throw new NumberFormatException();
            return rangeLength;
        } catch (NumberFormatException e) {
            throw new IOException("Content-Range 与请求或长度不匹配");
        }
    }

    private static void validatePrefix(byte[] prefix, int size) throws IOException {
        // Reject recognizable HTML/XML/JSON even when a portal labels it octet-stream. This
        // is a defensive sniff, not proof of origin; printable download data is otherwise valid.
        int start = size >= 3 && (prefix[0] & 255) == 239 && (prefix[1] & 255) == 187
                && (prefix[2] & 255) == 191 ? 3 : 0;
        while (start < size && (prefix[start] == ' ' || prefix[start] == '\t'
                || prefix[start] == '\r' || prefix[start] == '\n')) start++;
        StringBuilder text = new StringBuilder();
        boolean printable = true;
        for (int i = start; i < size; i++) {
            int b = prefix[i] & 255;
            if ((b < 32 && b != 9 && b != 10 && b != 13) || b >= 127) printable = false;
            text.append((char) b);
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        if (lower.startsWith("<!doctype") || lower.startsWith("<html")
                || lower.startsWith("<?xml") || lower.contains("<head") || lower.contains("<body")
                || (printable && (lower.startsWith("<") || lower.startsWith("{")
                || lower.startsWith("[")))) {
            throw new IOException("响应内容不是二进制测速数据");
        }
    }

    private static boolean anyAlive(List<Worker> workers) {
        for (Worker worker : workers) if (worker.thread.isAlive()) return true;
        return false;
    }
    private static boolean joinWorkers(List<Worker> workers, long budgetNanos) {
        long until = System.nanoTime() + budgetNanos;
        boolean complete = true;
        for (Worker worker : workers) {
            if (!joinThread(worker.thread, until)) complete = false;
            if (!joinThread(worker.closer, until)) complete = false;
        }
        return complete;
    }
    private static boolean joinThread(Thread thread, long deadline) {
        if (thread == null) return true;
        try {
            while (thread.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    private static void close(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (IOException | RuntimeException ignored) { }
        }
    }
    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
        }
    }
    private static double rate(long bytes, long nanos) {
        return bytes <= 0 || nanos <= 0 ? 0 : bytes * 8000.0 / nanos;
    }
    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos));
    }
    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
    private static String join(Iterable<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }
    private static String shortHost(String url) {
        try { return new URL(url).getHost(); }
        catch (Exception ignored) { return url; }
    }
}
