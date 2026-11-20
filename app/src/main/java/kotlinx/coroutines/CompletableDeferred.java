package kotlinx.coroutines;

/**
 * 本工程不使用 Kotlin。内核通过 JNI 的 GetMethodID 查找
 * complete(Object)Z / completeExceptionally(Throwable)Z，因此这里提供一个
 * 同名同签名的纯 Java 实现即可完成回调，无需引入 kotlin 运行时与协程库。
 */
public class CompletableDeferred {

    public interface OnComplete {
        void onComplete(Object value, Throwable error);
    }

    private final Object lock = new Object();
    private boolean completed;
    private Object value;
    private Throwable error;
    private OnComplete listener;

    public CompletableDeferred() {}

    public CompletableDeferred(OnComplete listener) {
        this.listener = listener;
    }

    public void setOnComplete(OnComplete l) {
        boolean fire = false;
        Object v = null;
        Throwable e = null;
        synchronized (lock) {
            this.listener = l;
            if (completed) {
                fire = true;
                v = value;
                e = error;
            }
        }
        if (fire && l != null) l.onComplete(v, e);
    }

    public boolean complete(Object v) {
        OnComplete l;
        synchronized (lock) {
            if (completed) return false;
            completed = true;
            value = v;
            l = listener;
        }
        if (l != null) l.onComplete(v, null);
        return true;
    }

    public boolean completeExceptionally(Throwable t) {
        OnComplete l;
        synchronized (lock) {
            if (completed) return false;
            completed = true;
            error = t;
            l = listener;
        }
        if (l != null) l.onComplete(null, t);
        return true;
    }

    public boolean isCompleted() {
        synchronized (lock) {
            return completed;
        }
    }

    public Object getCompleted() {
        synchronized (lock) {
            return error != null ? error : value;
        }
    }
}
