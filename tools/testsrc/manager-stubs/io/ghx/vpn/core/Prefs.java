package io.ghx.vpn.core; public class Prefs {
 public static int minutes=15; public Prefs(android.content.Context c){}
 public int autoUpdateMinutes(){return minutes;} public void lastSubCheck(long t){}
 public String activeProfile(){return "";}public void activeProfile(String id){throw new AssertionError("selection changed");}
 public String mode(){return "global";}public int mixedPort(){return 7890;}public boolean allowLan(){return false;}public boolean tun(){return true;}
}