import io.ghx.vpn.core.TrafficStats;
import java.io.*;
import java.net.*;
import java.util.*;

/** Synthetic counters and fake HTTP only: no sockets, JNI or VPN are executed. */
public final class AuditTrafficTest {
    static int checks;
    static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    static void near(double got, double expected, String name) {
        check(Math.abs(got-expected) <= Math.max(.001, expected*1e-12),name+": "+got+" != "+expected);
    }
    interface Work { void run() throws Exception; }
    static void fails(Work w, String name) throws Exception {
        boolean failed=false;try { w.run(); } catch(IOException | IllegalArgumentException e) { failed=true; }
        check(failed,name);
    }
    static long word(int unit, long value) { return ((long)unit<<30) | value; }
    static long pack(long up, long down) { return up<<32 | down; }
    // Mirrors bridge_helper.c, including its existing 1042 MiB switch threshold.
    static long nativeEncode(long value) {
        if(value>1042L*1024*1024)return word(3,value*100/1024/1024/1024);
        if(value>1024L*1024)return word(2,value*100/1024/1024);
        if(value>1024)return word(1,value*100/1024);
        return value;
    }
    static final class Fake extends HttpURLConnection {
        byte[] data="{\"connections\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int code=200; boolean disconnected; Runnable onRead;
        Fake() throws Exception { super(new URL("http://127.0.0.1:1234/connections")); }
        public void connect() { }
        public void disconnect() { disconnected=true; }
        public boolean usingProxy() { return false; }
        public int getResponseCode() { return code; }
        public InputStream getInputStream() { if(onRead!=null)onRead.run();return new ByteArrayInputStream(data); }
    }
    static final class Factory implements TrafficStats.Factory {
        final Fake fake;int opens;URL url;Proxy proxy;
        Factory(Fake fake){this.fake=fake;}
        public HttpURLConnection open(URL u,Proxy p){opens++;url=u;proxy=p;return fake;}
    }
    static final class Clock implements TrafficStats.Clock { long now;public long millis(){return now;} }
    public static void main(String[] args) throws Exception {
        near(TrafficStats.bytes(0),0,"zero");near(TrafficStats.bytes(1024),1024,"raw 1024 B is not divided by 100");
        near(TrafficStats.bytes(word(1,150)),1536,"hundredths of KiB");
        near(TrafficStats.bytes(word(2,125)),1310720,"hundredths of MiB");
        near(TrafficStats.bytes(word(3,250)),2684354560.0,"hundredths of GiB");
        long packed=pack(word(3,200),word(2,150));
        check(packed<0,"fixture exercises signed packed long");
        near(TrafficStats.upload(packed),2147483648.0,"unsigned upload half");
        near(TrafficStats.download(packed),1572864,"download half stays independent");
        for(long v:new long[]{0,1,1023,1024,1025,1048576,1048577,1073741824L,1092616192L,5L*1024*1024*1024,1024L*1024*1024*1024}){
            long encoded=nativeEncode(v);double decoded=TrafficStats.bytes(encoded);
            double quantum=((encoded>>>30)&3)==0?1:Math.pow(1024,(encoded>>>30)&3)/100;
            check(decoded<=v+.001 && v-decoded<quantum+.001,"native roundtrip "+v);
        }
        TrafficStats.Sample sample=new TrafficStats.Sample(pack(word(1,100),123),pack(word(3,150),word(2,100)));
        near(sample.upSpeed,1024,"speed comes directly from native now");
        near(sample.downSpeed,123,"small native speed");near(sample.upTotal,1610612736,"total independent from speed");
        String token="0123456789abcdef0123456789abcdef";
        final TrafficStats.Endpoint endpoint=new TrafficStats.Endpoint(45678,token);
        check(endpoint.address.equals("127.0.0.1:45678"),"loopback-only endpoint");
        check(endpoint.authorization().equals("Bearer "+token),"bearer auth");
        fails(()->new TrafficStats.Endpoint(0,token),"port zero not accepted as a resolved endpoint");
        fails(()->new TrafficStats.Endpoint(65536,token),"invalid port");
        fails(()->new TrafficStats.Endpoint(1,"x\r\nsecret"),"invalid secret rejected");
        Map<String,Object> previous=new LinkedHashMap<String,Object>();previous.put("mode","global");
        previous.put("external-controller","0.0.0.0:9090");previous.put("secret","old");
        Map<String,Object> override=endpoint.overrides(previous);
        check(override.get("mode").equals("global"),"unrelated override preserved");
        check(previous.get("secret").equals("old"),"previous map untouched");
        check(override.get("external-controller").equals(endpoint.address),"real control override");
        check(override.get("secret").equals(token),"controller secret matches request");
        for(String key:new String[]{"external-controller-tls","external-controller-unix","external-controller-pipe","external-doh-server"})
            check(override.get(key).equals(""),"no secondary listener "+key);
        check(((Map<?,?>)override.get("external-controller-cors")).get("allow-origins").equals(Collections.emptyList()),"no cross-origin controller access");
        final Map<String,Object> json=new LinkedHashMap<String,Object>();
        fails(()->TrafficStats.connections(json),"missing count not forged as zero");
        json.put("connections",null);check(TrafficStats.connections(json)==0,"null connections means none");
        json.put("connections",Arrays.asList(1,2,3));check(TrafficStats.connections(json)==3,"counts active array");
        json.put("connections","bad");fails(()->TrafficStats.connections(json),"invalid connections rejected");

        Fake good=new Fake();Factory f=new Factory(good);Clock clock=new Clock();
        TrafficStats.Reader reader=new TrafficStats.Reader(f,clock);
        check(reader.read(endpoint,reader.ticket()).contains("connections"),"loopback response read");
        check(f.proxy==Proxy.NO_PROXY,"system proxy bypassed");
        check(f.url.toString().equals("http://127.0.0.1:45678/connections"),"exact local route");
        check(good.getRequestProperty("Authorization").equals(endpoint.authorization()),"auth attached");
        check(good.getConnectTimeout()==1200&&good.getReadTimeout()==1200,"bounded socket waits");
        check(!good.getInstanceFollowRedirects(),"redirects cannot leak token");check(good.disconnected,"success cleans request");
        long old=reader.ticket();reader.cancel();int opens=f.opens;
        fails(()->reader.read(endpoint,old),"stale lifecycle ticket rejected");check(f.opens==opens,"cancelled ticket never opens request");
        check(reader.read(endpoint,reader.ticket()).contains("connections"),"resume uses fresh ticket");
        fails(()->reader.read(null,reader.ticket()),"unconfigured endpoint rejected");
        for(int code:new int[]{301,401,403,500}){
            Fake fake=new Fake();fake.code=code;TrafficStats.Reader r=new TrafficStats.Reader(new Factory(fake),new Clock());
            fails(()->r.read(endpoint,r.ticket()),"reject HTTP "+code);check(fake.disconnected,"HTTP failure cleanup "+code);
        }
        Fake big=new Fake();big.data=new byte[4*1024*1024+1];TrafficStats.Reader limited=new TrafficStats.Reader(new Factory(big),new Clock());
        fails(()->limited.read(endpoint,limited.ticket()),"response size bounded");check(big.disconnected,"size failure cleans request");
        Clock late=new Clock();Fake slow=new Fake();slow.onRead=()->late.now=3000;
        TrafficStats.Reader timed=new TrafficStats.Reader(new Factory(slow),late);
        fails(()->timed.read(endpoint,timed.ticket()),"overall deadline bounded");
        Fake cancelled=new Fake();final TrafficStats.Reader cancelReader=new TrafficStats.Reader(new Factory(cancelled),new Clock());
        cancelled.onRead=()->cancelReader.cancel();
        fails(()->cancelReader.read(endpoint,cancelReader.ticket()),"cancel during read rejected");check(cancelled.disconnected,"cancel disconnects");
        System.out.println("AuditTrafficTest: "+checks+" checks passed; fake transport only, no native/VPN/network execution");
    }
}
