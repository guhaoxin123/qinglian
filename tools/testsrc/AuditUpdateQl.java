import io.ghx.vpn.core.*;
import io.ghx.vpn.sub.*;
import java.util.*;
public final class AuditUpdateQl {
    static int checks;
    static void check(boolean ok, String s) { checks++; if (!ok) throw new AssertionError(s); }
    static void rejects(Runnable r) { boolean failed=false; try { r.run(); } catch (IllegalArgumentException e) { failed=true; } check(failed,"must reject"); }
    public static void main(String[] args) throws Exception {
        String[] nodes = {"trojan://p%40ss@a.example.invalid:443?sni=test.invalid&allowInsecure=0#A",
            "ss://"+B64.encodeUtf8("aes-128-gcm:test")+"@b.example.invalid:8388#B",
            "vless://11111111-1111-1111-1111-111111111111@c.example.invalid:443?security=tls&type=ws&path=%2Fchat&host=h.invalid#C"};
        for(String sep:new String[]{"\n","\r\n"," ",",",";","|","\\n","\\t",""}) {
            String plain=String.join(sep,nodes);
            for (String raw:new String[]{plain,B64.encodeUtf8(plain),B64.encodeUtf8(B64.encodeUtf8(plain)),"ql://"+plain,"ql:"+B64.encodeUtf8(plain)}) {
                String wrapped=QlParser.wrap(raw);
                check(SubParser.detect(wrapped)==SubParser.FMT_QL,"ql type");
                check(wrapped.equals(QlParser.wrap(wrapped)),"idempotent");
                String decoded=SubParser.unwrapBase64(raw);
                if(SubParser.detect(decoded)==SubParser.FMT_URI) check(wrapped.equals("ql://\n"+decoded),"exact original URI bytes");
                List<Node> before=SubParser.parseNodes(decoded,SubParser.detect(decoded)),after=QlParser.parse(wrapped);
                check(before.size()==after.size(),"size invariant");
                for(int i=0;i<before.size();i++) check(before.get(i).toYaml().equals(after.get(i).toYaml()),"all parameters invariant");
                Profile p=new Profile("id","name","https://example.invalid/sub",2);p.content=wrapped;p.updatedAt=123;p.enabled=false;p.lastError="error";p.autoUpdateMinutes=30;
                String next=p.contentForRefresh(plain);
                check(SubParser.detect(next)==SubParser.FMT_QL,"refresh retains ql");
                check(p.content.equals(wrapped)&&p.updatedAt==123&&!p.enabled&&p.autoUpdateMinutes==30&&p.lastError.equals("error"),"no side effects before commit");
                check(DocumentImport.inspect(next).format==SubParser.FMT_QL,"import agrees");
                rejects(()->p.contentForRefresh("proxies: []"));
                check(p.content.equals(wrapped),"failed refresh preserves cache");
                p.content=plain;p.type=4;
                check(p.contentForRefresh(plain).equals(plain),"stale type never auto-converts v2ray");
            }
        }
        for(String s:new String[]{"","ql://not a node","<html>bad</html>","proxies: []","[Proxy]\nA = trojan, a.invalid, 443, password=test"}) rejects(()->QlParser.wrap(s));
        check(UpdatePolicy.compare("2.10","2.9")>0,"numeric comparison"); check(UpdatePolicy.compare("v2.1","2.1.0")==0,"trailing zero");
        check(UpdatePolicy.compare("2.0","2.1")<0,"older");
        for(String s:new String[]{"", "2.1-beta","v2", "2.1/evil", "999999999999.1"}) rejects(()->UpdatePolicy.normalize(s));
        String asset="qinglian_2.1.apk",url=UpdatePolicy.REPO+"/releases/download/v2.1/"+asset;
        String digest="sha256:"+String.join("",Collections.nCopies(64,"a"));
        UpdatePolicy.Release r=new UpdatePolicy.Release("v2.1",asset,url,digest,123,"notes");
        check(r.version.equals("2.1")&&r.size==123,"valid asset");
        rejects(()->new UpdatePolicy.Release("v2.1",asset,url,null,123,""));
        rejects(()->new UpdatePolicy.Release("v2.1",asset,url,digest,UpdatePolicy.MAX_APK+1,""));
        rejects(()->new UpdatePolicy.Release("v2.1",asset,url+"?redirect=evil",digest,123,""));
        rejects(()->new UpdatePolicy.Release("v2.1","wrong.apk",url,digest,123,""));
        for(String s:new String[]{"http://github.com/x","https://github.com.evil.invalid/x","https://evil@github.com/x","file:///etc/a","https://release-assets.githubusercontent.com:444/x","https://evil.invalid/x"}) check(!UpdatePolicy.downloadHost(s),"untrusted host rejected");
        check(UpdatePolicy.downloadHost("https://release-assets.githubusercontent.com/a?sig=123"),"release CDN allowed");
        check(UpdatePolicy.hex(new byte[]{0,15,-1,-128}).equals("000fff80"),"hash hex");
        String masked=LogReader.redact("https://example.invalid/private?token=test trojan://secret@host:443 password=abc secret: def UUID=123");
        check(!masked.contains("example.invalid")&&!masked.contains("secret@")&&!masked.contains("abc")&&!masked.contains("def")&&!masked.contains("123"),"mask sensitive fields");
        System.out.println("AuditUpdateQl: "+checks+" checks passed; synthetic data, no network or VPN");
    }
}
