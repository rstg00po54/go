package com.badukai.engine;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.TensorBuffer;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import java.util.List;
import java.util.Locale;

/**
 * Experimental MediaTek NeuroPilot/LiteRT runner for the *actual* KataGo g170
 * 10-block graph. 3 feature tensors in, 5 heads out. This class is a runtime
 * probe and reusable model session, not yet wired into KataGo's GTP NN server.
 *
 * Note: Accelerator.NPU is a request, NOT proof of NPU execution: LiteRT may
 * partially or completely fall back to CPU when a vendor plugin is absent or
 * an operator cannot be compiled. Device-side delegate/partition traces are
 * required before claiming hardware acceleration.
 */
public final class KataGoMediaTekLiteRt implements AutoCloseable {
    private static final String TAG = "KataGoMTK";
    private static final String[] INPUT = {"InputSpatial", "InputGlobal", "InputMask"};
    private static final String[] OUTPUT = {"OutputPolicyPass", "OutputPolicy", "OutputValue",
            "OutputScoreValue", "OutputOwnership"};
    private static final int[] CHANNELS = {22, 19, 1};
    private final Environment environment;
    private final CompiledModel model;
    private final List<TensorBuffer> inputs;
    private final List<TensorBuffer> outputs;
    private final String[] inputNames;
    private final String[] outputNames;
    private final int[] inputCounts;
    private final int[] outputCounts;
    private final int[] inputPositions;
    private final int[] outputPositions;
    private final boolean spatialIsNchw;
    private final int size;

    public static boolean isMediaTek() {
        String desc = (Build.HARDWARE + " " + Build.BOARD + " " + Build.SOC_MANUFACTURER
                + " " + Build.SOC_MODEL).toLowerCase(Locale.US);
        return desc.contains("mediatek") || desc.contains("mt6990") || desc.contains("mtk");
    }

    /** Requires an independently verified per-board TFLite graph + JSON. */
    public KataGoMediaTekLiteRt(Context context, File file, File json, int board) throws Exception {
        if (!isMediaTek() || Build.VERSION.SDK_INT < 31)
            throw new IllegalStateException("MediaTek Android 12+ NPU required");
        if (!file.isFile() || !json.isFile())
            throw new IllegalArgumentException("KataGo LiteRT graph/manifest missing");
        if (board != 9 && board != 13 && board != 19)
            throw new IllegalArgumentException("Unsupported fixed KataGo graph size");
        size = board;
        JSONObject metadata = new JSONObject(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8));
        if (metadata.getInt("size") != board || !metadata.getString("weights").equals(
                "g170-b10c128-s1141046784-d204142634") ||
                !"PASS".equals(metadata.getString("cpu_inference")))
            throw new IllegalArgumentException("KataGo graph is not validated for this board/weights");
        inputNames = new String[INPUT.length];
        outputNames = new String[OUTPUT.length];
        inputCounts = new int[INPUT.length];
        outputCounts = new int[OUTPUT.length];
        inputPositions = new int[INPUT.length];
        outputPositions = new int[OUTPUT.length];
        JSONArray inputOrder = metadata.getJSONArray("inputOrder");
        JSONArray outputOrder = metadata.getJSONArray("outputOrder");
        if (inputOrder.length() != 3 || outputOrder.length() != 5)
            throw new IllegalArgumentException("Invalid LiteRT buffer order in manifest");
        for (int i=0;i<INPUT.length;i++) {
            inputPositions[i] = -1;
            for (int pos=0;pos<3;pos++)
                if (INPUT[i].equals(inputOrder.getString(pos))) inputPositions[i] = pos;
            if (inputPositions[i] < 0) throw new IllegalArgumentException("Missing LiteRT input " + INPUT[i]);
        }
        for (int i=0;i<OUTPUT.length;i++) {
            outputPositions[i] = -1;
            for (int pos=0;pos<5;pos++)
                if (OUTPUT[i].equals(outputOrder.getString(pos))) outputPositions[i] = pos;
            if (outputPositions[i] < 0) throw new IllegalArgumentException("Missing LiteRT output " + OUTPUT[i]);
        }
        JSONObject rawInputs = metadata.getJSONObject("inputs");
        JSONObject rawOutputs = metadata.getJSONObject("outputs");
        for (int i=0;i<INPUT.length;i++) {
            JSONObject item = rawInputs.getJSONObject(INPUT[i]);
            inputNames[i] = item.getString("name");
            inputCounts[i] = item.getInt("count");
        }
        for (int i=0;i<OUTPUT.length;i++) {
            JSONObject item = rawOutputs.getJSONObject(OUTPUT[i]);
            outputNames[i] = item.getString("name");
            outputCounts[i] = item.getInt("count");
        }
        JSONArray spatialShape = rawInputs.getJSONObject("InputSpatial").getJSONArray("shape");
        spatialIsNchw = spatialShape.getInt(1) == 22 && spatialShape.getInt(2) == board;
        if (inputCounts[0] != board * board * CHANNELS[0] || inputCounts[1] != 19 ||
                inputCounts[2] != board * board ||
                outputCounts[0] != 1 || outputCounts[1] != board * board ||
                outputCounts[2] != 3 || outputCounts[3] != 4 ||
                outputCounts[4] != board * board)
            throw new IllegalArgumentException("KataGo LiteRT tensor contract mismatch");

