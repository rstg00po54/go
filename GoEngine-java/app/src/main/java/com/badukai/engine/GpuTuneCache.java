package com.badukai.engine;

import android.content.Context;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast, filesystem-only preflight for KataGo's two OpenCL tuning profiles.
 * The native tuner remains authoritative: it validates files and re-tunes if needed.
 */
public final class GpuTuneCache {
    // Keep the version in sync with OpenCLTuner::TUNER_VERSION.
    private static final Pattern TUNE_NAME = Pattern.compile(
            "^tune11_gpu([A-Za-z0-9]+)_x(\\d+)_y(\\d+)_c(128|384)_mv\\d+\\.txt$");
    public static final int MAIN_MODEL = 1, HUMAN_MODEL = 2, BOTH_MODELS = MAIN_MODEL | HUMAN_MODEL;

    private GpuTuneCache() {}

    /** Uses exactly the same homeDataDir choice as KataGoEngine.start(). */
    private static File tuningDirectory(Context context) {
        File filesDir = context.getApplicationContext().getFilesDir();
        File smokeHome = new File(filesDir, "jni_gpu_smoke/home");
        File home = smokeHome.isDirectory() ? smokeHome : new File(filesDir, "engine/gpu_home");
        return new File(home, "opencltuning");
    }

    /**
     * Returns a bit mask for the shipped 10b (128 channels) and Human SL
     * (384 channels) networks, requiring both files to belong to one GPU.
     * KataGo normally tunes for maximum 19x19 and reuses that on smaller boards.
     */
    public static int cachedModelMask(Context context, int boardSize) {
        File[] files = tuningDirectory(context).listFiles();
        if (files == null) return 0;
        Map<String, Integer> byGpu = new HashMap<>();
        for (File file : files) {
            if (!file.isFile() || file.length() <= 128) continue;
            Matcher m = TUNE_NAME.matcher(file.getName());
            if (!m.matches()) continue;
            int x = Integer.parseInt(m.group(2)), y = Integer.parseInt(m.group(3));
            if (x != y || (x != boardSize && x != 19)) continue;
            int bit = "128".equals(m.group(4)) ? MAIN_MODEL : HUMAN_MODEL;
            String gpu = m.group(1);
            int mask = byGpu.containsKey(gpu) ? byGpu.get(gpu) : 0;
            byGpu.put(gpu, mask | bit);
        }
        int best = 0;
        for (int mask : byGpu.values()) if (Integer.bitCount(mask) > Integer.bitCount(best)) best = mask;
        return best;
    }

    public static boolean needsTuning(Context context, int boardSize) {
        return cachedModelMask(context, boardSize) != BOTH_MODELS;
    }
}
