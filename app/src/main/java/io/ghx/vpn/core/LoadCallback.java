package io.ghx.vpn.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kotlinx.coroutines.CompletableDeferred;

/** nativeLoad 的回调载体：内核完成后会调 complete / completeExceptionally。 */
public final class LoadCallback extends CompletableDeferred {

    private final CountDownLatch latch;
    private final String[] err;

    public LoadCallback(CountDownLatch latch, String[] err) {
        this.latch = latch;
        this.err = err;
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return latch.await(timeout, unit);
    }

    @Override
    public boolean complete(Object v) {
        boolean r = super.complete(v);
        latch.countDown();
        return r;
    }

    @Override
    public boolean completeExceptionally(Throwable t) {
        if (err != null && err.length > 0) {
            err[0] = t == null ? "未知错误" : (t.getMessage() == null ? t.toString() : t.getMessage());
        }
        boolean r = super.completeExceptionally(t);
        latch.countDown();
        return r;
    }
}
