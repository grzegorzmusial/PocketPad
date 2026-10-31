package pl.pocketpad;

import android.view.MotionEvent;
import android.os.SystemClock;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ModeUiTest {
    ActivityController<MainActivity> controller;
    MainActivity activity;
    @Before public void create(){
        controller=Robolectric.buildActivity(MainActivity.class).create();
        activity=controller.get();
        activity.preferences.edit().clear().commit();activity.xboxMode=false;activity.carMode=false;
        activity.steeringSensor=org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_GRAVITY);
        activity.pad.layout(0,0,1000,480);
    }
    @After public void close(){if(controller!=null)controller.destroy();}
    void tap(float x,float y){
        long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);
        MotionEvent up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0);
        try{activity.pad.onTouchEvent(down);activity.pad.onTouchEvent(up);}finally{down.recycle();up.recycle();}
    }
    @Test public void modeButtonsChooseAndRememberMode(){
        tap(602,39);
        assertTrue(activity.xboxMode);
        assertTrue(activity.preferences.getBoolean("xbox_mode",false));
        controller.destroy();
        controller=Robolectric.buildActivity(MainActivity.class).create();activity=controller.get();
        assertTrue(activity.xboxMode);
        tap(430,39);
        assertFalse(activity.xboxMode);
        assertFalse(activity.preferences.getBoolean("xbox_mode",true));
    }
    @Test public void changingModeReleasesGameButtonsWithoutChangingReportFormat(){
        for(PadView.Control control:activity.pad.controls)if("A".equals(control.label))control.active=true;
        activity.pad.aggregate();assertEquals(1,activity.state.buttons);
        tap(602,39);
        assertArrayEquals(new byte[]{0,0,8,0,0,0,0},activity.state.report());
        assertEquals(1,PadState.REPORT_ID);
    }
    @Test public void bluetoothModeProtocolRequiresTheCorrectAcknowledgement(){
        assertEquals("POCKETPAD/1 MODE XBOX\n",ModeProtocol.command(true));
        assertEquals("POCKETPAD/1 MODE DINPUT\n",ModeProtocol.command(false));
        assertTrue(ModeProtocol.accepted(true,"OK XBOX"));
        assertTrue(ModeProtocol.accepted(false,"OK DINPUT"));
        assertFalse(ModeProtocol.accepted(true,"OK DINPUT"));
        assertFalse(ModeProtocol.accepted(true,"ERROR SWITCH_FAILED"));
    }
    @Test public void wheelControlsOnlyLeftHorizontalAxisAndCanBeDisabled(){
        activity.carMode=true;activity.steeringListening=true;
        activity.steering.update(0,9.8f,0);
        for(int i=0;i<30;i++)activity.steering.update(-9.8f,0,0);
        activity.pad.aggregate();
        assertTrue(activity.state.lx>120);assertEquals(0,activity.state.ly);assertEquals(0,activity.state.rx);
        activity.selectCarMode();
        assertFalse(activity.carMode);assertEquals(0,activity.state.lx);
    }
    @Test public void carButtonPersistsAndRecenterReleasesSteering(){
        tap(430,112);assertTrue(activity.carMode);assertTrue(activity.preferences.getBoolean("car_mode",false));
        activity.steeringListening=true;activity.steering.update(0,9.8f,0);
        for(int i=0;i<30;i++)activity.steering.update(-9.8f,0,0);
        activity.pad.aggregate();assertTrue(activity.state.lx>120);
        tap(602,112);assertEquals(0,activity.state.lx);
        tap(430,112);assertFalse(activity.carMode);
    }
    @Test public void wheelTracksLeftRightDeadzoneAndBothLandscapeOrientations(){
        for(int rotation:new int[]{0,1,2,3}){
            Steering s=new Steering();
            feed(s,rotation,0);assertEquals(0,s.value,0);
            feed(s,rotation,2);assertEquals(0,s.value,0);
            feed(s,rotation,45);assertTrue(s.value>.99);
            feed(s,rotation,-45);assertTrue(s.value<-.99);
            s.reset();feed(s,rotation,20);assertEquals(0,s.value,0);
            feed(s,rotation,20);assertEquals(0,s.value,0);
        }
    }
    void feed(Steering steering,int rotation,double degrees){
        float sx=(float)(-9.8*Math.sin(Math.toRadians(degrees))),sy=(float)(9.8*Math.cos(Math.toRadians(degrees)));
        float x=sx,y=sy;
        if(rotation==1){x=-sy;y=sx;}else if(rotation==2){x=-sx;y=-sy;}else if(rotation==3){x=sy;y=-sx;}
        for(int i=0;i<40;i++)steering.update(x,y,rotation);
    }
    @Test public void staleHidConnectionNeverConfirmsWindows(){
        android.bluetooth.BluetoothDevice device=android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55");
        activity.callback.onConnectionStateChanged(device,android.bluetooth.BluetoothProfile.STATE_CONNECTED);
        assertNotNull(activity.host);assertFalse(activity.windowsConnected());
        assertFalse(activity.status.startsWith("Połączono"));
        activity.windowsReply(true);assertTrue(activity.windowsConnected());
        activity.callback.onConnectionStateChanged(device,android.bluetooth.BluetoothProfile.STATE_DISCONNECTED);
        assertNull(activity.host);assertFalse(activity.windowsConnected());
    }
    @Test public void missingWindowsHeartbeatExpiresEvenIfHidStillClaimsConnected(){
        activity.windowsReply(true);assertTrue(activity.windowsConnected());
        activity.checkWindowsConnection(activity.lastWindowsAck+4999);assertTrue(activity.windowsConnected());
        activity.checkWindowsConnection(activity.lastWindowsAck+5000);assertFalse(activity.windowsConnected());
        assertTrue(activity.status.contains("Utracono"));
        activity.windowsReply(true);assertTrue(activity.windowsConnected());
        activity.windowsReply(false);assertFalse(activity.windowsConnected());
    }
    @Test public void wheelReachesNinetyFivePercentWithinThirtyMillisecondsAtDifferentRates(){
        for(int interval:new int[]{5,10,20,30}){
            Steering s=new Steering();long time=1_000_000_000L;
            s.update(0,9.8f,0,time);
            for(int elapsed=interval;elapsed<=30;elapsed+=interval){
                s.update(-9.8f,0,0,time+elapsed*1_000_000L);
            }
            // At 20 ms only the first 20 ms sample falls within this window.
            assertTrue("Fast initial response at "+interval+" ms",s.value>(interval==20?.86:.949));
            s.update(-9.8f,0,0,time+60_000_000L);assertTrue(s.value>.99);
        }
    }
    @Test public void wheelQuicklyReturnsToNeutralAndReverses(){
        Steering s=new Steering();long time=1_000_000_000L;
        s.update(0,9.8f,0,time);
        for(int i=1;i<=6;i++)s.update(-9.8f,0,0,time+i*10_000_000L);
        assertTrue(s.value>.99);
        for(int i=7;i<=10;i++)s.update(9.8f,0,0,time+i*10_000_000L);
        assertTrue(s.value<-.96);
        for(int i=11;i<=16;i++)s.update(0,9.8f,0,time+i*10_000_000L);
        assertEquals(0,s.value,0);
    }
    @Test public void largerButtonEdgesSendCorrectInputsAndControlsDoNotOverlap(){
        hold(895,356);assertEquals(1,activity.state.buttons);activity.pad.release();
        hold(472,328);assertEquals(2,activity.state.hat);activity.pad.release();
        for(PadView.Control a:activity.pad.controls){
            if(a.r<=0)continue;
            for(PadView.Control b:activity.pad.controls){
                if(a==b)continue;
                double distance;
                if(b.r>0)distance=Math.hypot(a.x-b.x,a.y-b.y)-b.r;
                else distance=Math.hypot(Math.max(0,Math.abs(a.x-b.x)-b.w/2),Math.max(0,Math.abs(a.y-b.y)-b.h/2));
                assertTrue(a.label+" overlaps "+b.label,distance>=a.r);
            }
            assertTrue(a.x-a.r>=0 && a.x+a.r<=1000 && a.y+a.r<447);
        }
    }
    void hold(float x,float y){
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);
        try{activity.pad.onTouchEvent(down);}finally{down.recycle();}
    }
    android.bluetooth.BluetoothDevice device(String suffix){return android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:"+suffix);}
    @Test public void androidTabletOrTvNeverProbesWindowsOrDisplaysWindowsFailure(){
        android.bluetooth.BluetoothDevice tv=device("66");activity.setHostKind(tv,"android");
        activity.foreground=true;activity.registered=true;activity.xboxMode=true;
        activity.callback.onConnectionStateChanged(tv,android.bluetooth.BluetoothProfile.STATE_CONNECTED);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700));
        assertTrue(activity.androidHost());assertTrue(activity.connectionConfirmed());
        assertEquals("ANDROID: POŁĄCZONY",activity.connectionCaption());assertFalse(activity.status.contains("Windows"));
        activity.syncMode(true);activity.selectMode(true);
        assertFalse(activity.status.contains("Windows"));assertTrue(activity.xboxMode);
        activity.callback.onConnectionStateChanged(tv,android.bluetooth.BluetoothProfile.STATE_DISCONNECTED);
        assertFalse(activity.connectionConfirmed());
    }
    @Test public void windowsStillRequiresAcknowledgementAndAndroidSettingIsPerDevice(){
        android.bluetooth.BluetoothDevice tablet=device("66"),pc=device("77");
        activity.setHostKind(tablet,"android");
        activity.preferences.edit().putStringSet("windows_receivers",new java.util.HashSet<>(java.util.Arrays.asList(pc.getAddress()))).commit();
        activity.host=pc;activity.registered=true;
        assertEquals("windows",activity.hostKind(pc));assertFalse(activity.androidHost());assertFalse(activity.connectionConfirmed());
        assertEquals("WINDOWS: NIEPOTWIERDZONE",activity.connectionCaption());
        activity.windowsReply(true);assertTrue(activity.connectionConfirmed());
        activity.checkWindowsConnection(activity.lastWindowsAck+5000);
        assertFalse(activity.connectionConfirmed());assertTrue(activity.status.contains("Windows"));
        activity.host=tablet;assertTrue(activity.androidHost());assertEquals("ANDROID: POŁĄCZONY",activity.connectionCaption());
    }
    @Test public void hostTypePickerRemembersAndroidAfterRecreatingActivity(){
        android.bluetooth.BluetoothDevice tablet=device("66");activity.foreground=true;
        activity.chooseHostKind(tablet,()->{});
        android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);dialog.getListView().performItemClick(null,1,1);
        assertEquals("android",activity.hostKind(tablet));
        controller.destroy();controller=Robolectric.buildActivity(MainActivity.class).create();activity=controller.get();
        assertEquals("android",activity.hostKind(tablet));
    }
}
