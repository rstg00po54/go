#include <jni.h>
#include <string>

#include "game/board.h"

// First milestone only: a JNI symbol linked into the REAL KataGo C++ library.
// This is NOT a playable engine session and must not replace ProcessBuilder yet.
extern "C" JNIEXPORT jstring JNICALL
Java_com_badukai_engine_KataGoNative_nativeBuildStatus(JNIEnv* env, jclass) {
    const std::string status = "KataGo CPU/Eigen JNI core loaded; maxBoardSize=" +
                               std::to_string(Board::MAX_LEN);
    return env->NewStringUTF(status.c_str());
}
