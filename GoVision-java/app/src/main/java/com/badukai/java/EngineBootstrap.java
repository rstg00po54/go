package com.badukai.java;

import android.content.Context;
import java.io.*;

public class EngineBootstrap {
    private final Context context;
    public final File engineDir;
    public final File executableFile;
    public final File libcxxFile;
    public final File configFile;

    public EngineBootstrap(Context context) {
        this.context = context.getApplicationContext();
        engineDir = new File(context.getFilesDir(), "engine"); engineDir.mkdirs();
        executableFile = new File(engineDir, "katago");
        libcxxFile = new File(engineDir, "libc++_shared.so");
        configFile = new File(engineDir, "default_gtp.cfg");
    }
    public void ensureExtracted() throws IOException {
        copy("engine/katago", executableFile); executableFile.setExecutable(true, false);
        copy("engine/libc++_shared.so", libcxxFile); libcxxFile.setExecutable(false, false);
        copy("engine/default_gtp.cfg", configFile);
    }
    private void copy(String asset, File dst) throws IOException {
        if (dst.exists() && dst.length() > 0) return;
        try (InputStream in = context.getAssets().open(asset); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
