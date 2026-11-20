package com.github.kr328.clash.core.bridge;

public interface FetchCallback {
    void report(String message);

    void complete(String message);
}
