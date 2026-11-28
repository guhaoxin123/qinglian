package io.ghx.vpn.core;
import java.util.*;
/** In-memory disk boundary only: production manager and scheduler run unmodified. */
public class ProfileStore {
 static final Object LOCK=new Object();
 private static final Map<android.content.Context,List<Profile>> db=new IdentityHashMap<android.content.Context,List<Profile>>();
 private final android.content.Context ctx;
 public ProfileStore(android.content.Context c){ctx=c;}
 public List<Profile> load(){synchronized(LOCK){List<Profile> a=db.get(ctx);return cloneList(a==null?new ArrayList<Profile>():a);}}
 public void save(List<Profile> a){synchronized(LOCK){db.put(ctx,cloneList(a));}}
 public Profile byId(List<Profile> a,String id){for(Profile p:a)if(p.id.equals(id))return p;return null;}
 private static List<Profile> cloneList(List<Profile> a){List<Profile> b=new ArrayList<Profile>();for(Profile p:a){Profile q=new Profile(p.id,p.name,p.url,p.type);q.content=p.content;q.updatedAt=p.updatedAt;q.autoUpdateMinutes=p.autoUpdateMinutes;q.enabled=p.enabled;q.lastError=p.lastError;b.add(q);}return b;}
 public static String readFile(java.io.File f)throws java.io.IOException{throw new java.io.IOException("not used");}
 public static void writeFile(java.io.File f,String s)throws java.io.IOException{throw new AssertionError("unexpected file write");}
}