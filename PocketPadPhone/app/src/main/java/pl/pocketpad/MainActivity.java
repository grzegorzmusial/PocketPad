package pl.pocketpad;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.hardware.*;
import java.util.*;

public class MainActivity extends Activity implements SensorEventListener {
    BluetoothAdapter adapter;
    BluetoothHidDevice hid;
    BluetoothDevice host;
    PadView pad;
    final PadState state=new PadState();
    boolean registered, registering, proxyRequested, foreground, destroyed, choosing;
    String status="Dotknij POŁĄCZ, aby wybrać tablet";
    final Handler handler=new Handler(Looper.getMainLooper());
    final WindowsModeClient modeClient=new WindowsModeClient(handler);
    SharedPreferences preferences;
    boolean xboxMode;
    boolean windowsConfirmed;
    long lastWindowsAck;
    final Runnable retryMode=()->{if(foreground && host!=null && registered && !windowsConnected())syncMode(false);};
    boolean carMode, steeringListening;
    SensorManager sensors;
    Sensor steeringSensor;
    final Steering steering=new Steering();
    final Runnable heartbeat=new Runnable(){public void run(){if(foreground){checkWindowsConnection(SystemClock.elapsedRealtime());send();handler.postDelayed(this,40);}}};

    final BluetoothHidDevice.Callback callback=new BluetoothHidDevice.Callback(){
        @Override public void onAppStatusChanged(BluetoothDevice device,boolean ok){
            registered=ok;registering=false;
            if(ok){
                status("Gotowy · wybierz sparowany tablet");
                if(choosing && foreground){choosing=false;devices();}
            } else {clearWindowsConnection();modeClient.cancel();host=null;state.clear();status("Pad nieaktywny · dotknij POŁĄCZ");}
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device,int connection){
            if(connection==BluetoothProfile.STATE_CONNECTED){clearWindowsConnection();host=device;pad.release();status(androidHost()?androidStatus():"HID zgłasza połączenie · sprawdzam typ urządzenia…");send();handler.postDelayed(()->{if(foreground && device.equals(host)){if(hostKind(device).isEmpty())chooseHostKind(device,()->syncMode(false));else syncMode(false);}},600);}
            else if(connection==BluetoothProfile.STATE_CONNECTING)status("Łączenie z "+name(device)+"…");
            else if(connection==BluetoothProfile.STATE_DISCONNECTED && (host==null || host.equals(device))){clearWindowsConnection();modeClient.cancel();host=null;pad.release();status("Rozłączono · dotknij POŁĄCZ");}
        }
        @Override public void onGetReport(BluetoothDevice device,byte type,byte id,int size){
            try {if(type==BluetoothHidDevice.REPORT_TYPE_INPUT && id==PadState.REPORT_ID)hid.replyReport(device,type,id,state.report());
                else hid.reportError(device,BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            }catch(SecurityException ignored){}
        }
        @Override public void onVirtualCableUnplug(BluetoothDevice device){clearWindowsConnection();modeClient.cancel();host=null;state.clear();status("Urządzenie usunęło parowanie · sparuj ponownie");}
    };
    final BluetoothProfile.ServiceListener listener=new BluetoothProfile.ServiceListener(){
        @Override public void onServiceConnected(int profile,BluetoothProfile service){
            proxyRequested=false;
            if(destroyed){adapter.closeProfileProxy(profile,service);return;}
            hid=(BluetoothHidDevice)service;
            if(foreground)register();
        }
        @Override public void onServiceDisconnected(int profile){clearWindowsConnection();modeClient.cancel();hid=null;registered=false;registering=false;proxyRequested=false;host=null;state.clear();status("Bluetooth HID niedostępny · spróbuj ponownie");}
    };
    @Override public void onCreate(Bundle bundle){
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        preferences=getSharedPreferences("pocket_pad_settings",MODE_PRIVATE);
        xboxMode=preferences.getBoolean("xbox_mode",false);
        carMode=preferences.getBoolean("car_mode",false);
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);
        if(sensors!=null){steeringSensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);if(steeringSensor==null)steeringSensor=sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);}
        pad=new PadView(this);setContentView(pad);
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
    }
    boolean permissions(){return Build.VERSION.SDK_INT<31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED);}
    void status(String text){status=text;if(pad!=null)pad.invalidate();}
    String hostKind(BluetoothDevice device){
        if(device==null || preferences==null)return "";
        try{String address=device.getAddress();String kind=preferences.getString("host_kind_"+address,"");if(!kind.isEmpty())return kind;
            return preferences.getStringSet("windows_receivers",Collections.emptySet()).contains(address)?"windows":"";
        }catch(SecurityException ignored){return "";}
    }
    boolean androidHost(){return "android".equals(hostKind(host));}
    boolean connectionConfirmed(){return androidHost()?host!=null&&registered:windowsConnected();}
    String connectionCaption(){if(androidHost())return connectionConfirmed()?"ANDROID: POŁĄCZONY":"ANDROID: ROZŁĄCZONY";if(host==null)return "BLUETOOTH CONTROLLER";if(hostKind(host).isEmpty())return "BLUETOOTH: WYBIERZ TYP";return windowsConnected()?"WINDOWS: POŁĄCZONY":"WINDOWS: NIEPOTWIERDZONE";}
    String androidStatus(){return "Połączono · "+name(host)+" · pad Bluetooth dla Androida";}
    void setHostKind(BluetoothDevice device,String kind){
        try{preferences.edit().putString("host_kind_"+device.getAddress(),kind).apply();}catch(SecurityException ignored){return;}
        if(device.equals(host)){clearWindowsConnection();modeClient.cancel();pad.release();if("android".equals(kind))status(androidStatus());}
    }
    void chooseHostKind(BluetoothDevice device,Runnable next){
        if(!foreground || isFinishing())return;
        new AlertDialog.Builder(this).setTitle("Typ urządzenia: "+name(device)).setItems(new String[]{"Komputer z Windows","Tablet / TV z Androidem"},(d,i)->{setHostKind(device,i==0?"windows":"android");next.run();}).setNegativeButton("Anuluj",null).show();
    }
    boolean windowsConnected(){return windowsConfirmed && SystemClock.elapsedRealtime()-lastWindowsAck<5000;}
    void clearWindowsConnection(){windowsConfirmed=false;lastWindowsAck=0;handler.removeCallbacks(retryMode);if(pad!=null)pad.invalidate();}
    void windowsReply(boolean accepted){windowsConfirmed=accepted;lastWindowsAck=accepted?SystemClock.elapsedRealtime():0;if(pad!=null)pad.invalidate();}
    void checkWindowsConnection(long now){
        if(androidHost())return;
        if(windowsConfirmed && now-lastWindowsAck>=5000){
            clearWindowsConnection();modeClient.cancel();
            status("Utracono odpowiedź odbiornika Windows · ponawiam połączenie…");
            handler.postDelayed(retryMode,3000);
        }
    }
    void selectCarMode(){
        if(!carMode && steeringSensor==null){status("Brak czujnika przechylenia w telefonie");return;}
        carMode=!carMode;preferences.edit().putBoolean("car_mode",carMode).apply();
        steering.reset();pad.release();updateSteeringSensor();
        status(carMode?"Samochód: trzymaj telefon jak kierownicę · skręt do 45° · WYŚRODKUJ ustawia środek":"Tryb samochodowy wyłączony");
    }
    void centerSteering(){steering.reset();pad.aggregate();}
    void updateSteeringSensor(){
        if(sensors==null)return;
        sensors.unregisterListener(this);steeringListening=false;
        if(carMode && foreground && steeringSensor!=null)steeringListening=sensors.registerListener(this,steeringSensor,10000);
        if(carMode && foreground && !steeringListening)status("Nie można uruchomić czujnika przechylenia");
    }
    @Override public void onSensorChanged(SensorEvent event){
        if(!carMode || !steeringListening)return;
        steering.update(event.values[0],event.values[1],getWindowManager().getDefaultDisplay().getRotation(),event.timestamp);pad.aggregate();
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    void selectMode(boolean xbox){
        pad.release();
        if(androidHost()){status(androidStatus());return;}
        clearWindowsConnection();
        xboxMode=xbox;preferences.edit().putBoolean("xbox_mode",xbox).apply();pad.invalidate();
        if(host==null || !registered){status(xbox?"Wybrano Xbox · połącz komputer z odbiornikiem Pocket Pad":"Wybrano DirectInput · połącz komputer lub tablet");return;}
        syncMode(true);
    }
    void syncMode(boolean explicit){
        if(host==null || !registered || !foreground || !permissions())return;
        if(androidHost()){clearWindowsConnection();modeClient.cancel();status(androidStatus());return;}
        if(hostKind(host).isEmpty()){chooseHostKind(host,()->syncMode(explicit));return;}
        final BluetoothDevice device=host;
        final boolean wanted=xboxMode;
        final String address;
        try{address=device.getAddress();}catch(SecurityException ignored){return;}
        final boolean known=preferences.getStringSet("windows_receivers",Collections.emptySet()).contains(address);
        clearWindowsConnection();
        status(wanted?"Xbox · oczekiwanie na potwierdzenie z Windows…":"DirectInput · przełączanie odbiornika Windows…");
        modeClient.request(device,wanted,accepted->{
            if(destroyed || !foreground || !device.equals(host) || androidHost() || wanted!=xboxMode)return;
            if(accepted){
                boolean first=!windowsConnected();windowsReply(true);
                if(!first)return;
                Set<String> receivers=new HashSet<>(preferences.getStringSet("windows_receivers",Collections.emptySet()));receivers.add(address);
                preferences.edit().putStringSet("windows_receivers",receivers).apply();
                status("Windows odpowiada · "+name(device)+" · "+(wanted?"Xbox potwierdzony":"DirectInput potwierdzony"));
            }else{
                windowsReply(false);
                status("Windows: brak potwierdzonego połączenia · sprawdź odbiornik. Sam HID nie potwierdza działania komputera.");
                if(wanted || known){handler.removeCallbacks(retryMode);handler.postDelayed(retryMode,5000);}
            }
        });
    }
    String name(BluetoothDevice device){try {String n=device.getName();return n==null?device.getAddress():n;}catch(SecurityException e){return "tablet";}}
    void connectMenu(){
        if(host!=null && hostKind(host).isEmpty()){chooseHostKind(host,()->syncMode(true));return;}
        if(host!=null && !androidHost() && !windowsConnected()){
            new AlertDialog.Builder(this).setTitle("Windows: połączenie niepotwierdzone").setItems(new String[]{"Sprawdź odbiornik Windows","Rozłącz HID","Wybierz inne urządzenie","Typ urządzenia"},(d,i)->{
                if(i==3){chooseHostKind(host,()->syncMode(true));return;}
                if(i==0){syncMode(true);return;}
                clearWindowsConnection();modeClient.cancel();state.clear();send();try{if(hid!=null)hid.disconnect(host);}catch(SecurityException ignored){}host=null;pad.release();if(i==2)devices();
            }).show();return;
        }
        if(host!=null){new AlertDialog.Builder(this).setTitle(name(host)).setItems(new String[]{"Rozłącz","Wybierz inne urządzenie","Typ urządzenia"},(d,i)->{
            if(i==2){chooseHostKind(host,()->syncMode(true));return;}
            state.clear();send();try{hid.disconnect(host);}catch(SecurityException ignored){}if(i==1)devices();
        }).show();return;}
        choosing=true;prepare();
    }
    void prepare(){
        if(adapter==null){status("To urządzenie nie ma Bluetooth");return;}
        if(!permissions()) {requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE},10);return;}
        try {
            if(!adapter.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),11);return;}
            if(hid==null){
                if(proxyRequested)return;
                status("Uruchamianie pada Bluetooth…");
                proxyRequested=adapter.getProfileProxy(this,listener,BluetoothProfile.HID_DEVICE);
                if(!proxyRequested)status("Telefon nie obsługuje Bluetooth HID");
                else handler.postDelayed(()->{if(hid==null && foreground)status("Brak odpowiedzi HID · telefon może nie obsługiwać tej funkcji");},7000);
            }else if(registered){if(choosing){choosing=false;devices();}}
            else register();
        }catch(SecurityException e){status("Brak uprawnienia Bluetooth · przyznaj je w ustawieniach");}
    }
    void register(){
        if(registering || registered || hid==null || !foreground)return;
        try {
            registering=true;
            BluetoothHidDeviceAppSdpSettings sdp=new BluetoothHidDeviceAppSdpSettings("Pocket Pad","Bezprzewodowy pad do Androida","Pocket Pad",BluetoothHidDevice.SUBCLASS2_GAMEPAD,PadState.DESCRIPTOR);
            if(!hid.registerApp(sdp,null,null,getMainExecutor(),callback)){registering=false;status("Nie można uruchomić HID · zamknij inne aplikacje typu pad");}
        }catch(SecurityException | IllegalStateException e){registering=false;status("Nie udało się uruchomić Bluetooth HID");}
    }
    void devices(){
        if(!foreground || isFinishing())return;
        if(!registered){choosing=true;prepare();return;}
        try {
            ArrayList<BluetoothDevice> list=new ArrayList<>(adapter.getBondedDevices());
            list.sort((a,b)->name(a).compareToIgnoreCase(name(b)));
            String[] labels=new String[list.size()+1];
            labels[0]="+ Sparuj nowy tablet";
            for(int i=0;i<list.size();i++)labels[i+1]=name(list.get(i))+"\n"+list.get(i).getAddress();
            new AlertDialog.Builder(this).setTitle("Połącz z tabletem").setItems(labels,(dialog,item)->{
                if(item==0){pair();return;}
                BluetoothDevice target=list.get(item-1);
                if(hostKind(target).isEmpty())chooseHostKind(target,()->connectTarget(target));else connectTarget(target);
            }).setNegativeButton("Anuluj",null).show();
        }catch(SecurityException e){status("Przyznaj uprawnienie Urządzenia w pobliżu");}
    }
    void connectTarget(BluetoothDevice target){
        status("Łączenie z "+name(target)+"…");
        try {if(hid==null || !hid.connect(target))status("Nie udało się połączyć · sprawdź Bluetooth urządzenia");}
        catch(SecurityException e){status("Brak uprawnień Bluetooth");}
        handler.postDelayed(()->{if(host==null && foreground)status("Brak połączenia · sprawdź Bluetooth i parowanie urządzenia");},12000);
    }
    void pair(){
        new AlertDialog.Builder(this).setTitle("Parowanie z tabletem").setMessage("Za chwilę telefon będzie widoczny przez 120 sekund. Na TABLECIE otwórz Ustawienia → Bluetooth → Sparuj nowe urządzenie i wybierz ten telefon. Potwierdź kod na obu urządzeniach.\n\nNastępnie wróć tutaj i dotknij POŁĄCZ. Na tablecie nie instalujesz tej aplikacji.")
        .setPositiveButton("Udostępnij telefon",(d,w)->{try {
            Intent intent=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120);startActivityForResult(intent,12);
        }catch(SecurityException | ActivityNotFoundException e){status("Otwórz ustawienia Bluetooth i sparuj tablet ręcznie");}}).setNegativeButton("Anuluj",null).show();
    }
    void help(){new AlertDialog.Builder(this).setTitle("Pocket Pad 1.4 · większe przyciski")
        .setMessage("SAMOCHÓD: włącz AUTO i trzymaj telefon jak kierownicę. Obrót w lewo/prawo steruje osią X lewej gałki, w obu trybach pada. Pełny skręt uzyskasz przy 45°. WYŚRODKUJ ustawia bieżące położenie jako środek. Po powrocie do aplikacji środek ustawia się ponownie.\n\nPołącz telefon z komputerem lub tabletem przez Bluetooth. Istniejącego parowania nie trzeba usuwać.\n\nDIRECTINPUT: bezpośredni pad Bluetooth dla Androida, starszych gier i emulatorów Windows. Na tablecie nie potrzeba odbiornika.\n\nXBOX: przed grą uruchom PocketPadReceiver.exe na Windowsie. Odbiornik działa w zasobniku obok zegara. Dotknij Xbox na telefonie i poczekaj na potwierdzenie. Gry zobaczą pada Xbox 360.\n\nTryb wybierasz wyłącznie na telefonie, przed uruchomieniem gry. Zakończenie odbiornika Windows wyłącza emulację Xbox.\n\nPad działa, gdy ekran aplikacji na telefonie jest otwarty. L2/R2 są cyfrowe. Brak mapowania na dotyk dla gier bez obsługi kontrolerów. Komunikacja działa przez Bluetooth; internet nie jest używany.")
        .setPositiveButton("Rozumiem",null).setNeutralButton("Bluetooth",(d,w)->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))).show();}
    void send(){if(hid!=null && host!=null && registered)try{if(!hid.sendReport(host,PadState.REPORT_ID,state.report())){clearWindowsConnection();modeClient.cancel();status("Bluetooth HID nie przyjmuje danych · sprawdź połączenie");}}catch(SecurityException | IllegalStateException e){clearWindowsConnection();modeClient.cancel();host=null;status("Utracono połączenie Bluetooth");}}
    @Override protected void onResume(){super.onResume();foreground=true;steering.reset();updateSteeringSensor();handler.removeCallbacks(heartbeat);handler.post(heartbeat);if(permissions() && adapter!=null)try {if(adapter.isEnabled())prepare();}catch(SecurityException ignored){}if(host!=null && registered)handler.postDelayed(()->{if(foreground)syncMode(false);},600);}
    @Override protected void onPause(){clearWindowsConnection();modeClient.cancel();if(sensors!=null)sensors.unregisterListener(this);steeringListening=false;steering.reset();state.clear();if(pad!=null)pad.release();send();super.onPause();}
    @Override protected void onStop(){foreground=false;choosing=false;handler.removeCallbacks(heartbeat);state.clear();send();if(hid!=null)try{hid.unregisterApp();}catch(SecurityException ignored){}registered=false;registering=false;super.onStop();}
    @Override protected void onDestroy(){destroyed=true;modeClient.close();handler.removeCallbacksAndMessages(null);if(adapter!=null && hid!=null)adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE,hid);super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);if(code==10){if(permissions())prepare();else{choosing=false;status("Włącz uprawnienie „Urządzenia w pobliżu” w ustawieniach aplikacji");}}}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==11){if(result==RESULT_OK)prepare();else{choosing=false;status("Włącz Bluetooth, aby używać pada");}}}
}
