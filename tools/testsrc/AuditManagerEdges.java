import android.content.Context;
import io.ghx.vpn.core.*;
import io.ghx.vpn.net.Downloader;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class AuditManagerEdges {
 static int checks,failures;
 static final String OLD="trojan://OLD@old.example.invalid:443#old",NEW="trojan://NEW@new.example.invalid:8443#new";
 static void check(boolean ok,String name){checks++;if(!ok){failures++;System.out.println("FAIL "+name);}}
 static void rejected(Runnable r,String name){try{r.run();check(false,name);}catch(IllegalArgumentException expected){check(true,name);}}
 static void await(CountDownLatch l)throws Exception{if(!l.await(5,TimeUnit.SECONDS))throw new AssertionError("barrier timeout");}
 static void join(Thread t)throws Exception{t.join(6000);if(t.isAlive())throw new AssertionError("thread timeout");}
 public static void main(String[] args)throws Exception{
  Context c=new Context();SubscriptionManager sm=new SubscriptionManager(c);
  rejected(()->new SubscriptionManager(new Context()).addRaw("bad","proxies: [",15),"malformed addRaw refused");
  rejected(()->new SubscriptionManager(new Context()).addRemote("bad","https://feed.example.invalid","proxies: [",15),"malformed addRemote refused");
  Profile p=sm.addRemote("good","https://feed.example.invalid",OLD,720);
  check(c.alarm.trigger>System.currentTimeMillis()+719*60000L,"first import schedules alarm");
  check(Downloader.calls==0,"saving does not download");
  sm.setAutoUpdateMinutes(p.id,15);check(c.alarm.trigger>0&&c.alarm.trigger<System.currentTimeMillis()+16*60000L,"interval change reschedules");
  sm.setAutoUpdateMinutes(p.id,0);check(c.alarm.trigger==0,"disabling cancels");
  sm.setAutoUpdateMinutes(p.id,15);check(c.alarm.trigger>0,"reenabling schedules");
  p=sm.list().get(0);final long before=p.updatedAt;Downloader.fetcher=u->"proxies: [";
  boolean refused=false;try{sm.refresh(p);}catch(java.io.IOException expected){refused=true;p.lastError=expected.getMessage();}
  check(refused,"malformed refresh refused");check(OLD.equals(p.content)&&p.updatedAt==before,"failed refresh leaves snapshot intact");
  sm.update(p);Profile current=sm.list().get(0);check(OLD.equals(current.content)&&current.updatedAt==before,"last good cache survives malformed reply");
  check(current.lastError!=null,"refresh error recorded");
  Profile malicious=sm.list().get(0);malicious.content="proxies: [";malicious.updatedAt=before+1;malicious.lastError=null;sm.update(malicious);
  check(OLD.equals(sm.list().get(0).content),"commit independently validates malformed content");
  String provider="proxy-groups:\n  - name: providers\n    type: select\n    use: [sample]\nproxy-providers:\n  sample:\n    type: http\n    url: https://provider.example.invalid/nodes\n    path: ./provider.yaml\n    interval: 3600\n";
  Profile q=sm.addRemote("provider","https://provider.example.invalid/feed",provider,15);check(q.type==Profile.TYPE_CLASH,"provider-only config allowed");
  c.alarm.fail=true;sm.setAutoUpdateMinutes(q.id,30);check(sm.store().byId(sm.list(),q.id).autoUpdateMinutes==30,"alarm exception does not undo save");c.alarm.fail=false;
  sm.remove(q.id);sm.remove(p.id);check(c.alarm.trigger==0&&sm.list().isEmpty(),"last deletion cancels alarm");
  for(boolean oldFails:new boolean[]{false,true}){
   Context d=new Context();SubscriptionManager m=new SubscriptionManager(d);Profile start=m.addRemote("race","https://race.example.invalid",OLD,15);
   CountDownLatch began=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger order=new AtomicInteger();AtomicReference<Throwable> error=new AtomicReference<Throwable>();
   Downloader.fetcher=u->{if(order.incrementAndGet()==1){began.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}if(oldFails)throw new java.io.IOException("old failure");return OLD;}return NEW;};
   Profile a=m.list().get(0),b=m.list().get(0);
   Thread older=new Thread(()->{try{try{m.refresh(a);}catch(java.io.IOException e){a.lastError=e.getMessage();}m.update(a);}catch(Throwable e){error.set(e);}});older.start();await(began);
   m.refresh(b);m.update(b);release.countDown();join(older);if(error.get()!=null)throw new AssertionError(error.get());
   Profile end=m.list().get(0);check(NEW.equals(end.content),"old result cannot overwrite newer request oldFails="+oldFails);check(end.lastError==null,"old failure cannot overwrite new success");
   m.remove(start.id);
  }
  // Completion-time timestamps must not beat request order, including a newer failed request.
  {
   SubscriptionManager m=new SubscriptionManager(new Context());Profile start=m.addRemote("stale","https://stale.example.invalid",OLD,15);
   Profile older=m.list().get(0),newer=m.list().get(0);Downloader.fetcher=u->NEW;m.refresh(older);
   Downloader.fetcher=u->{throw new java.io.IOException("new failure");};
   try{m.refresh(newer);}catch(java.io.IOException expected){newer.lastError=expected.getMessage();}
   m.update(newer);older.updatedAt=System.currentTimeMillis()+60000;m.update(older);
   Profile end=m.list().get(0);check(OLD.equals(end.content)&&"new failure".equals(end.lastError),"newer failed request still invalidates older success");
   Downloader.fetcher=u->NEW;Profile waiting=m.list().get(0);m.refresh(waiting);
   Profile unticketed=m.list().get(0);unticketed.updatedAt=Long.MAX_VALUE;unticketed.content=OLD;unticketed.lastError=null;m.update(unticketed);
   check("new failure".equals(m.list().get(0).lastError),"unticketed snapshot cannot clear active request state");
   m.setAutoUpdateMinutes(start.id,60);m.convertToQl(start.id);m.update(waiting);end=m.list().get(0);
   check(end.autoUpdateMinutes==60,"refresh preserves latest interval");
   check(end.type==Profile.TYPE_QL&&end.content.contains(NEW),"refresh preserves latest explicit QL conversion");
   Profile changed=m.list().get(0);m.refresh(changed);
   java.util.List<Profile> all=m.list();all.get(0).url="https://changed.example.invalid";m.store().save(all);
   changed.lastError="stale error";m.update(changed);end=m.list().get(0);
   check(end.lastError==null&&end.url.endsWith("changed.example.invalid"),"changed URL rejects old response and error");
   Profile deleted=m.list().get(0);m.refresh(deleted);m.remove(start.id);m.update(deleted);
   check(m.list().isEmpty(),"deleted subscription never recreated by late refresh");
  }
  Context e=new Context();SubscriptionManager m=new SubscriptionManager(e);
  e.alarm.cancelling=new CountDownLatch(1);e.alarm.release=new CountDownLatch(1);CountDownLatch saved=new CountDownLatch(1);AtomicReference<Throwable> err=new AtomicReference<Throwable>();
  Thread stale=new Thread(()->AutoUpdate.schedule(e));stale.start();await(e.alarm.cancelling);
  Thread newer=new Thread(()->{try{m.addRemote("first","https://first.example.invalid",OLD,15);}catch(Throwable t){err.set(t);}finally{saved.countDown();}});newer.start();
  // On broken code this save races the old cancel; on fixed code it waits for the shared lock.
  saved.await(150,TimeUnit.MILLISECONDS);e.alarm.release.countDown();join(stale);join(newer);if(err.get()!=null)throw new AssertionError(err.get());
  check(e.alarm.trigger>0,"old empty schedule cannot cancel new subscription alarm");
  System.out.println("AuditManagerEdges: "+checks+" checks, "+failures+" failures; real manager/scheduler, fake storage/network/Android only");
  if(failures!=0)throw new AssertionError("manager regressions: "+failures);
 }
}
