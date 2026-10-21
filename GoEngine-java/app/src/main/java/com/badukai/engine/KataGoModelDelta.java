package com.badukai.engine;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Read-only base + tiny copy/insert delta -> EXACT original .tflite/.rknn.
 * The Android runtime never changes or requantizes a neural network.
 * First restoration verifies both source and reconstructed SHA-256 hashes;
 * later launches reuse private, persisted files when metadata matches.
 */
final class KataGoModelDelta {
    private static final String TAG = "KataGoModelDelta";
    private static final byte[] MAGIC = new byte[]{'K','G','M','D','E','L','0','1'};
    private static final int BUFFER = 64 * 1024;

    private KataGoModelDelta() {}

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }

    private static byte[] shaFile(File file) throws IOException {
        MessageDigest digest = sha256();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[BUFFER];
            int count;
            while ((count = input.read(data)) != -1) {
                if (count != 0) digest.update(data, 0, count);
            }
        }
        return digest.digest();
    }

    private static String hex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte b : data) builder.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return builder.toString();
    }

    /**
     * A model restored once is cached per APK update, file length, modification
     * time, and expected output digest. The first use verifies actual SHA-256.
     * Subsequent private-file reuse avoids expensive 100 MiB re-hashing.
     */
    static synchronized void restore(Context context, File base, String patchAsset, File target)
            throws IOException {
        long start = System.nanoTime();
        if (!base.isFile()) throw new IOException("Missing compact model base: " + base);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create model directory: " + parent);

        try (DataInputStream patch = new DataInputStream(new BufferedInputStream(
                context.getAssets().open(patchAsset), BUFFER))) {
            byte[] magic = new byte[8];
            patch.readFully(magic);
            if (!Arrays.equals(magic, MAGIC))
                throw new IOException("Unknown compact model patch format: " + patchAsset);
            byte[] baseHash = new byte[32];
            byte[] targetHash = new byte[32];
            patch.readFully(baseHash);
            patch.readFully(targetHash);
            long expectedSize = patch.readLong();
            if (expectedSize < 1 || expectedSize > 2L * 1024 * 1024 * 1024)
                throw new IOException("Invalid expected model size: " + expectedSize);
            String expectedHex = hex(targetHash);
            String prefix = "compact_" + patchAsset + "_" + target.getAbsolutePath();
            SharedPreferences prefs = context.getSharedPreferences("katago_delta_cache", Context.MODE_PRIVATE);
            long apkTime;
            try {
                apkTime = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), 0).lastUpdateTime;
            } catch (android.content.pm.PackageManager.NameNotFoundException error) {
                throw new IOException("Unable to inspect APK for model cache", error);
            }

            boolean matchingCache = target.isFile()
                    && target.length() == expectedSize
                    && target.lastModified() == prefs.getLong(prefix + ".modified", -1)
                    && apkTime == prefs.getLong(prefix + ".apk", -2)
                    && expectedHex.equals(prefs.getString(prefix + ".sha", ""));
            if (matchingCache) {
                Log.i(TAG, "COMPACT_MODEL_REUSED asset=" + patchAsset + " bytes=" + expectedSize);
                return;
            }
            // Existing full-model installs may already have the exact target.
            if (target.isFile() && target.length() == expectedSize
                    && Arrays.equals(shaFile(target), targetHash)) {
                storeCache(prefs, prefix, apkTime, expectedHex, target);
                Log.i(TAG, "COMPACT_MODEL_EXISTING_VERIFIED asset=" + patchAsset);
                return;
            }
            if (!Arrays.equals(shaFile(base), baseHash))
                throw new IOException("Base model SHA-256 mismatch: " + base);

            File staging = File.createTempFile(target.getName() + ".", ".partial", parent);
            try {
                MessageDigest digest = sha256();
                long written = 0;
                try (RandomAccessFile source = new RandomAccessFile(base, "r");
                     FileOutputStream stream = new FileOutputStream(staging);
                     OutputStream out = new BufferedOutputStream(stream, BUFFER)) {
                    byte[] buffer = new byte[BUFFER];
                    for (;;) {
                        int op = patch.read();
                        if (op == 'E') {
                            if (patch.read() != -1) throw new IOException("Unexpected bytes after patch end");
                            break;
                        }
                        if (op != 'C' && op != 'I')
                            throw new IOException(op == -1 ? "Truncated compact model patch"
                                    : "Invalid model patch opcode " + op);
                        long offset = 0;
                        int size;
                        if (op == 'C') {
                            offset = patch.readLong();
                            size = patch.readInt();
                            if (offset < 0 || size <= 0 || offset > source.length()
                                    || size > source.length() - offset)
                                throw new IOException("Bad compact model copy bounds");
                            source.seek(offset);
                        } else {
                            size = patch.readInt();
                            if (size <= 0 || size > 1024 * 1024)
                                throw new IOException("Bad model patch literal size");
                        }
                        if (written > expectedSize - size)
                            throw new IOException("Restored model exceeds expected size");
                        int remaining = size;
                        while (remaining > 0) {
                            int count = Math.min(buffer.length, remaining);
                            if (op == 'C') source.readFully(buffer, 0, count);
                            else patch.readFully(buffer, 0, count);
                            out.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                            remaining -= count;
                            written += count;
                        }
                    }
                    out.flush();
                    stream.getFD().sync();
                }
                if (written != expectedSize || !Arrays.equals(digest.digest(), targetHash))
                    throw new IOException("Reconstructed model SHA-256 or size mismatch: " + patchAsset);
                try {
                    Files.move(staging.toPath(), target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                storeCache(prefs, prefix, apkTime, expectedHex, target);
                Log.i(TAG, "COMPACT_MODEL_RESTORED asset=" + patchAsset
                        + " bytes=" + written + " ms=" + (System.nanoTime() - start) / 1000000.0);
            } finally {
                if (staging.exists() && !staging.delete())
                    Log.w(TAG, "Could not delete stale compact model temp: " + staging);
            }
        }
    }

    private static void storeCache(SharedPreferences prefs, String prefix, long apkTime,
                                   String sha, File file) {
        prefs.edit().putLong(prefix + ".apk", apkTime)
                .putLong(prefix + ".modified", file.lastModified())
                .putString(prefix + ".sha", sha).apply();
    }
}
