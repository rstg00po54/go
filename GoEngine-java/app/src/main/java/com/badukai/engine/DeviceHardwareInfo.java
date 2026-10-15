package com.badukai.engine;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;

import java.util.Locale;

/**
 * UI-only hardware identification. Display names are not accelerator availability proofs.
 * Native backend selection remains in KataGoEngine and NpuHardware.
 */
public final class DeviceHardwareInfo {
    private static final String TAG = "DeviceHardwareInfo";
    private DeviceHardwareInfo() {}

    private static String clean(String name) {
        if (name == null) return "";
        String text = name.trim();
        if (text.isEmpty() || "unknown".equalsIgnoreCase(text)) return "";
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    public static String socName() {
        String soc = Build.VERSION.SDK_INT >= 31 ? clean(Build.SOC_MODEL) : "";
        if (!soc.isEmpty()) return soc;
        String board = clean(Build.HARDWARE);
        return board.isEmpty() ? "SoC 未公开" : board;
    }

    public static String deviceName() {
        String brand = clean(Build.MANUFACTURER);
        String model = clean(Build.MODEL);
        if (model.isEmpty()) model = clean(Build.DEVICE);
        if (model.isEmpty()) model = "未知设备";
        String shown = !brand.isEmpty() && !model.toLowerCase(Locale.ROOT)
                .startsWith(brand.toLowerCase(Locale.ROOT) + " ") ? brand + " " + model : model;
        return "设备：" + shown + "  |  SoC：" + socName();
    }

    public static String cpuName() {
        return socName() + " / " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "未知架构");
    }

    /** NPU's exact marketing model is not available via stable public Android APIs. */
    public static String npuName() {
        NpuHardware.Vendor vendor = NpuHardware.current();
        if (vendor == NpuHardware.Vendor.ROCKCHIP_RK3588)
            return "Rockchip RKNPU2（" + socName() + "）";
        if (vendor == NpuHardware.Vendor.MEDIATEK)
            return "MediaTek APU（" + socName() + "，具体型号未公开）";
        return "未识别";
    }

    public static String npuDescription(boolean rknnGameAvailable) {
        NpuHardware.Vendor vendor = NpuHardware.current();
        if (vendor == NpuHardware.Vendor.MEDIATEK)
            return "NPU：" + npuName() + (rknnGameAvailable
                    ? " · LiteRT 对弈可选（9/13/19路）" : " · GTP 测试可用，未打包对弈后端");
        if (vendor == NpuHardware.Vendor.ROCKCHIP_RK3588)
            return "NPU：" + npuName() + (rknnGameAvailable ? " · RKNN 对弈可选" : " · 尚未打包对弈后端");
        return "NPU：系统未识别可用后端";
    }

    /**
     * Probe the OpenGL renderer on a background thread. This reports the
     * underlying GPU name, not the availability of OpenCL or KataGo GPU JNI.
     */
    public static String queryGpuRenderer() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY
                    || !EGL14.eglInitialize(display, new int[2], 0, new int[2], 0))
                return "GPU 型号未公开";
            int[] attribs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
                    || count[0] == 0 || configs[0] == null)
                return "GPU 型号未公开";
            int[] pbuffer = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbuffer, 0);
            int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    ctxAttribs, 0);
            if (surface == EGL14.EGL_NO_SURFACE || context == EGL14.EGL_NO_CONTEXT
                    || !EGL14.eglMakeCurrent(display, surface, surface, context))
                return "GPU 型号未公开";
            String renderer = clean(GLES20.glGetString(GLES20.GL_RENDERER));
            return renderer.isEmpty() ? "GPU 型号未公开" : renderer;
        } catch (RuntimeException | LinkageError err) {
            Log.w(TAG, "GPU renderer query failed", err);
            return "GPU 型号未公开";
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                EGL14.eglTerminate(display);
            }
        }
    }
}
