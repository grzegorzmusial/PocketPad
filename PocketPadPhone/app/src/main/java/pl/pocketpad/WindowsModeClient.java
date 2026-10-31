package pl.pocketpad;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Authenticated mode selection followed by a liveness channel; HID stays unchanged. */
@SuppressLint("MissingPermission")
final class WindowsModeClient implements AutoCloseable {
    interface Callback { void complete(boolean accepted); }
    private final Handler main;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    private volatile BluetoothSocket active;
    WindowsModeClient(Handler main){this.main=main;}
    void cancel(){generation.incrementAndGet();BluetoothSocket socket=active;active=null;closeSocket(socket);}
    void request(BluetoothDevice device,boolean xbox,Callback callback){
        cancel();
        final int ticket=generation.get();
        worker.execute(()->{
            if(ticket!=generation.get())return;
            BluetoothSocket socket=null;
            Runnable timeout=null;
            boolean ok=false;
            try {
                socket=device.createRfcommSocketToServiceRecord(UUID.fromString(ModeProtocol.SERVICE_UUID));
                final BluetoothSocket connection=socket;
                active=connection;
                if(ticket!=generation.get())return;
                timeout=()->closeSocket(connection);
                main.postDelayed(timeout,12000);
                connection.connect();
                OutputStream output=connection.getOutputStream();
                output.write(ModeProtocol.command(xbox).getBytes(StandardCharsets.US_ASCII));output.flush();
                InputStream input=connection.getInputStream();
                ok=ModeProtocol.accepted(xbox,readLine(input));
                main.removeCallbacks(timeout);
                if(ok){
                    main.post(()->{if(ticket==generation.get())callback.complete(true);});
                    while(ticket==generation.get()){
                        Thread.sleep(1000);
                        main.postDelayed(timeout,5000);
                        output.write("POCKETPAD/1 ALIVE\n".getBytes(StandardCharsets.US_ASCII));output.flush();
                        if(!"OK ALIVE".equals(readLine(input)))break;
                        main.removeCallbacks(timeout);
                        main.post(()->{if(ticket==generation.get())callback.complete(true);});
                    }
                }
            }catch(IOException|SecurityException|InterruptedException ignored){
                // The activity presents a precise, mode-specific status without claiming activation.
            }finally{
                if(timeout!=null)main.removeCallbacks(timeout);
                if(active==socket)active=null;
                closeSocket(socket);
            }
            if(ticket==generation.get())main.post(()->{if(ticket==generation.get())callback.complete(false);});
        });
    }
    private static String readLine(InputStream input)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int i=0;i<80;i++){int value=input.read();if(value<0)throw new EOFException();if(value=='\n')return new String(bytes.toByteArray(),StandardCharsets.US_ASCII);if(value!='\r')bytes.write(value);}
        throw new IOException("Response too long");
    }
    private static void closeSocket(BluetoothSocket socket){if(socket!=null)try{socket.close();}catch(IOException ignored){}}
    @Override public void close(){cancel();worker.shutdownNow();}
}