        BuiltinNpuAcceleratorProvider provider = new BuiltinNpuAcceleratorProvider(context);
        if (!provider.isDeviceSupported() || !provider.isLibraryReady())
            throw new IllegalStateException("MediaTek NPU vendor plugin not available in APK");
        Environment candidateEnv = Environment.create(context, provider, Collections.emptyMap(), true);
        CompiledModel candidate = null;
        List<TensorBuffer> in = null, out = null;
        try {
            if (!candidateEnv.getAvailableAccelerators().contains(Accelerator.NPU))
                throw new IllegalStateException("LiteRT NPU accelerator unavailable");
            candidate = CompiledModel.create(file.getAbsolutePath(),
                    new CompiledModel.Options(Accelerator.NPU), candidateEnv);
            in = candidate.createInputBuffers();
            out = candidate.createOutputBuffers();
            if (in.size() != 3 || out.size() != 5)
                throw new IllegalStateException("LiteRT created wrong tensor buffer count");
        } catch (Exception | LinkageError ex) {
            if (in != null) for (TensorBuffer buffer : in) buffer.close();
            if (out != null) for (TensorBuffer buffer : out) buffer.close();
            if (candidate != null) candidate.close();
            candidateEnv.close();
            throw ex;
        }
        environment = candidateEnv;
        model = candidate;
        inputs = in;
        outputs = out;
        Log.i(TAG, "MTK_LITERT_INIT board=" + board + " npuRequested=true graph=" + file.getName());
    }

    /**
     * Caller supplies NHWC KataGo feature tensors. Uses exactly the 5 original
     * head semantics, without remapping the value/ownership head contents.
     */
    public synchronized float[][] infer(float[] spatial, float[] global, float[] mask) throws Exception {
        float[][] values = {spatial, global, mask};
        for (int i=0;i<3;i++) {
            if (values[i].length != inputCounts[i])
                throw new IllegalArgumentException("Incorrect tensor length for " + INPUT[i]);
            float[] input = values[i];
            if (i == 0 && spatialIsNchw) {
                input = new float[spatial.length];
                for (int c=0;c<22;c++)
                    for (int p=0;p<size*size;p++) input[c*size*size+p] = spatial[p*22+c];
            }
            inputs.get(inputPositions[i]).writeFloat(input);
        }
        long start = SystemClock.elapsedRealtimeNanos();
        model.run(inputs, outputs);
        float[][] result = new float[5][];
        for (int i=0;i<5;i++) {
            result[i] = outputs.get(outputPositions[i]).readFloat();
            if (result[i].length != outputCounts[i])
                throw new IllegalStateException("Unexpected output shape for " + OUTPUT[i]);
            for (float v : result[i])
                if (!Float.isFinite(v)) throw new IllegalStateException("NaN/Inf from " + OUTPUT[i]);
        }
        long elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1000000L;
        Log.i(TAG, "MTK_LITERT_INFER board=" + size + " ms=" + elapsedMs
                + " npuRequested=true hardwareExecution=UNVERIFIED");
        return result;
    }

    public synchronized String smokeOnce() throws Exception {
        float[] spatial = new float[inputCounts[0]];
        float[] global = new float[19];
        float[] mask = new float[size * size];
        Arrays.fill(mask, 1.0f);
        // Mask channel 0 is also present in every spatial location.
        for (int i=0; i<size * size; i++) spatial[i * 22] = 1.0f;
        float[][] output = infer(spatial, global, mask);
        return "MTK_LITERT_SMOKE board=" + size + " heads=" + output.length
                + " policyPass=" + output[0][0] + " hardwareExecution=UNVERIFIED";
    }

    @Override public synchronized void close() {
        for (TensorBuffer buffer : inputs) buffer.close();
        for (TensorBuffer buffer : outputs) buffer.close();
        model.close();
        environment.close();
    }
}
