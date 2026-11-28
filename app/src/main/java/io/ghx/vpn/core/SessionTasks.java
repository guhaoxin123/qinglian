package io.ghx.vpn.core;
import java.util.concurrent.locks.ReentrantLock;
/** Scheduling guards only: never initialize a core or start a service. */
public final class SessionTasks {
 private SessionTasks() { }
 public interface State { boolean running(); }
 public interface Task { void run() throws Exception; }
 public static boolean admitReload(boolean running, boolean starting) { return running || starting; }
 /** Check after waiting for the startup/stop transaction, not before waiting. */
 public static boolean runWhenRunning(ReentrantLock lock, State state, Task task) throws Exception {
  lock.lockInterruptibly();
  try { if (!state.running()) return false; task.run(); return true; }
  finally { lock.unlock(); }
 }
}
