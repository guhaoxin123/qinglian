package android.content;
public class Context {
 public static final String ALARM_SERVICE="alarm";
 public final android.app.AlarmManager alarm=new android.app.AlarmManager();
 public Context getApplicationContext(){return this;}
 public Object getSystemService(String name){return alarm;}
 public java.io.File getFilesDir(){return new java.io.File(System.getProperty("java.io.tmpdir"),"synthetic-unused");}
 public java.io.File getExternalFilesDir(String type){return getFilesDir();}
}
