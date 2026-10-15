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
 * 10-block graph or separately validated Human SL b18 graph. The Human graph
 * requires InputMeta with 192 SGF features, provided by native KataGo.
 *
 * Note: Accelerator.NPU is a request, NOT proof of NPU execution: LiteRT may
 * partially or completely fall back to CPU when a vendor plugin is absent or
 * an operator cannot be compiled. Device-side delegate/partition traces are
 * required before claiming hardware acceleration.
 */
public final class KataGoMediaTekLiteRt implements AutoCloseable {
    private static final String TAG = "KataGoMTK";
    private static final String[] INPUT = {"InputSpatial", "InputGlobal", "InputMask"};
    private static final String[] INPUT_HUMAN = {"InputSpatial", "InputGlobal", "InputMeta", "InputMask"};
    private static final String[] OUTPUT = {"OutputPolicyPass", "OutputPolicy", "OutputValue",
            "OutputScoreValue", "OutputOwnership"};
    private static final int[] CHANNELS = {22, 19, 1};
    private final boolean humanModel;
    private final String[] inputKeys;
    private final int policyChannels;
    private final int metaChannels;
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
    private final float outputScaleMultiplier;

    public static boolean isMediaTek() { return NpuHardware.canProbeMediaTekLiteRt(); }

    /** Existing g170 caller; do not change its deployed tensor contract. */
    public KataGoMediaTekLiteRt(Context context, File file, File json, int board) throws Exception {
        this(context, file, json, board, false);
    }

