package io.ghx.vpn.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.ghx.vpn.core.Session;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** Read-only controller statistics. Never loads or starts the kernel. */
public final class TrafficPanel extends LinearLayout {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView speed, connections, totals, state;
    private final Graph graph;
    private boolean active, busy;
    private long generation, lastTime, lastUp, lastDown, session = -1;
    private volatile HttpURLConnection request;
    private final Runnable tick = new Runnable() { public void run() { poll(); } };
    public TrafficPanel(Context c) {
        super(c); setOrientation(VERTICAL);
        LinearLayout card = Ui.card(c);
        card.addView(Ui.text(c, L10n.t(c,"网络速度","Network speed"),18,Ui.TEXT));
        graph = new Graph(c); card.addView(graph,new LayoutParams(-1,Ui.dp(c,110)));
        speed = Ui.text(c,"↑ 0 B/s     ↓ 0 B/s",20,Ui.TEXT); card.addView(speed);
        state = Ui.text(c,L10n.t(c,"仅统计代理内核流量 · 每秒刷新","Proxy core traffic · Every second"),11,Ui.TEXT_DIM);card.addView(state);
        addView(card); Ui.margin(c,card,0,0,0,12);
        LinearLayout row = Ui.row(c);
        LinearLayout a = Ui.card(c), b = Ui.card(c);
        a.addView(Ui.text(c,L10n.t(c,"活跃连接","Active connections"),14,Ui.TEXT_MID));
        connections = Ui.text(c,"0",32,Ui.TEXT);a.addView(connections);
        b.addView(Ui.text(c,L10n.t(c,"流量使用","Traffic usage"),14,Ui.TEXT_MID));
        totals=Ui.text(c,"↑ 0 B\n↓ 0 B",18,Ui.TEXT);b.addView(totals);
        LayoutParams left=new LayoutParams(0,-1,1);left.rightMargin=Ui.dp(c,12);
        row.addView(a,left);row.addView(b,new LayoutParams(0,-1,1));addView(row);
    }
    public void resume() { if(active)return;active=true;generation++;lastTime=0;handler.post(tick); }
    public void pause() { active=false;generation++;lastTime=0;handler.removeCallbacks(tick);HttpURLConnection c=request;if(c!=null)c.disconnect(); }
    private void schedule(){if(active)handler.postDelayed(tick,1000);}
    private void poll(){
        if(!active)return;if(busy){schedule();return;}
        if(!Session.isKernelRunning()) {lastTime=0;session=-1;graph.clear();speed.setText("↑ 0 B/s     ↓ 0 B/s");connections.setText("0");totals.setText("↑ 0 B\n↓ 0 B");state.setText(L10n.t(getContext(),"未连接","Disconnected"));schedule();return;}
        busy=true;final long g=generation, epoch=Session.trafficEpoch();
        new Thread(new Runnable(){public void run(){
            long up=0,down=0;int count=0;boolean ok=false;HttpURLConnection c=null;
            try {
                c=(HttpURLConnection)new URL("http://127.0.0.1:9090/connections").openConnection(Proxy.NO_PROXY);request=c;
                c.setConnectTimeout(1200);c.setReadTimeout(1200);c.setInstanceFollowRedirects(false);
                if(c.getResponseCode()!=200)throw new Exception("HTTP");
                ByteArrayOutputStream out=new ByteArrayOutputStream();long deadline=SystemClock.elapsedRealtime()+2500;
                try(InputStream in=c.getInputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>4*1024*1024||SystemClock.elapsedRealtime()>deadline)throw new Exception("limit");out.write(buf,0,n);}}
                JSONObject j=new JSONObject(out.toString("UTF-8"));up=j.getLong("uploadTotal");down=j.getLong("downloadTotal");
                if(!j.has("connections"))throw new Exception("missing connections");
                count=j.isNull("connections")?0:j.getJSONArray("connections").length();if(up<0||down<0)throw new Exception("counter");ok=true;
            }catch(Exception ignored){}finally{if(c!=null)c.disconnect();request=null;}
            final long u=up,d=down;final int n=count;final boolean valid=ok;
            handler.post(new Runnable(){public void run(){busy=false;
                if(!active||g!=generation)return;
                if(!Session.isKernelRunning()||epoch!=Session.trafficEpoch()){lastTime=0;schedule();return;}
                if(!valid){lastTime=0;speed.setText("↑ —     ↓ —");connections.setText("—");totals.setText("↑ —\n↓ —");state.setText(L10n.t(getContext(),"统计暂不可用","Statistics unavailable"));graph.clear();schedule();return;}
                long now=SystemClock.elapsedRealtime();double us=0,ds=0;
                if(session==epoch&&lastTime>0&&now>lastTime&&u>=lastUp&&d>=lastDown){us=(u-lastUp)*1000.0/(now-lastTime);ds=(d-lastDown)*1000.0/(now-lastTime);}else graph.clear();
                session=epoch;lastTime=now;lastUp=u;lastDown=d;
                speed.setText("↑ "+format(us)+"/s     ↓ "+format(ds)+"/s");connections.setText(String.valueOf(n));totals.setText("↑ "+format(u)+"\n↓ "+format(d));
                state.setText(L10n.t(getContext(),"代理内核累计 · 曲线最近60次采样","Core totals · Last 60 samples"));graph.add(us,ds);schedule();
            }});
        }},"traffic-read").start();
    }
    private static String format(double n){String[] units={"B","KB","MB","GB","TB"};int i=0;while(n>=1024&&i<units.length-1){n/=1024;i++;}return String.format(Locale.ROOT,i==0?"%.0f %s":"%.2f %s",n,units[i]);}
    private static final class Graph extends View {
        final double[] up=new double[60],down=new double[60];final Paint paint=new Paint(3);int size;
        Graph(Context c){super(c);setContentDescription(L10n.t(c,"上传和下载速度曲线","Upload and download speed chart"));}
        void clear(){size=0;invalidate();}
        void add(double u,double d){if(size==60){System.arraycopy(up,1,up,0,59);System.arraycopy(down,1,down,0,59);size--;}up[size]=u;down[size++]=d;invalidate();}
        protected void onDraw(Canvas c){super.onDraw(c);double max=1;for(int i=0;i<size;i++)max=Math.max(max,Math.max(up[i],down[i]));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Ui.dp(getContext(),2));
            for(int series=0;series<2;series++){paint.setColor(series==0?Ui.TEXT_MID:Ui.ACCENT);Path p=new Path();double[] a=series==0?up:down;for(int i=0;i<60;i++){float x=i*(getWidth()/59f);int index=i-(60-size);float y=getHeight()-8-(float)((index>=0?a[index]:0)/max*(getHeight()-24));if(i==0)p.moveTo(x,y);else p.lineTo(x,y);}c.drawPath(p,paint);}}
    }
}
