package com.badukai;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Private per-SGF cache of COMPLETED raw 10b NN win-rate samples. */
final class GoReviewCache {
    private static final String TAG = "GoReviewCache";
    private static final int MAGIC = 0x47525631; // GRV1
    private static final int VERSION = 1;

    private GoReviewCache() {}

    private static File file(Context context, String sgf) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(("10b-nn-review-v1:" + sgf)
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 16; i++)
            hex.append(Character.forDigit((digest[i] >> 4) & 15, 16))
                    .append(Character.forDigit(digest[i] & 15, 16));
        return new File(context.getFilesDir(), "review_" + hex + ".bin");
    }

    static float[] read(Context context, String sgf, int expectedCount) {
        if (sgf == null || expectedCount <= 0 || expectedCount > 10000) return null;
        try {
            AtomicFile atomic = new AtomicFile(file(context, sgf));
            try (InputStream stream = atomic.openRead();
                    DataInputStream in = new DataInputStream(stream)) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION
                        || in.readInt() != expectedCount) return null;
                float[] result = new float[expectedCount];
                for (int i = 0; i < result.length; i++) {
                    result[i] = in.readFloat();
                    if (!Float.isFinite(result[i]) || result[i] < 0f || result[i] > 1f)
                        return null;
                }
                if (in.read() != -1) return null;
                return result;
            }
        } catch (Exception ex) {
            Log.d(TAG, "No valid review cache", ex);
            return null;
        }
    }

    static void write(Context context, String sgf, float[] rates) {
        if (sgf == null || rates == null || rates.length == 0 || rates.length > 10000) return;
        for (float rate : rates)
            if (!Float.isFinite(rate) || rate < 0f || rate > 1f) return;
        try {
            AtomicFile atomic = new AtomicFile(file(context, sgf));
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                DataOutputStream out = new DataOutputStream(output);
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(rates.length);
                for (float rate : rates) out.writeFloat(rate);
                out.flush();
                atomic.finishWrite(output);
            } catch (Exception ex) {
                if (output != null) atomic.failWrite(output);
                throw ex;
            }
        } catch (Exception ex) {
            Log.w(TAG, "Failed to save review cache", ex);
        }
    }
}
