package io.ghx.vpn.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure-Java statistics contracts. No JNI initialization, core loading or VPN start. */
public final class TrafficStats {
    private TrafficStats() { }

    // bridge_helper.c: high 2 bits are the unit, low 30 bits the value.
    // Unit 0 is raw bytes; units 1..3 are hundredths of KiB/MiB/GiB.
    public static double bytes(long word) {
        int unit = (int) ((word >>> 30) & 3);
        long value = word & 0x3fffffffL;
        if (unit == 0) return value;
        return value * (double) (1L << (unit * 10)) / 100.0;
    }
    public static double upload(long packed) { return bytes(packed >>> 32); }
    public static double download(long packed) { return bytes(packed & 0xffffffffL); }

    public static final class Sample {
        public final double upSpeed, downSpeed, upTotal, downTotal;
        public Sample(long now, long total) {
            upSpeed = upload(now); downSpeed = download(now);
            upTotal = upload(total); downTotal = download(total);
        }
    }

    /** Never permits a remote controller or an unprotected endpoint. */
    public static final class Endpoint {
        public final String address;
        private final String secret;
        public Endpoint(int port, String secret) {
            if (port < 1 || port > 65535 || secret == null || !secret.matches("[0-9a-f]{32,64}"))
                throw new IllegalArgumentException("Invalid statistics endpoint");
            address = "127.0.0.1:" + port;
            this.secret = secret;
        }
        public String authorization() { return "Bearer " + secret; }
        public Map<String, Object> overrides(Map<String, Object> previous) {
            Map<String, Object> result = new LinkedHashMap<String, Object>(previous);
            result.put("external-controller", address);
            result.put("secret", secret);
            result.put("external-controller-tls", "");
            result.put("external-controller-unix", "");
            result.put("external-controller-pipe", "");
            result.put("external-doh-server", "");
            Map<String, Object> cors = new LinkedHashMap<String, Object>();
            cors.put("allow-origins", new java.util.ArrayList<String>());
            cors.put("allow-private-network", Boolean.FALSE);
            result.put("external-controller-cors", cors);
            return result;
        }
    }

    public static int connections(Map<String, Object> json) throws IOException {
        if (!json.containsKey("connections")) throw new IOException("Missing connections");
        Object value = json.get("connections");
        if (value == null) return 0;
        if (!(value instanceof List)) throw new IOException("Invalid connections");
        return ((List<?>) value).size();
    }

    public interface Factory { HttpURLConnection open(URL url, Proxy proxy) throws IOException; }
    public interface Clock { long millis(); }

    /** Cancellable bounded loopback read; one Reader per visible panel. */
    public static final class Reader {
        private final Factory factory;
        private final Clock clock;
        private HttpURLConnection active;
        private long generation;
        public Reader() {
            this(new Factory() {
                public HttpURLConnection open(URL u, Proxy p) throws IOException {
                    return (HttpURLConnection) u.openConnection(p);
                }
            }, new Clock() { public long millis() { return System.nanoTime() / 1000000L; } });
        }
        public Reader(Factory factory, Clock clock) { this.factory = factory; this.clock = clock; }
        public synchronized void cancel() {
            generation++;
            if (active != null) active.disconnect();
        }
        public synchronized long ticket() { return generation; }
        private synchronized void check(long ticket) throws IOException {
            if (ticket != generation || Thread.currentThread().isInterrupted())
                throw new IOException("Cancelled statistics read");
        }
        public String read(Endpoint endpoint, long ticket) throws IOException {
            if (endpoint == null) throw new IOException("Controller not ready");
            check(ticket);
            HttpURLConnection c = factory.open(new URL("http://" + endpoint.address + "/connections"), Proxy.NO_PROXY);
            try {
                synchronized (this) { check(ticket); active = c; }
                long deadline = clock.millis() + 2500;
                c.setConnectTimeout(1200); c.setReadTimeout(1200);
                c.setInstanceFollowRedirects(false); c.setUseCaches(false);
                c.setRequestProperty("Authorization", endpoint.authorization());
                c.setRequestProperty("Accept", "application/json");
                if (c.getResponseCode() != 200) throw new IOException("Controller HTTP error");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        check(ticket);
                        if (out.size() + n > 4 * 1024 * 1024 || clock.millis() > deadline)
                            throw new IOException("Statistics response limit");
                        out.write(buffer, 0, n);
                    }
                }
                check(ticket);
                if (clock.millis() > deadline) throw new IOException("Statistics deadline");
                return out.toString("UTF-8");
            } finally {
                c.disconnect();
                synchronized (this) { if (active == c) active = null; }
            }
        }
    }
}
