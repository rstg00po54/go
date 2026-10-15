package com.badukai.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Restores the raw output scale chosen by upstream KataGo ONNX export.
 * The legacy model set was exported with default ONNX scale8 enabled, but
 * its original JSON manifest discarded the outputScaleMultiplier metadata.
 * Only exactly these SHA256-pinned legacy graphs may infer that missing field.
 */
public final class KataGoLiteRtOutputScale {
    private static final String[] PINNED_GRAPH_SHA256 = {
            "eaf37768b48021e579e38f9bae0efbcb17f5c5a84d80d288430ee11784f1f039",
            "646788f1928fb39450486b5033eee7f5d84eb48f21064f3791c46dd9c74e5658",
            "9bbab05d27e2fa0b9cac64dcb4d81f7121f6731d3e0176ba08e59041f0382181"
    };
    private static final int[] BOARD_SIZES = {9, 13, 19};

    private KataGoLiteRtOutputScale() {}

    public static double resolve(int board, Double declaredMultiplier, String graphSha256) {
        if (declaredMultiplier != null) {
            double scale = declaredMultiplier;
            if (!Double.isFinite(scale) || (scale != 1.0 && scale != 8.0))
                throw new IllegalArgumentException("Unexpected KataGo ONNX outputScaleMultiplier: " + scale);
            return scale;
        }
        for (int i = 0; i < BOARD_SIZES.length; i++)
            if (board == BOARD_SIZES[i] && PINNED_GRAPH_SHA256[i].equalsIgnoreCase(graphSha256))
                return 8.0;
        throw new IllegalArgumentException(
                "Missing ONNX output scale metadata in an unrecognized TFLite graph; refusing inference");
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) {
            byte[] chunk = new byte[65536];
            int count;
            while ((count = stream.read(chunk)) != -1) digest.update(chunk, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b & 0xff));
        return hex.toString();
    }

    /** Raw ONNX head scaling must happen before any KataGo logit/score decoding. */
    public static void restore(float[][] heads, float factor) {
        if (heads == null || !Float.isFinite(factor) || factor <= 0f)
            throw new IllegalArgumentException("Invalid output multiplier");
        for (float[] head : heads) {
            if (head == null) throw new IllegalArgumentException("Missing KataGo output head");
            for (int i = 0; i < head.length; i++) {
                head[i] *= factor;
                if (!Float.isFinite(head[i]))
                    throw new IllegalArgumentException("Overflow after ONNX output scale restoration");
            }
        }
    }
}
