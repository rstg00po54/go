package com.badukai.java;

import android.content.*;
import java.io.*;
import java.util.*;

public class EngineManager {
    private static volatile EngineManager INSTANCE;
    public static EngineManager get(Context c) {
        if (INSTANCE == null) synchronized (EngineManager.class) {
            if (INSTANCE == null) INSTANCE = new EngineManager(c.getApplicationContext());
        }
        return INSTANCE;
    }
    private final Context context;
    private final EngineBootstrap bootstrap;
    private final SharedPreferences prefs;
    private GtpClient client;
    private EngineConfig config;

    private EngineManager(Context context) {
        this.context = context; bootstrap = new EngineBootstrap(context);
        prefs = context.getSharedPreferences("engine_settings", Context.MODE_PRIVATE);
    }
    public synchronized EngineConfig load() throws IOException {
        bootstrap.ensureExtracted();
        EngineConfig c = new EngineConfig();
        c.executablePath = prefs.getString("exe", bootstrap.executableFile.getAbsolutePath());
        c.modelPath = prefs.getString("model", "");
        c.configPath = prefs.getString("cfg", bootstrap.configFile.getAbsolutePath());
        c.threads = prefs.getInt("threads", 2); c.visits = prefs.getInt("visits", 800); c.komi = prefs.getFloat("komi", 7.5f);
        config = c; return c;
    }
    public synchronized void save(EngineConfig c) {
        prefs.edit().putString("exe", c.executablePath).putString("model", c.modelPath).putString("cfg", c.configPath)
                .putInt("threads", c.threads).putInt("visits", c.visits).putFloat("komi", c.komi).apply();
        config = c;
    }
    public synchronized EngineConfig config() throws IOException { return config != null ? config : load(); }
    public synchronized GtpClient start(int boardSize) throws Exception {
        EngineConfig c = config();
        if (!c.isReady()) throw new IllegalStateException("请先在设置中选择模型文件");
        if (client != null && client.isAlive()) return client;
        ArrayList<String> args = new ArrayList<>();
        args.add("gtp"); args.add("-model"); args.add(c.modelPath); args.add("-config"); args.add(c.configPath);
        args.add("-override-config"); args.add("numSearchThreads="+c.threads+",maxVisits="+c.visits);
        client = new GtpClient(c.executablePath, bootstrap.engineDir, args);
        must(client.send("boardsize " + boardSize)); must(client.send("clear_board")); must(client.send("komi " + c.komi));
        return client;
    }
    private static void must(GtpClient.Response r) throws IOException { if (!r.success) throw new IOException(r.text); }
    public synchronized void stop() { if (client != null) { client.close(); client = null; } }
    public synchronized boolean isReady() { try { return config().isReady(); } catch (Exception e) { return false; } }
}
