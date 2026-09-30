package com.badukai.java;

import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GtpClient implements Closeable {
    public static class Response {
        public final boolean success; public final String text;
        public Response(boolean success, String text) { this.success = success; this.text = text; }
    }
    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;
    private final Thread stderrThread;

    public GtpClient(String executable, File workingDir, List<String> args) throws IOException {
        ArrayList<String> cmd = new ArrayList<>(); cmd.add(executable); cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workingDir).redirectErrorStream(false);
        String old = pb.environment().get("LD_LIBRARY_PATH");
        pb.environment().put("LD_LIBRARY_PATH", workingDir.getAbsolutePath() + (old == null || old.isEmpty() ? "" : ":" + old));
        process = pb.start();
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stderrThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) Log.i("KataGo", line);
            } catch (IOException ignored) {}
        }, "katago-stderr");
        stderrThread.start();
    }

    public synchronized Response send(String command) throws IOException {
        Log.d("GtpClient", "> " + command);
        stdin.write(command); stdin.newLine(); stdin.flush();
        StringBuilder sb = new StringBuilder(); boolean gotHeader = false, ok = false;
        while (true) {
            String line = stdout.readLine();
            if (line == null) throw new EOFException("KataGo 已退出");
            Log.d("GtpClient", "< " + line);
            if (!gotHeader) {
                if (line.startsWith("=") || line.startsWith("?")) {
                    gotHeader = true; ok = line.startsWith("=");
                    String t = line.substring(1).trim(); if (!t.isEmpty()) sb.append(t);
                } else {
                    // KataGo analyze info may appear before final GTP header; keep it for parser.
                    if (!line.trim().isEmpty()) { if (sb.length() > 0) sb.append('\n'); sb.append(line); }
                }
            } else if (line.isEmpty()) break;
            else { if (sb.length() > 0) sb.append('\n'); sb.append(line); }
        }
        return new Response(ok, sb.toString());
    }
    public boolean isAlive() { return process.isAlive(); }
    @Override public void close() {
        try { if (process.isAlive()) send("quit"); } catch (Exception ignored) {}
        process.destroy();
    }
}
