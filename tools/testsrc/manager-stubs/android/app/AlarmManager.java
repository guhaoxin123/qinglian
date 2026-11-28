package android.app;
public class AlarmManager {
 public static final int RTC_WAKEUP=0;
 public volatile long trigger; public volatile int sets,cancels;
 public volatile boolean fail;
 public java.util.concurrent.CountDownLatch cancelling,release;
 public void cancel(PendingIntent p){ if(fail)throw new SecurityException("synthetic");
  if(cancelling!=null){cancelling.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("cancel barrier timed out");}catch(InterruptedException e){throw new AssertionError(e);}}
  trigger=0;cancels++; }
 public void setAndAllowWhileIdle(int t,long at,PendingIntent p){if(fail)throw new SecurityException("synthetic");trigger=at;sets++;}
}