package io.ghx.vpn.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.ghx.vpn.core.Session;
import org.json.JSONObject;
import io.ghx.vpn.core.TrafficStats;
import java.util.Locale;

/** Read-only controller statistics. Never loads or starts the kernel. */
public final class TrafficPanel extends LinearLayout {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView speed, connections, totals, state;
    private final Graph graph;
    private boolean active, busy;
    private long generation, session = -1;
    private final TrafficStats.Reader reader = new TrafficStats.Reader();
    private volatile Thread worker;
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
    public void resume() {
        if (active) return;
        active=true; generation++; graph.clear(); handler.post(tick);
    }
    public void pause() {
        active=false; generation++; handler.removeCallbacks(tick); reader.cancel();
        Thread w=worker; if(w!=null)w.interrupt();
    }
    private void schedule(){if(active){handler.removeCallbacks(tick);handler.postDelayed(tick,1000);}}
    private void poll(){
        if(!active)return;
        if(busy){schedule();return;}
        if(!Session.isKernelRunning()) {
            session=-1;graph.clear();speed.setText("↑ 0 B/s     ↓ 0 B/s");connections.setText("0");
            totals.setText("↑ 0 B\n↓ 0 B");state.setText(L10n.t(getContext(),"未连接","Disconnected"));schedule();return;
        }
        busy=true;final long g=generation, epoch=Session.trafficEpoch(), ticket=reader.ticket();
        worker=new Thread(new Runnable(){public void run(){
            TrafficStats.Sample sample=null;int count=-1;
            try {
                // Primary counters are independent of REST availability and its rounding.
                sample=Session.queryTraffic(epoch);
            }catch(Exception | LinkageError ignored) { }
            try {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                JSONObject json=new JSONObject(reader.read(Session.statisticsEndpoint(),ticket));
                java.util.Map<String,Object> fields=new java.util.LinkedHashMap<String,Object>();
                if(json.has("connections")) {
                    if(json.isNull("connections")) fields.put("connections",null);
                    else fields.put("connections",java.util.Collections.nCopies(json.getJSONArray("connections").length(),Boolean.TRUE));
                }
                count=TrafficStats.connections(fields);
            }catch(Exception | LinkageError ignored) { }
            final TrafficStats.Sample value=sample;final int n=count;
            handler.post(new Runnable(){public void run(){
                busy=false;worker=null;
                if(!active||g!=generation){schedule();return;}
                if(!Session.isKernelRunning()||epoch!=Session.trafficEpoch()){graph.clear();schedule();return;}
                if(session!=epoch){graph.clear();session=epoch;}
                connections.setText(n<0?"—":String.valueOf(n));
                if(value==null){
                    speed.setText("↑ —     ↓ —");totals.setText("↑ —\n↓ —");graph.clear();
                    state.setText(L10n.t(getContext(),"内核计数器暂不可用 · 自动重试","Core counters unavailable · Retrying"));
                }else{
                    speed.setText("↑ "+format(value.upSpeed)+"/s     ↓ "+format(value.downSpeed)+"/s");
                    totals.setText("↑ "+format(value.upTotal)+"\n↓ "+format(value.downTotal));
                    state.setText(n<0?L10n.t(getContext(),"内核流量正常 · 连接数接口不可用","Core traffic available · Connection count unavailable")
                            :L10n.t(getContext(),"代理内核累计 · 曲线最近60次采样","Core totals · Last 60 samples"));
                    graph.add(value.upSpeed,value.downSpeed);
                }
                schedule();
            }});
        }},"traffic-read");worker.start();
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
