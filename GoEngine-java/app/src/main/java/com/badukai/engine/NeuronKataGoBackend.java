package com.badukai.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Actual MediaTek MDLA inference backend for the original 19x19 KataGo 10b network.
 *
 * Inputs must be the real KataGo NN feature tensors (not the raw GoBoard stones):
 * spatial [1, 22, 19, 19] in NCHW order and global [1, 19, 1, 1].
 *
 * Output order is: pass[1], policy[361], value[3], scoreValue[4], ownership[361].
 * Output tensors are FP32 arrays with original ONNX NCHW indexing. Computation uses
 * an internally retained FP16 Neuron graph, compiled once on the mtk-mdla device.
 *
 * This class is a callable NN bridge only. It does NOT replace the KataGo GTP
 * subprocess, generate NN input features, or implement Monte Carlo search.
 * Until the real KataGo native evaluator uses this bridge, GTP genmove is NOT accelerated.
 */
public final class NeuronKataGoBackend implements AutoCloseable {
    private static final String TAG = "NeuronKataGo";
    private static final String LIB_NAME = "katago_neuron_jni";
    private static final String ASSET_NAME = "neuron/katago10b_19x19.bin";
    public static final int BOARD_SIZE = 19;
    public static final int SPATIAL_FEATURES = 22 * BOARD_SIZE * BOARD_SIZE;
    public static final int GLOBAL_FEATURES = 19;
    private static final boolean LIB_LOADED;
    private static final String LIB_ERROR;

    static {
        boolean loaded = false;
        String error = "";
        try {
            System.loadLibrary(LIB_NAME);
            loaded = true;
        } catch (UnsatisfiedLinkError exception) {
            error = exception.toString();
            Log.w(TAG, "Neuron JNI library unavailable", exception);
        }
        LIB_LOADED = loaded;
        LIB_ERROR = error;
    }

    private long nativeHandle;

    private NeuronKataGoBackend(long handle) {
        nativeHandle = handle;
    }

    public static boolean isLibraryLoaded() {
        return LIB_LOADED;
    }

    public static String getLibraryError() {
        return LIB_ERROR;
    }

    /**
     * Load the original 10b NN packet (packaged in app assets), create the graph,
     * verify all 126 operations on the MDLA, and compile once.
     *
     * Throws if this is not an MTK MDLA device, model is absent or any op fails.
     * Do not call from Android's main/UI thread.
     */
    public static NeuronKataGoBackend open(Context context) throws IOException {
        if (!LIB_LOADED) throw new IllegalStateException("Neuron native backend unavailable: " + LIB_ERROR);
        File packet = installModel(context.getApplicationContext());
        long handle = nativeOpen(packet.getAbsolutePath());
        if (handle == 0) throw new IllegalStateException("Native KataGo MDLA session failed to initialize");
        Log.i(TAG, "Original KataGo 10b model ready on mtk-mdla: " + packet.getAbsolutePath());
        return new NeuronKataGoBackend(handle);
    }

    /**
     * Run one real NN evaluation. Call this off the UI thread. Not thread safe with
     * an external closer; synchronized methods guard normal Java callers.
     */
    public synchronized Result evaluate(float[] spatialNchw, float[] globalNchw) {
        if (nativeHandle == 0) throw new IllegalStateException("Neuron backend is closed");
        if (spatialNchw == null || spatialNchw.length != SPATIAL_FEATURES)
            throw new IllegalArgumentException("spatial must contain 7942 KataGo features [1,22,19,19]");
        if (globalNchw == null || globalNchw.length != GLOBAL_FEATURES)
            throw new IllegalArgumentException("global must contain 19 KataGo features");
        float[][] out = nativeEvaluate(nativeHandle, spatialNchw, globalNchw);
        if (out == null || out.length != 5 || out[0].length != 1 || out[1].length != 361 ||
                out[2].length != 3 || out[3].length != 4 || out[4].length != 361)
            throw new IllegalStateException("KataGo MDLA returned invalid NN head dimensions");
        return new Result(out[0], out[1], out[2], out[3], out[4]);
    }

    @Override
    public synchronized void close() {
        if (nativeHandle != 0) {
            nativeClose(nativeHandle);
            nativeHandle = 0;
        }
    }

    public static final class Result {
        public final float[] policyPass;
        public final float[] policy;
        public final float[] value;
        public final float[] scoreValue;
        public final float[] ownership;

        private Result(float[] policyPass, float[] policy, float[] value,
                       float[] scoreValue, float[] ownership) {
            this.policyPass = policyPass;
            this.policy = policy;
            this.value = value;
            this.scoreValue = scoreValue;
            this.ownership = ownership;
        }
    }

    private static File installModel(Context context) throws IOException {
        File folder = new File(context.getFilesDir(), "neuron");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create Neuron model directory");
        File output = new File(folder, "katago10b_19x19.bin");
        long length;
        try (InputStream input = context.getAssets().open(ASSET_NAME)) {
            // Opening the asset here deliberately verifies it was explicitly packaged.
            length = input.available();
        } catch (IOException missing) {
            throw new IOException("KataGo NPU model asset missing: " + ASSET_NAME +
                    ". Run MiniGoCpp/tools/prepare_goengine_neuron.sh first.", missing);
        }
        if (length > 0 && output.isFile() && output.length() == length) return output;
        File temp = new File(folder, "katago10b_19x19.bin.tmp");
        try (InputStream input = context.getAssets().open(ASSET_NAME);
             FileOutputStream stream = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) stream.write(buffer, 0, read);
            stream.getFD().sync();
        }
        if (output.exists() && !output.delete()) throw new IOException("Cannot replace old Neuron packet");
        if (!temp.renameTo(output)) throw new IOException("Cannot install Neuron packet");
        return output;
    }

    private static native long nativeOpen(String packetPath);
    private static native float[][] nativeEvaluate(long nativeHandle, float[] spatialNchw, float[] globalNchw);
    private static native void nativeClose(long nativeHandle);
}
