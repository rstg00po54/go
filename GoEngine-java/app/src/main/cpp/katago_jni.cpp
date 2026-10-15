#include <jni.h>
#include <string>

#include "game/board.h"

#if defined(USE_RKNN_BACKEND)
#define KATAGO_STATUS_JNI Java_com_badukai_engine_KataGoNpuNative_nativeBuildStatus
#elif defined(USE_MEDIATEK_BACKEND)
#define KATAGO_STATUS_JNI Java_com_badukai_engine_KataGoMediaTekNative_nativeBuildStatus
#elif defined(USE_OPENCL_BACKEND)
#define KATAGO_STATUS_JNI Java_com_badukai_engine_KataGoGpuNative_nativeBuildStatus
#else
#define KATAGO_STATUS_JNI Java_com_badukai_engine_KataGoNative_nativeBuildStatus
#endif

// Optional backend-specific JNI linkage probe.
extern "C" JNIEXPORT jstring JNICALL
KATAGO_STATUS_JNI(JNIEnv* env, jclass) {
#if defined(USE_RKNN_BACKEND)
    const std::string status = "KataGo NPU/RKNN JNI core loaded; maxBoardSize=" + std::to_string(Board::MAX_LEN);
#elif defined(USE_MEDIATEK_BACKEND)
    const std::string status = "KataGo MediaTek LiteRT GTP JNI core loaded; maxBoardSize=" +
                               std::to_string(Board::MAX_LEN);
#elif defined(USE_OPENCL_BACKEND)
    const std::string status = "KataGo GPU/OpenCL JNI core loaded; maxBoardSize=" +
                               std::to_string(Board::MAX_LEN);
#else
    const std::string status = "KataGo CPU/Eigen JNI core loaded; maxBoardSize=" +
                               std::to_string(Board::MAX_LEN);
#endif
    return env->NewStringUTF(status.c_str());
}
