package com.badukai.engine;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;

/** Isolated OpenCL JNI worker. Runs only in the Android :katago_gpu process. */
public final class GpuGtpService extends Service {
    private static final String TAG = "GpuGtpService";

    static final int START = 1, SEND = 2, STOP = 3;
    static final int STARTED = 10, OUTPUT = 11, FAILED = 12, STOPPED = 13;
    static final String MODEL = "model", CONFIG = "config", HUMAN = "human", COMMAND = "command", DATA = "data";

    private HandlerThread worker;
    private Handler handler;
    private Messenger client;
    private KataGoNative.GtpSession session;
    private volatile boolean closing;

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("KataGo-GPU-Service");
        worker.start();
        handler = new Handler(worker.getLooper(), message -> {
            if (message.what == START) startSession(message);
            else if (message.what == SEND) sendCommand(message.getData().getString(COMMAND));
            else if (message.what == STOP) stopSession();
            return true;
        });
    }

    @Override public IBinder onBind(Intent intent) { return new Messenger(handler).getBinder(); }

    @Override public boolean onUnbind(Intent intent) {
        handler.sendEmptyMessage(STOP);
        return false;
    }

    private void startSession(Message message) {
        client = message.replyTo;
        if (session != null) { notifyClient(FAILED, "GPU JNI service already has a session"); return; }
        try {
            Bundle args = message.getData();
            String model = args.getString(MODEL), config = args.getString(CONFIG);
            if (model == null || config == null || !new File(model).isFile() || !new File(config).isFile())
                throw new IllegalArgumentException("GPU model/config missing");
            String human = args.getString(HUMAN);
            if (human != null && !new File(human).isFile()) throw new IllegalArgumentException("Human SL model missing");
            KataGoNative.selectIsolatedGpuLibrary();
            session = KataGoNative.createSession(new File(model), new File(config),
                    human == null ? null : new File(human));
            if (session == null) throw new IllegalStateException("GPU JNI createSession returned null");
            closing = false;
            final KataGoNative.GtpSession started = session;
            new Thread(() -> readOutput(started), "KataGo-GPU-GTP-reader").start();
            Log.i(TAG, "GPU/OpenCL JNI session created, humanSL=" + (human != null));
            notifyClient(STARTED, "GPU/OpenCL JNI session created");
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "GPU JNI startup failed", e);
            notifyClient(FAILED, e.toString());
            stopSession();
        }
    }

    private void readOutput(KataGoNative.GtpSession original) {
        try {
            while (!closing && session == original) {
                String output = original.read(1000);
                if (output == null) break;
                // Binder messages must stay far below the IPC transaction size limit.
                for (int i = 0; i < output.length(); i += 32768)
                    notifyClient(OUTPUT, output.substring(i, Math.min(i + 32768, output.length())));
            }
            if (!closing) notifyClient(FAILED, "GPU GTP worker exited unexpectedly");
        } catch (Exception | LinkageError e) {
            if (!closing) { Log.e(TAG, "GPU JNI output reader failed", e); notifyClient(FAILED, e.toString()); }
        }
    }

    private void sendCommand(String command) {
        if (session == null || command == null || !session.send(command))
            notifyClient(FAILED, "GPU GTP send failed: " + command);
    }

    private void notifyClient(int what, String data) {
        Messenger target = client;
        if (target == null) return;
        Message reply = Message.obtain(null, what);
        Bundle payload = new Bundle();
        payload.putString(DATA, data);
        reply.setData(payload);
        try { target.send(reply); }
        catch (RemoteException e) { Log.w(TAG, "GPU JNI client disconnected", e); }
    }

    private void stopSession() {
        closing = true;
        KataGoNative.GtpSession old = session;
        session = null;
        if (old != null) {
            try { old.close(); } catch (Exception | LinkageError e) { Log.w(TAG, "GPU JNI close failed", e); }
        }
        notifyClient(STOPPED, "GPU JNI session closed");
        client = null;
    }

    @Override public void onDestroy() {
        // Stop JNI on the service worker before quitting its Looper.
        if (worker != null) {
            handler.post(this::stopSession);
            worker.quitSafely();
        }
        super.onDestroy();
    }
}
