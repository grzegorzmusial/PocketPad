package pl.pocketpad;

import android.graphics.*;
import android.view.*;
import java.util.*;

public class PadView extends View {
    final MainActivity activity;
    final Paint p=new Paint(3);
    final ArrayList<Control> controls=new ArrayList<>();
    float scale=1,ox,oy;
    static final int BG=Color.rgb(10,16,27), CARD=Color.rgb(22,33,49), LINE=Color.rgb(43,59,79), TEXT=Color.rgb(232,240,249), MUTED=Color.rgb(133,153,178), MINT=Color.rgb(103,232,192);
    static class Control {
        String label;float x,y,r,w,h;int bit,color,owner=-1,type;boolean active;float dx,dy;
        Control(String label,float x,float y,float r,int bit,int color,int type){this.label=label;this.x=x;this.y=y;this.r=r;this.bit=bit;this.color=color;this.type=type;}
        boolean hit(float px,float py){return w>0?Math.abs(px-x)<w/2 && Math.abs(py-y)<h/2:Math.hypot(px-x,py-y)<r;}
    }
    Control add(String s,float x,float y,float r,int bit,int col,int type){Control c=new Control(s,x,y,r,bit,col,type);controls.add(c);return c;}
    void rect(String s,float x,float y,float w,float h,int bit){Control c=add(s,x,y,0,bit,TEXT,0);c.w=w;c.h=h;}
    PadView(MainActivity a){
        super(a);activity=a;setFocusable(true);setContentDescription("Pad Bluetooth. Dwa drążki, krzyżak i przyciski. Pomoc i połączenie w prawym górnym rogu.");
        rect("L2",77,112,92,44,8);rect("L1",184,112,92,44,6);
        rect("R1",816,112,92,44,7);rect("R2",923,112,92,44,9);
        add("L",172,269,89,-1,MINT,1);
        add("R",622,328,63,-1,MINT,2);
        add("DPAD",382,328,97.5f,-1,MINT,3);
        add("Y",850,200,49.5f,4,Color.rgb(255,206,105),0);
        add("X",774,278,49.5f,3,Color.rgb(113,178,255),0);
        add("B",926,278,49.5f,1,Color.rgb(255,126,143),0);
        add("A",850,356,49.5f,0,MINT,0);
        rect("SELECT",454,179,78,36,10);rect("START",546,179,78,36,11);
        rect("L3",172,397,72,32,13);rect("R3",622,419,72,32,14);
        Control connect=add("POŁĄCZ",850,39,0,-1,MINT,4);connect.w=155;connect.h=38;
        Control help=add("?",958,39,0,-1,TEXT,5);help.w=38;help.h=38;
        Control direct=add("DirectInput",430,39,0,-1,MINT,6);direct.w=156;direct.h=38;
        Control xbox=add("Xbox",602,39,0,-1,MINT,7);xbox.w=156;xbox.h=38;
        Control car=add("AUTO: WYŁ.",430,112,0,-1,MINT,8);car.w=156;car.h=38;
        Control center=add("WYŚRODKUJ",602,112,0,-1,MINT,9);center.w=156;center.h=38;
    }
    void color(int c){p.setColor(c);p.setStyle(Paint.Style.FILL);}
    void text(Canvas c,String s,float x,float y,float size,int color){color(color);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(size);p.setTextAlign(Paint.Align.CENTER);c.drawText(s,x,y,p);}
    void round(Canvas c,float l,float t,float r,float b,float rad,int col){color(col);c.drawRoundRect(l,t,r,b,rad,rad,p);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);c.drawColor(BG);scale=Math.min(getWidth()/1000f,getHeight()/480f);ox=(getWidth()-1000*scale)/2;oy=(getHeight()-480*scale)/2;
        c.save();c.translate(ox,oy);c.scale(scale,scale);
        round(c,22,17,61,56,12,MINT);text(c,"+",41,47,30,BG);
        text(c,"POCKET PAD",165,38,22,TEXT);text(c,activity.connectionCaption(),175,56,9,activity.connectionConfirmed()?MINT:MUTED);
        color(LINE);c.drawRect(24,73,976,74,p);
        text(c,"LEWY DRĄŻEK",172,160,10,MUTED);
        text(c,"KRZYŻAK",382,438,10,MUTED);
        text(c,"PRAWY DRĄŻEK",622,242,10,MUTED);
        text(c,"ABXY",850,428,10,MUTED);
        for(Control a:controls){
            if(a.type==1 || a.type==2){
                color(LINE);c.drawCircle(a.x,a.y,a.r,p);color(CARD);c.drawCircle(a.x,a.y,a.r-2,p);
                p.setColor(Color.rgb(48,66,85));p.setStrokeWidth(1);c.drawLine(a.x-a.r+12,a.y,a.x+a.r-12,a.y,p);c.drawLine(a.x,a.y-a.r+12,a.x,a.y+a.r-12,p);
                float dx=a.type==1 && activity.carMode?activity.steering.value:a.dx;
                color(a.active||a.type==1&&activity.carMode?MINT:Color.rgb(47,66,85));c.drawCircle(a.x+dx*a.r*.54f,a.y+a.dy*a.r*.54f,a.r*.43f,p);
                text(c,a.label,a.x+dx*a.r*.54f,a.y+a.dy*a.r*.54f+6,17,a.active||a.type==1&&activity.carMode?BG:TEXT);
            }else if(a.type==3){
                c.save();c.translate(a.x,a.y);c.scale(a.r/65f,a.r/65f);
                round(c,-21,-64,21,64,10,CARD);round(c,-64,-21,64,21,10,CARD);
                text(c,"▲",0,-35,18,a.active&&a.dy<-.25?MINT:MUTED);
                text(c,"▼",0,48,18,a.active&&a.dy>.25?MINT:MUTED);
                text(c,"◀",-43,6,18,a.active&&a.dx<-.25?MINT:MUTED);
                text(c,"▶",43,6,18,a.active&&a.dx>.25?MINT:MUTED);c.restore();
            }else{
                boolean chosen=(a.type==6 && (!activity.xboxMode||activity.androidHost()))||(a.type==7 && activity.xboxMode&&!activity.androidHost())||(a.type==8 && activity.carMode);
                if(a.type==8)a.label=activity.carMode?"AUTO: WŁ.":"AUTO: WYŁ.";
                if(a.type==4)a.label=activity.connectionConfirmed()?"POŁĄCZONY":activity.host!=null?"SPRAWDŹ":"POŁĄCZ";
                if(a.w>0){round(c,a.x-a.w/2,a.y-a.h/2,a.x+a.w/2,a.y+a.h/2,10,(a.active||chosen)?a.color:(a.type==4?Color.rgb(28,63,60):CARD));}
                else{color(a.active?a.color:LINE);c.drawCircle(a.x,a.y,a.r,p);if(!a.active){color(CARD);c.drawCircle(a.x,a.y,a.r-2,p);}}
                text(c,a.label,a.x,a.y+(a.w>0?5:12),a.w>0?12:36,(a.active||chosen)?BG:a.color);
            }
        }
        round(c,24,447,976,477,10,CARD);
        color(activity.connectionConfirmed()?MINT:MUTED);c.drawCircle(40,462,4,p);
        p.setTextAlign(Paint.Align.LEFT);p.setTextSize(12);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setColor(TEXT);
        String status=activity.status;while(status.length()>1 && p.measureText(status)>900)status=status.substring(0,status.length()-2)+"…";
        c.drawText(status,54,466,p);c.restore();
    }
    void update(Control a,float x,float y){
        if(a.type>=1 && a.type<=3){
            float dx=(x-a.x)/(a.r*.72f),dy=(y-a.y)/(a.r*.72f);float len=(float)Math.hypot(dx,dy);if(len>1){dx/=len;dy/=len;}
            if(len<.12){dx=0;dy=0;}a.dx=dx;a.dy=dy;a.active=true;
        }else a.active=a.hit(x,y);
    }
    void aggregate(){
        PadState s=activity.state;s.clear();
        for(Control a:controls){if(!a.active)continue;
            if(a.type==0)s.buttons|=1<<a.bit;
            else if(a.type==1){s.lx=PadState.axis(a.dx);s.ly=PadState.axis(a.dy);}
            else if(a.type==2){s.rx=PadState.axis(a.dx);s.ry=PadState.axis(a.dy);}
            else if(a.type==3)s.hat=PadState.hat(a.dx,a.dy);
        }if(activity.carMode && activity.steeringListening)s.lx=PadState.axis(activity.steering.value);activity.send();invalidate();
    }
    void release(){for(Control a:controls){a.owner=-1;a.active=false;a.dx=a.dy=0;}aggregate();}
    @Override public boolean onTouchEvent(MotionEvent event){
        int action=event.getActionMasked(),index=event.getActionIndex(),id=event.getPointerId(index);
        float x=(event.getX(index)-ox)/scale,y=(event.getY(index)-oy)/scale;
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN){
            for(Control a:controls)if(a.owner==-1 && a.hit(x,y)){a.owner=id;update(a,x,y);performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);break;}
        }else if(action==MotionEvent.ACTION_MOVE){
            for(Control a:controls)if(a.owner!=-1){int i=event.findPointerIndex(a.owner);if(i>=0)update(a,(event.getX(i)-ox)/scale,(event.getY(i)-oy)/scale);}
        }else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP){
            int menu=0;
            for(Control a:controls)if(a.owner==id){if(a.hit(x,y) && a.type>=4)menu=a.type;a.owner=-1;a.active=false;a.dx=a.dy=0;}
            aggregate();if(menu>=4)release();if(menu==4)activity.connectMenu();else if(menu==5)activity.help();else if(menu==6)activity.selectMode(false);else if(menu==7)activity.selectMode(true);else if(menu==8)activity.selectCarMode();else if(menu==9)activity.centerSteering();performClick();return true;
        }else if(action==MotionEvent.ACTION_CANCEL){release();return true;}
        aggregate();return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
