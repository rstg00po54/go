package com.badukai.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;

/** Reports completed native OpenCL startup phases from the active KataGo GTP log. */
public final class GpuStartupLogProgress implements AutoCloseable {
    private static final String TAG = "GpuStartupLogProgress";
    public interface Listener { void onPhase(int percent, String stage); }

    private final File directory;
    private final long startedAt;
    private final Listener listener;
    private final Thread worker;
    private volatile boolean running = true;

    public GpuStartupLogProgress(Context context, Listener listener) {
        this.directory = new File(context.getFilesDir(), "engine/gtp_logs");
        this.startedAt = System.currentTimeMillis();
        this.listener = listener;
        this.worker = new Thread(this::poll, "KataGo-GPU-startup-progress");
        worker.setDaemon(true);
        worker.start();
    }

    private void poll() {
        File reading = null;
        long offset = 0;
        while (running) {
            try {
                File latest = latestLog();
                if (latest != null) {
                    if (!latest.equals(reading)) { reading = latest; offset = 0; }
                    try (RandomAccessFile input = new RandomAccessFile(reading, "r")) {
                        if (input.length() < offset) offset = 0;
                        input.seek(offset);
                        String line;
                        while (running && (line = input.readLine()) != null) {
                            if (line.contains("OPENCL_TIMING")) dispatch(line);
                        }
                        offset = input.getFilePointer();
                    }
                }
                Thread.sleep(240);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running) Log.w(TAG, "Cannot read GPU startup stage", e);
                try { Thread.sleep(800); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private File latestLog() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".log"));
        File newest = null;
        if (files == null) return null;
        for (File f : files) {
            if (f.lastModified() < startedAt - 1000) continue;
            if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
        }
        return newest;
    }

    private void dispatch(String line) {
        boolean human = line.contains("b18c384nbt-humanv0");
        int percent = -1;
        String detail = null;
        if (line.contains("phase=model_file_parse")) {
            percent = human ? 55 : 16;
            detail = human ? "Human SL 模型解析完成" : "10b 模型解析完成";
        } else if (line.contains("phase=context_init")) {
            percent = human ? 59 : 19;
            detail = human ? "正在初始化 Human SL GPU" : "正在初始化 OpenCL";
        } else if (line.contains("phase=tuning_lookup_or_autotune")) {
            percent = human ? 75 : 34;
            detail = human ? "Human SL 调优参数已就绪" : "10b 调优参数已就绪";
        } else if (line.contains("phase=kernel_program_build")) {
            percent = human ? 85 : 42;
            detail = human ? "Human SL 内核已加载" : "10b OpenCL 内核已加载";
        } else if (line.contains("phase=worker_startup_total")) {
            percent = human ? 94 : 49;
            detail = human ? "Human SL 权重加载完成" : "10b 权重加载完成";
        }
        if (percent >= 0) listener.onPhase(percent,detail);
    }

    @Override public void close() {
        running = false;
        worker.interrupt();
    }
}
