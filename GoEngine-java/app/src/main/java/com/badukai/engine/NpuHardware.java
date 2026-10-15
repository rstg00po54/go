package com.badukai.engine;

import android.os.Build;
import java.util.Locale;

/**
 * Select the vendor NPU backend by SoC, NOT by ARM64 CPU architecture.
 *
 * Both RK3588 and Vivo/Dimensity are aarch64. Their NPU runtimes/models are
 * incompatible: Rockchip RKNN (.rknn) vs MediaTek LiteRT/NeuroPilot (.tflite).
 * Only Rockchip currently has a playable KataGo GTP NPU backend; MediaTek is
 * independently probed until GTP integration and hardware execution are proven.
 */
public final class NpuHardware {
    public enum Vendor { ROCKCHIP_RK3588, MEDIATEK, UNSUPPORTED }

    private NpuHardware() {}

    public static Vendor classify(String socManufacturer, String socModel, String hardware,
                                  String board, String device) {
        String identity = (socManufacturer + " " + socModel + " " + hardware + " "
                + board + " " + device).toLowerCase(Locale.US);
        if (identity.contains("rk3588")) return Vendor.ROCKCHIP_RK3588;
        if (identity.contains("mediatek") || identity.contains("mtk")
                || identity.contains("mt6990") || identity.contains("mt6993"))
            return Vendor.MEDIATEK;
        return Vendor.UNSUPPORTED;
    }

    public static Vendor current() {
        String manufacturer = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER : "";
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "";
        return classify(manufacturer, soc, Build.HARDWARE, Build.BOARD, Build.DEVICE);
    }

    public static boolean canRunRknn() { return current() == Vendor.ROCKCHIP_RK3588; }

    public static boolean canProbeMediaTekLiteRt() {
        return Build.VERSION.SDK_INT >= 31 && current() == Vendor.MEDIATEK;
    }

    /**
     * True hardware NPU availability also depends on bundled model and vendor
     * runtime; identifying the SoC alone NEVER certifies LiteRT delegation.
     */
    public static String summary() {
        Vendor vendor = current();
        return vendor == Vendor.ROCKCHIP_RK3588 ? "RK3588 / RKNN"
                : vendor == Vendor.MEDIATEK ? "MediaTek / LiteRT GTP"
                : "CPU/GPU (NPU unsupported)";
    }
}
