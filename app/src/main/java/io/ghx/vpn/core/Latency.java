package io.ghx.vpn.core;

import android.content.Context;

import com.github.kr328.clash.core.bridge.Bridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kotlinx.coroutines.CompletableDeferred;

/** Callback-driven proxy HTTP health checks. Never substitute a TCP handshake. */
public final class Latency {
    public static final int FAILED = -1;
    public static final int UNAVAILABLE = -2;
    private static final long CHECK_TIMEOUT_MS = 60000;
    private static final Object STATE = new Object();
    private static final Map<String, Integer> CACHE = new HashMap<String, Integer>();
    private static long epoch;
    private static Request active;
    // A timed-out/interrupted JNI check cannot be cancelled by this ABI. Do not overlap it.
    private static HealthCallback pending;

    private Latency() {}

    public static Integer get(String name) {
        synchronized (STATE) {
            return CACHE.get(name);
        }
    }

    /** Invalidate both cached values and work started for a previous configuration. */
    public static void clear() {
        synchronized (STATE) {
            ++epoch;
            CACHE.clear();
            if (active != null) active.cancel();
        }
    }

    /** Release the Java wait/CORE_LOCK promptly when the user disconnects; JNI may finish later. */
    public static void cancelActive() {
        synchronized (STATE) {
            if (active != null) active.cancel();
        }
    }

    /** One UI request; cancellation does not pretend to cancel native network work. */
    public static final class Request {
        private boolean cancelled;
        private boolean started;
        private boolean finished;
        private boolean ownsCache;
        private Thread waiter;
        private long epoch;
        private List<String> names;

        public void cancel() {
            synchronized (STATE) {
                cancelled = true;
                if (ownsCache && !finished && names != null && epoch == Latency.epoch) {
                    for (String name : names) CACHE.remove(name);
                }
                if (waiter != null && !finished && waiter != Thread.currentThread()) {
                    waiter.interrupt();
                }
            }
        }
    }

    public static final class BatchResult {
        public final int succeeded;
        public final int failed;
        public final int unavailable;
        public final boolean cancelled;
        public final String message;
        private final long epoch;

        private BatchResult(int succeeded, int failed, int unavailable, boolean cancelled,
                            String message, long epoch) {
            this.succeeded = succeeded;
            this.failed = failed;
            this.unavailable = unavailable;
            this.cancelled = cancelled;
            this.message = message;
            this.epoch = epoch;
        }

        /** A lifecycle/configuration change can invalidate a result before the UI sees it. */
        public boolean isCurrent() {
            synchronized (STATE) {
                return epoch == Latency.epoch;
            }
        }
    }

    /** Used by the session before reloading: JNI does not expose health-check cancellation. */
    public static boolean hasPendingCheck() {
        synchronized (STATE) {
            return pending != null && !pending.done();
        }
    }

