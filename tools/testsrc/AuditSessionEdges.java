import io.ghx.vpn.core.SessionTasks;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
public final class AuditSessionEdges {
 static int checks;
 static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 public static void main(String[] args)throws Exception {
  check(SessionTasks.admitReload(false,true),"starting admits pending mode change");
  check(SessionTasks.admitReload(true,false),"running admits mode change");
  check(!SessionTasks.admitReload(false,false),"offline mode cannot start core");
  for(int round=0;round<100;round++) {
   ReentrantLock lock=new ReentrantLock(true);AtomicBoolean running=new AtomicBoolean(false);AtomicReference<String> wanted=new AtomicReference<String>("A"),applied=new AtomicReference<String>("A");AtomicReference<Throwable> error=new AtomicReference<Throwable>();
   CountDownLatch begun=new CountDownLatch(1);lock.lock();
   Thread worker=new Thread(()->{begun.countDown();try{SessionTasks.runWhenRunning(lock,()->running.get(),()->applied.set(wanted.get()));}catch(Throwable e){error.set(e);}});worker.start();
   check(begun.await(2,TimeUnit.SECONDS),"queued while startup owns lock");wanted.set("B");running.set(true);lock.unlock();worker.join(2000);
   check(!worker.isAlive()&&error.get()==null,"startup task finishes");check("B".equals(applied.get()),"startup late choice/mode applied after running");
   running.set(false);check(!SessionTasks.runWhenRunning(lock,()->running.get(),()->{throw new AssertionError("offline task ran");}),"stopped task skipped");
   check(!lock.isLocked(),"skip unlocks");
  }
  ReentrantLock lock=new ReentrantLock();
  try{SessionTasks.runWhenRunning(lock,()->true,()->{throw new Exception("synthetic");});throw new AssertionError("exception lost");}catch(Exception expected){check(!lock.isLocked(),"exception unlocks");}
  Thread.currentThread().interrupt();try{SessionTasks.runWhenRunning(lock,()->true,()->{});throw new AssertionError("interrupt lost");}catch(InterruptedException expected){check(!lock.isLocked(),"interrupt never acquires lock");}finally{Thread.interrupted();}
  System.out.println("AuditSessionEdges: "+checks+" checks passed; real scheduling helper + latch, no native/VPN");
 }
}
