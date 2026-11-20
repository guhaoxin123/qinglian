package com.github.kr328.clash.core.bridge;

/** 内核抛出的异常类型。 */
public class ClashException extends RuntimeException {
    private final int errno;

    public ClashException(int errno, String message, String reason) {
        super(message + (reason == null || reason.isEmpty() ? "" : ": " + reason));
        this.errno = errno;
    }

    public int getErrno() {
        return errno;
    }
}