    /**
     * Tests the running kernel's HTTP health-check group and publishes only requested members.
     * The caller must run this off the main thread. This method never loads a configuration,
     * starts VPN, or opens/closes listeners. Disconnected tests explicitly remain unavailable.
     * CORE_LOCK is shared with config/service lifecycle; a pending native check blocks reload.
     */
    public static BatchResult test(Context ctx, List<String> names, Request request) {
        if (request == null) throw new IllegalArgumentException("request");
        final List<String> target = uniqueNames(names);
        synchronized (STATE) {
            if (request.started) {
                return unavailable(target.size(), "此测试请求已使用，请重新测试", false);
            }
            request.started = true;
            request.names = target;
            request.epoch = epoch;
            if (request.cancelled || Thread.currentThread().isInterrupted()) {
                return unavailable(target.size(), "HTTP 测试已取消", true);
            }
            if (active != null || (pending != null && !pending.done())) {
                return unavailable(target.size(), "内核仍在处理上次 HTTP 测试，请稍后重试", false);
            }
            if (target.isEmpty()) return unavailable(0, "没有可测试的节点", false);
            active = request;
            request.ownsCache = true;
            request.waiter = Thread.currentThread();
            for (String name : target) CACHE.put(name, UNAVAILABLE);
        }

        boolean locked = false;
        try {
            locked = Session.CORE_LOCK.tryLock(2, TimeUnit.SECONDS);
            if (!locked) return finish(request, null, "内核正在切换配置，HTTP 测试未开始");
            if (!current(request)) return finish(request, null, "测试已取消或配置已变更");
            if (!Session.isKernelRunning() || !Session.isCoreReady()) {
                return finish(request, null, "请先连接后测代理延迟（未启动 VPN，也未改用 TCP）");
            }
            final String group = Session.autoGroup();
            final Map<String, Integer> before = parseGroup(Bridge.INSTANCE.nativeQueryGroup(group, "Delay"));
            boolean hasMember = false;
            for (String name : target) {
                if (before.containsKey(name)) { hasMember = true; break; }
            }
            if (!hasMember) return finish(request, null, "当前配置的 HTTP 测试组不含所选节点");

            final HealthCallback callback = new HealthCallback();
            synchronized (STATE) {
                if (!currentLocked(request)) return finish(request, null, "测试已取消或配置已变更");
                pending = callback;
            }
            // Verified JNI ABI: tc/src/main.c invokes complete(Unit) or
            // completeExceptionally(ClashException), not a delay payload.
            try {
                Bridge.INSTANCE.nativeHealthCheck(callback, group);
            } catch (UnsatisfiedLinkError e) {
                // Method dispatch failed before native work began; do not retain a dead guard.
                synchronized (STATE) {
                    if (pending == callback) pending = null;
                }
                throw e;
            }
            if (!callback.await(CHECK_TIMEOUT_MS)) {
                return finish(request, null, "内核 HTTP 测试尚未完成；未取得结果，不代表节点超时");
            }
            if (callback.error != null) {
                return finish(request, null, "内核无法完成 HTTP 测试；未取得节点结果");
            }
            if (!current(request)) return finish(request, null, "测试已取消或配置已变更");
            Map<String, Integer> after = parseGroup(Bridge.INSTANCE.nativeQueryGroup(group, "Delay"));
            Map<String, Integer> measured = new HashMap<String, Integer>();
            for (String name : target) {
                // Never import newly appearing members that this request did not test.
                Integer value = before.containsKey(name) ? after.get(name) : null;
                measured.put(name, value == null ? UNAVAILABLE : value);
            }
            return finish(request, measured, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.cancel();
            return finish(request, null, "HTTP 测试已取消");
        } catch (JSONException e) {
            return finish(request, null, "内核返回的 HTTP 测试格式无法识别，未使用历史或 TCP 数据");
        } catch (Exception e) {
            return finish(request, null, "HTTP 测试不可用：内核或配置未就绪");
        } catch (LinkageError e) {
            return finish(request, null, "HTTP 测试不可用：内核接口不兼容");
        } finally {
            if (locked) Session.CORE_LOCK.unlock();
            synchronized (STATE) {
                request.ownsCache = false;
                request.waiter = null;
                if (active == request) active = null;
            }
        }
    }

    private static List<String> uniqueNames(List<String> names) {
        LinkedHashMap<String, Boolean> unique = new LinkedHashMap<String, Boolean>();
        if (names != null) {
            for (String name : names) {
                if (name != null && name.length() > 0) unique.put(name, Boolean.TRUE);
            }
        }
        return new ArrayList<String>(unique.keySet());
    }

    private static boolean current(Request request) {
        synchronized (STATE) {
            return currentLocked(request);
        }
    }

    private static boolean currentLocked(Request request) {
        return !request.cancelled && request.epoch == epoch && !Thread.currentThread().isInterrupted();
    }

    private static BatchResult unavailable(int count, String message, boolean cancelled) {
        return new BatchResult(0, 0, count, cancelled, message, epoch);
    }

    private static BatchResult finish(Request request, Map<String, Integer> values, String reason) {
        synchronized (STATE) {
            request.finished = true;
            if (!currentLocked(request)) {
                return unavailable(request.names.size(), "测试已取消或配置已变更，结果已丢弃", true);
            }
            int success = 0;
            int failed = 0;
            int unknown = 0;
            for (String name : request.names) {
                Integer value = values == null ? null : values.get(name);
                if (value == null) value = UNAVAILABLE;
                CACHE.put(name, value);
                if (value > 0) ++success;
                else if (value == FAILED) ++failed;
                else ++unknown;
            }
            String summary = "HTTP：成功 " + success + "，失败 " + failed + "，未取得结果 " + unknown;
            if (reason == null && unknown > 0) {
                reason = "部分节点无可确认结果（缺失、异常或未识别的状态）；不能视为成功";
            }
            return new BatchResult(success, failed, unknown, false,
                    reason == null ? summary : reason + "\n" + summary, epoch);
        }
    }

    /**
     * CFA Bridge returns a ProxyGroup model, not the controller's history JSON.
     * Parse object boundaries/escaped names and require the flattened member delay field.
     * Missing, ambiguous, or unsupported members stay unavailable; never borrow another delay.
     */
    static Map<String, Integer> parseGroup(String json) throws JSONException {
        if (json == null || json.trim().length() == 0) throw new JSONException("Missing group");
        JSONTokener tokener = new JSONTokener(json);
        Object root = tokener.nextValue();
        if (!(root instanceof JSONObject) || tokener.nextClean() != 0) {
            throw new JSONException("Unsupported group document");
        }
        JSONObject group = (JSONObject) root;
        JSONArray proxies = group.getJSONArray("proxies");
        Map<String, Integer> values = new HashMap<String, Integer>();
        for (int i = 0; i < proxies.length(); ++i) {
            Object member = proxies.get(i);
            if (!(member instanceof JSONObject)) throw new JSONException("Unsupported proxy schema");
            JSONObject proxy = (JSONObject) member;
            Object rawName = proxy.opt("name");
            if (!(rawName instanceof String) || ((String) rawName).length() == 0) {
                throw new JSONException("Missing proxy name");
            }
            String name = (String) rawName;
            int delay = UNAVAILABLE;
            Object raw = proxy.opt("delay");
            if (raw instanceof Integer || raw instanceof Long) {
                long n = ((Number) raw).longValue();
                // Only accept positive bounded delays. The exact failure sentinels of the
                // bundled native build are not verified: 0/-1/65535 stay unavailable, not success.
                if (n > 0 && n < 65535) delay = (int) n;
            }
            // A duplicate/ambiguous name must not receive a value from either proxy.
            values.put(name, values.containsKey(name) ? UNAVAILABLE : delay);
        }
        return values;
    }

    private static final class HealthCallback extends CompletableDeferred {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Throwable error;

        @Override
        public synchronized boolean complete(Object value) {
            if (!super.complete(value)) return false;
            latch.countDown();
            return true;
        }

        @Override
        public synchronized boolean completeExceptionally(Throwable error) {
            if (isCompleted()) return false;
            this.error = error == null ? new IllegalStateException("Missing native error") : error;
            if (!super.completeExceptionally(this.error)) return false;
            latch.countDown();
            return true;
        }

        boolean await(long timeoutMs) throws InterruptedException {
            return latch.await(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        }

        boolean done() { return latch.getCount() == 0; }
    }
}
