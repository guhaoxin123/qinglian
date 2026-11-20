package com.github.kr328.clash.core.bridge;

/** 内核回调：给内核新建的 socket 打标记 / 查询 uid。由服务实现。 */
public interface TunInterface {
    void markSocket(int fd);

    int querySocketUid(int protocol, String source, String target);
}
