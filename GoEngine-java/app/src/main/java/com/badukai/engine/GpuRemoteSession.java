package com.badukai.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * GTP transport in the main APP process. The OpenCL JNI engine stays inside
 * :katago_gpu; replies are streamed back into KataGoEngine's existing GTP parser.
 */
public final class GpuRemoteSession implements AutoCloseable {
    private static final String TAG = "GpuRemoteSession";
    private final Context context;
    private final HandlerThread repliesThread = new HandlerThread("KataGo-GPU-IPC-replies");
    private final CountDownLatch started = new CountDownLatch(1);
    private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
    private final File model, config, human;
    private final ServiceConnection connection;
    private Messenger remote, receiver;
    private volatile boolean alive, closed, bound;
    private volatile String error;
    private final long connectStartNs = System.nanoTime();
    private volatile long bindRequestNs, callbackNs, startSentNs;

    private static void startupTiming(String phase, long startNs) {
        Log.i(TAG, "STARTUP_TIMING phase=" + phase + " ms=" + (System.nanoTime() - startNs) / 1000000.0);
    }

    private GpuRemoteSession(Context context, File model, File config, File human) {
        this.context = context.getApplicationContext();
        this.model = model;
        this.config = config;
        this.human = human;
        repliesThread.start();
        receiver = new Messenger(new Handler(repliesThread.getLooper(), this::handleReply));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                callbackNs = System.nanoTime();
                startupTiming("gpu_service_bind_to_callback", bindRequestNs);
                if (closed) return;
                remote = new Messenger(binder);
                Message message = Message.obtain(null, GpuGtpService.START);
                Bundle args = new Bundle();
                args.putString(GpuGtpService.MODEL, GpuRemoteSession.this.model.getAbsolutePath());
                args.putString(GpuGtpService.CONFIG, GpuRemoteSession.this.config.getAbsolutePath());
                if (GpuRemoteSession.this.human != null)
                    args.putString(GpuGtpService.HUMAN, GpuRemoteSession.this.human.getAbsolutePath());
                message.setData(args);
                message.replyTo = receiver;
                try {
                    remote.send(message);
                    startSentNs = System.nanoTime();
                    startupTiming("gpu_service_start_ipc_send", callbackNs);
                } catch (RemoteException e) { fail("GPU JNI start IPC failed: " + e); }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                fail("GPU JNI service disconnected");
            }

            @Override public void onBindingDied(ComponentName name) {
                fail("GPU JNI service binding died");
            }
        };
    }

    public static GpuRemoteSession connect(Context context, File model, File config, File human) throws Exception {
        GpuRemoteSession session = new GpuRemoteSession(context, model, config, human);
        try {
            startupTiming("gpu_remote_session_setup", session.connectStartNs);
            Intent intent = new Intent(context, GpuGtpService.class);
            session.bindRequestNs = System.nanoTime();
            boolean bound = session.context.bindService(intent, session.connection, Context.BIND_AUTO_CREATE);
            startupTiming("gpu_service_bind_call", session.bindRequestNs);
            if (!bound) throw new IllegalStateException("Cannot bind GPU JNI service (is GPU JNI APK enabled?)");
            session.bound = true;
            long awaitNs = System.nanoTime();
            boolean started = session.started.await(20000, TimeUnit.MILLISECONDS);
            startupTiming("gpu_service_wait_started", awaitNs);
            if (!started) throw new IllegalStateException("Timed out connecting to GPU JNI service");
            if (!session.alive) throw new IllegalStateException(session.error == null ? "GPU JNI service failed" : session.error);
            startupTiming("gpu_remote_connect_total", session.connectStartNs);
            Log.i(TAG, "Connected to GPU/OpenCL JNI service");
            return session;
        } catch (Exception e) {
            startupTiming("gpu_remote_connect_failed", session.connectStartNs);
            session.close();
            throw e;
        }
    }

    private boolean handleReply(Message message) {
        switch (message.what) {
            case GpuGtpService.STARTED:
                startupTiming("gpu_service_ipc_started_reply", startSentNs > 0 ? startSentNs : connectStartNs);
                startupTiming("gpu_service_bind_to_ready", bindRequestNs > 0 ? bindRequestNs : connectStartNs);
                alive = true;
                started.countDown();
                break;
            case GpuGtpService.OUTPUT:
                String chunk = message.getData().getString(GpuGtpService.DATA);
                if (chunk != null && !chunk.isEmpty()) output.offer(chunk);
                break;
            case GpuGtpService.FAILED:
                fail(message.getData().getString(GpuGtpService.DATA));
                break;
            case GpuGtpService.STOPPED:
                if (!closed) fail("GPU JNI service stopped");
                break;
            default:
                break;
        }
        return true;
    }

    private void fail(String why) {
        if (closed) return;
        Log.e(TAG, why);
        error = why;
        alive = false;
        started.countDown();
        // Wake any ongoing read; subsequent read sees !alive and returns EOF.
        output.offer("");
    }

    public boolean isAlive() { return !closed && alive; }

    public boolean send(String command) {
        Messenger target = remote;
        if (!isAlive() || target == null) return false;
        Message message = Message.obtain(null, GpuGtpService.SEND);
        Bundle args = new Bundle();
        args.putString(GpuGtpService.COMMAND, command);
        message.setData(args);
        try { target.send(message); return true; }
        catch (RemoteException e) { fail("GPU JNI send IPC failed: " + e); return false; }
    }

    public String read(int timeoutMs) throws InterruptedException {
        String data = output.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (data != null && !data.isEmpty()) return data;
        return isAlive() ? "" : null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        alive = false;
        Messenger target = remote;
        if (target != null) {
            try { target.send(Message.obtain(null, GpuGtpService.STOP)); }
            catch (RemoteException ignored) {}
        }
        if (bound) {
            try { context.unbindService(connection); }
            catch (IllegalArgumentException ignored) {}
            bound = false;
        }
        output.offer("");
        repliesThread.quitSafely();
    }
}
