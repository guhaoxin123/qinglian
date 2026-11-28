import io.ghx.vpn.core.*;
import android.content.*;
import android.content.pm.*;
import java.nio.file.*;
public class AuditSignature {
    static int checks;
    interface Test { void run() throws Exception; }
    static void check(boolean ok) { checks++; if(!ok)throw new AssertionError(); }
    static void reject(Test r) throws Exception { boolean failed=false;try{r.run();}catch(SecurityException e){failed=true;}check(failed); }
    static PackageInfo info(byte[] cert) {
        PackageInfo i=new PackageInfo();i.packageName="io.ghx.vpn";i.versionName="2.1";i.versionCode=21;
        i.signatures=new Signature[]{new Signature(cert)};i.signingInfo=new SigningInfo();i.signingInfo.signers=i.signatures;return i;
    }
    public static void main(String[] args)throws Exception{
        byte[] cert=Files.readAllBytes(Paths.get(args[0]));
        for(int sdk:new int[]{24,27,28,35}){
            android.os.Build.VERSION.SDK_INT=sdk;Context c=new Context();c.pm.current=info(cert);
            SignatureGuard.requireTrusted(c);check(SignatureGuard.CERT_SHA256.equals(SignatureGuard.fingerprint(c.pm.current)));
            c.pm.current=info(new byte[]{1,2,3});reject(()->SignatureGuard.requireTrusted(c));
            c.pm.current=null;reject(()->SignatureGuard.requireTrusted(c));
            c.pm.current=info(cert);c.pm.current.packageName="other";reject(()->SignatureGuard.requireTrusted(c));
            c.pm.current=info(cert);c.pm.current.signatures=new Signature[0];c.pm.current.signingInfo.signers=new Signature[0];reject(()->SignatureGuard.requireTrusted(c));
            c.pm.current=info(cert);c.pm.current.signatures=new Signature[]{new Signature(cert),new Signature(cert)};c.pm.current.signingInfo.signers=c.pm.current.signatures;reject(()->SignatureGuard.requireTrusted(c));
            c.pm.current=info(cert);c.pm.current.versionName="2.0";c.pm.current.versionCode=20;c.pm.archive=info(cert);
            String digest="sha256:"+new String(new char[64]).replace('\0','a');
            UpdatePolicy.Release r=new UpdatePolicy.Release("v2.1","qinglian_2.1.apk",UpdatePolicy.REPO+"/releases/download/v2.1/qinglian_2.1.apk",digest,123,"");
            SignatureGuard.verifyUpdate(c,new java.io.File("fixture.apk"),r);check(true);
            c.pm.archive=info(new byte[]{0});reject(()->SignatureGuard.verifyUpdate(c,new java.io.File("fixture.apk"),r));
            c.pm.archive=info(cert);c.pm.archive.versionCode=20;reject(()->SignatureGuard.verifyUpdate(c,new java.io.File("fixture.apk"),r));
            c.pm.archive=info(cert);c.pm.archive.versionName="2.2";reject(()->SignatureGuard.verifyUpdate(c,new java.io.File("fixture.apk"),r));
            c.pm.archive=info(cert);c.pm.archive.packageName="evil";reject(()->SignatureGuard.verifyUpdate(c,new java.io.File("fixture.apk"),r));
        }
        System.out.println("AuditSignature: "+checks+" checks passed; real public cert, synthetic PackageManager (no app/VPN)");
    }
}
