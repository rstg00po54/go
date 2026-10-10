#include <jni.h>
#include <string>

#include "game/board.h"

#if defined(USE_OPENCL_BACKEND)
#define KATAGO_STATUS_JNI Java_com_badukai_engine_KataGoGpuNative_nativeBuildStatus
#else
#define KATAGO_STATUS_JNI Java_com_badukai_engine_KataGoNative_nativeBuildStatus
#endif

// JNI linkage status for the current compiled backend.
// This is NOT a playable engine session and must not replace ProcessBuilder yet.
extern "C" JNIEXPORT jstring JNICALL
KATAGO_STATUS_JNI(JNIEnv* env, jclass) {
    const std::string status = "KataGo JNI core loaded; maxBoardSize=" +
                               std::to_string(Board::MAX_LEN);
    return env->NewStringUTF(status.c_str());
}
