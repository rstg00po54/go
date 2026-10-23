package com.example.mtkprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.TensorBuffer;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Independent, model-agnostic LiteRT -> MediaTek Neuron compilation test.
 * No KataGo process, JNI bridge, rank features, GTP or automatic CPU fallback.
 *
 * A PASS only proves the public LiteRT API compiled the graph and created
 * buffers. It does NOT prove full hardware NPU delegation or correct results.
 */
public final class ProbeActivity extends Activity {
    private static final String TAG = "MTKPROBE";
    private TextView status;
    private String runId;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(32, 32, 32, 32);
        status.setText("Independent MediaTek LiteRT compile probe.\n"
                + "Use the host script to load a model and run this test.\n"
                + "This app does not modify BadukaiJ.");
        setContentView(status);

        String modelName = getIntent().getStringExtra("model");
        if (modelName == null) return; // Launcher tap: no implicit testing.
        String id = getIntent().getStringExtra("run_id");
        runId = (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) ? "invalid_id" : id;
        final String accelerator = getIntent().getStringExtra("accelerator");
        final String mode = getIntent().getStringExtra("mode");
        final String name = modelName;
        new Thread(() -> probe(name, accelerator, mode), "mtk-compile-probe").start();
    }

    private void emit(String event, String details) {
        String message = "id=" + runId + " event=" + event + " " + details;
        Log.i(TAG, message);
        runOnUiThread(() -> status.setText(message));
    }

    private static String shortError(Throwable ex) {
        String message = String.valueOf(ex);
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() > 800 ? message.substring(0, 800) : message;
    }

    private void probe(String filename, String accelerator, String mode) {
        String phase = "ARGS";
        long started = SystemClock.elapsedRealtime();
        Environment env = null;
        CompiledModel compiled = null;
        List<TensorBuffer> in = null;
        List<TensorBuffer> out = null;
        try {
            if (!filename.matches("[A-Za-z0-9_.-]{1,100}\\.tflite") || filename.contains(".."))
                throw new IllegalArgumentException("Invalid model filename");
            String accel = accelerator == null ? "NPU" : accelerator.toUpperCase(Locale.ROOT);
            String testMode = mode == null ? "BUFFERS" : mode.toUpperCase(Locale.ROOT);
            if (!accel.equals("NPU") && !accel.equals("CPU"))
                throw new IllegalArgumentException("accelerator must be NPU or CPU");
            if (!testMode.equals("COMPILE") && !testMode.equals("BUFFERS"))
                throw new IllegalArgumentException("mode must be COMPILE or BUFFERS");
            File file = new File(new File(getFilesDir(), "models"), filename);
            if (!file.isFile() || file.length() < 8)
                throw new IllegalArgumentException("Missing/empty model: " + file);
            emit("START", "model=" + filename + " accelerator=" + accel
                    + " mode=" + testMode + " bytes=" + file.length());

            phase = "PROVIDER";
            emit("PHASE", "name=" + phase);
            BuiltinNpuAcceleratorProvider provider = new BuiltinNpuAcceleratorProvider(this);
            if (accel.equals("NPU") && (!provider.isDeviceSupported() || !provider.isLibraryReady()))
                throw new IllegalStateException("MediaTek NPU provider unavailable");

            phase = "ENVIRONMENT";
            emit("PHASE", "name=" + phase);
            env = Environment.create(this, provider, Collections.emptyMap(), true);
            Accelerator target = accel.equals("NPU") ? Accelerator.NPU : Accelerator.CPU;
            if (!env.getAvailableAccelerators().contains(target))
                throw new IllegalStateException("Requested accelerator not advertised: " + accel);

            phase = "COMPILED_MODEL_CREATE";
            emit("PHASE", "name=" + phase);
            long t = SystemClock.elapsedRealtime();
            compiled = CompiledModel.create(file.getAbsolutePath(),
                    new CompiledModel.Options(target), env);
            emit("MODEL_CREATED", "compileMs=" + (SystemClock.elapsedRealtime() - t));

            if (testMode.equals("BUFFERS")) {
                phase = "CREATE_BUFFERS";
                emit("PHASE", "name=" + phase);
                in = compiled.createInputBuffers();
                out = compiled.createOutputBuffers();
                emit("BUFFERS_CREATED", "inputs=" + in.size() + " outputs=" + out.size());
            }
            emit("PASS", "model=" + filename + " accelerator=" + accel
                    + " totalMs=" + (SystemClock.elapsedRealtime() - started)
                    + " note=compile_not_proof_of_physical_npu_execution");
        } catch (Throwable ex) {
            emit("FAIL", "phase=" + phase + " exception=" + ex.getClass().getName()
                    + " detail=" + shortError(ex)
                    + " totalMs=" + (SystemClock.elapsedRealtime() - started));
            Log.e(TAG, "id=" + runId + " Detailed error from " + phase, ex);
        } finally {
            if (in != null) for (TensorBuffer b : in) try { b.close(); } catch (Throwable ignored) {}
            if (out != null) for (TensorBuffer b : out) try { b.close(); } catch (Throwable ignored) {}
            if (compiled != null) try { compiled.close(); } catch (Throwable ignored) {}
            if (env != null) try { env.close(); } catch (Throwable ignored) {}
        }
    }
}
