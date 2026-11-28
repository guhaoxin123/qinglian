package io.ghx.vpn.net;
public class Downloader {
 public interface Fetcher {String get(String url)throws java.io.IOException;}
 public static volatile Fetcher fetcher; public static int calls;
 public static class Result {public String body;public Result(String b){body=b;}}
 public static Result fetch(String url)throws java.io.IOException{calls++;if(fetcher==null)throw new AssertionError("unexpected network");return new Result(fetcher.get(url));}
}