    /** Human model is explicitly opted-in only after exact graph/manifest validation. */
    public KataGoMediaTekLiteRt(Context context, File file, File json, int board,
                                boolean human) throws Exception {
        if (!isMediaTek() || Build.VERSION.SDK_INT < 31)
            throw new IllegalStateException("MediaTek Android 12+ NPU required");
        if (!file.isFile() || !json.isFile())
            throw new IllegalArgumentException("KataGo LiteRT graph/manifest missing");
        if (board != 9 && board != 13 && board != 19)
            throw new IllegalArgumentException("Unsupported fixed KataGo graph size");
        size = board;
        JSONObject metadata = new JSONObject(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8));
        humanModel = human;
        inputKeys = human ? INPUT_HUMAN : INPUT;
        String expectedWeights = human ? "b18c384nbt-humanv0" :
                "g170-b10c128-s1141046784-d204142634";
        if (metadata.getInt("size") != board || !expectedWeights.equals(metadata.getString("weights"))
                || !"PASS".equals(metadata.getString("cpu_inference")))
            throw new IllegalArgumentException("KataGo graph is not validated for this board/weights");
        if (human && (!"human".equals(metadata.optString("modelType"))
                || metadata.optInt("metaEncoderVersion") != 1
                || metadata.optInt("numInputMetaChannels") != 192
                || metadata.optInt("numInputChannels") != 22
                || metadata.optInt("numInputGlobalChannels") != 19
                || metadata.optInt("numScoreValueChannels") != 6
                || metadata.optInt("numValueChannels") != 3
                || metadata.optInt("numOwnershipChannels") != 1
                || !metadata.has("graphSha256")))
            throw new IllegalArgumentException("Human SGF metadata / original weights contract missing");
        policyChannels = human ? metadata.getInt("numPolicyChannels") : 1;
        metaChannels = human ? 192 : 0;
        if (policyChannels < 1 || policyChannels > 4 || (human && policyChannels == 3))
            throw new IllegalArgumentException("Unexpected Human policy channel count");
        // KataGo dumponnx defaults to scale8. Its outputs are raw and have
        // to be unscaled before neuralnet/nneval.cpp postprocessing.
        // Legacy release manifests omitted the field; only their exact SHA256
        // model graphs can use the documented scale8 compatibility path.
        Double declaredScale = metadata.has("outputScaleMultiplier")
                ? metadata.getDouble("outputScaleMultiplier") : null;
        String graphHash = KataGoLiteRtOutputScale.sha256(file);
        if (human && !graphHash.equalsIgnoreCase(metadata.getString("graphSha256")))
            throw new IllegalArgumentException("Human graph SHA-256 differs from validated manifest");
        double scale = human
                ? KataGoLiteRtOutputScale.resolveDeclared(declaredScale)
                : KataGoLiteRtOutputScale.resolve(board, declaredScale, graphHash);
        if (metadata.has("scale8Applied") && metadata.getBoolean("scale8Applied") != (scale == 8.0))
            throw new IllegalArgumentException("ONNX scale8Applied and output multiplier disagree");
        outputScaleMultiplier = (float) scale;
        inputNames = new String[inputKeys.length];
        outputNames = new String[OUTPUT.length];
        inputCounts = new int[inputKeys.length];
        outputCounts = new int[OUTPUT.length];
        inputPositions = new int[inputKeys.length];
        outputPositions = new int[OUTPUT.length];
        JSONArray inputOrder = metadata.getJSONArray("inputOrder");
        JSONArray outputOrder = metadata.getJSONArray("outputOrder");
        if (inputOrder.length() != inputKeys.length || outputOrder.length() != 5)
            throw new IllegalArgumentException("Invalid LiteRT buffer order in manifest");
        for (int i=0;i<inputKeys.length;i++) {
            inputPositions[i] = -1;
            for (int pos=0;pos<inputKeys.length;pos++)
                if (inputKeys[i].equals(inputOrder.getString(pos))) inputPositions[i] = pos;
            if (inputPositions[i] < 0)
                throw new IllegalArgumentException("Missing LiteRT input " + inputKeys[i]);
        }
        for (int i=0;i<OUTPUT.length;i++) {
            outputPositions[i] = -1;
            for (int pos=0;pos<5;pos++)
                if (OUTPUT[i].equals(outputOrder.getString(pos))) outputPositions[i] = pos;
            if (outputPositions[i] < 0) throw new IllegalArgumentException("Missing LiteRT output " + OUTPUT[i]);
        }
        JSONObject rawInputs = metadata.getJSONObject("inputs");
        JSONObject rawOutputs = metadata.getJSONObject("outputs");
        for (int i=0;i<inputKeys.length;i++) {
            JSONObject item = rawInputs.getJSONObject(inputKeys[i]);
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
                (human && inputCounts[2] != metaChannels) ||
                inputCounts[human ? 3 : 2] != board * board ||
                outputCounts[0] != policyChannels ||
                outputCounts[1] != board * board * policyChannels ||
                outputCounts[2] != 3 || outputCounts[3] != (human ? 6 : 4) ||
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
            if (in.size() != inputKeys.length || out.size() != 5)
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
        Log.i(TAG, "MTK_LITERT_INIT board=" + board + " model=" + (human ? "human" : "10b")
                + " npuRequested=true graph=" + file.getName()
                + " outputScaleMultiplier=" + outputScaleMultiplier
                + " scaleSource=" + (declaredScale == null ? "pinned_legacy_graph" : "onnx_manifest"));
    }

    /**
     * Caller supplies NHWC KataGo feature tensors. Uses exactly the 5 original
     * head semantics, without remapping the value/ownership head contents.
     */
    public synchronized float[][] infer(float[] spatial, float[] global, float[] mask) throws Exception {
        return infer(spatial, global, mask, null);
    }

    public synchronized float[][] infer(float[] spatial, float[] global, float[] mask,
                                         float[] meta) throws Exception {
        if (humanModel && (meta == null || meta.length != 192))
            throw new IllegalArgumentException("Human LiteRT requires 192 original SGF metadata features");
        if (!humanModel && meta != null)
            throw new IllegalArgumentException("g170 must not receive Human metadata");
        float[][] values = humanModel ? new float[][] {spatial, global, meta, mask}
                                      : new float[][] {spatial, global, mask};
        for (int i=0;i<inputKeys.length;i++) {
            if (values[i] == null || values[i].length != inputCounts[i])
                throw new IllegalArgumentException("Incorrect tensor length for " + inputKeys[i]);
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
        KataGoLiteRtOutputScale.restore(result, outputScaleMultiplier);
        long elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1000000L;
        Log.i(TAG, "MTK_LITERT_INFER board=" + size
                + " model=" + (humanModel ? "human" : "10b") + " ms=" + elapsedMs
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
