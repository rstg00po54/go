// MediaTek LiteRT inference bridge for genuine KataGo g170 10b features.
// The Java runner owns the CompiledModel and checks five output tensor heads.
// No vendor-specific C++ library is linked into this shared object.
#include "mediatekbackend.h"

#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr int kSpatialChannels = 22;
constexpr int kGlobalChannels = 19;
std::mutex runnerMutex;
JavaVM* javaVm = nullptr;
jobject javaRunner = nullptr;
jmethodID inferenceMethod = nullptr;
std::atomic<bool> verifyAgainstCpu{false};

struct ThreadEnv {
  JNIEnv* env = nullptr;
  bool attached = false;
  ThreadEnv() {
    if (javaVm == nullptr) throw std::runtime_error("MediaTek JVM not installed");
    const jint status = javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
      if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK)
        throw std::runtime_error("Could not attach KataGo NN worker to JVM");
      attached = true;
    } else if (status != JNI_OK) {
      throw std::runtime_error("Invalid JVM for MediaTek inference");
    }
  }
  ~ThreadEnv() {
    if (attached) javaVm->DetachCurrentThread();
  }
};

void checkJava(JNIEnv* env, const char* step) {
  if (!env->ExceptionCheck()) return;
  // The Java stacktrace explains the vendor error; keep the C++ NN worker from
  // terminating the whole Android process due to a pending JNI exception.
  env->ExceptionDescribe();
  env->ExceptionClear();
  throw std::runtime_error(std::string("LiteRT Java failure at ") + step);
}

jfloatArray makeFloatArray(JNIEnv* env, const float* floats, jsize len) {
  jfloatArray result = env->NewFloatArray(len);
  checkJava(env, "NewFloatArray");
  if (!result) throw std::runtime_error("No JNI memory for KataGo feature input");
  env->SetFloatArrayRegion(result, 0, len, floats);
  checkJava(env, "SetFloatArrayRegion");
  return result;
}
}

bool KataGoMediaTekBridge::install(JNIEnv* env, jobject runner, bool compareCpu) {
  if (runner == nullptr) return false;
  std::lock_guard<std::mutex> lock(runnerMutex);
  if (javaRunner != nullptr) return false;  // No concurrent GTP sessions.
  JavaVM* vm = nullptr;
  if (env->GetJavaVM(&vm) != JNI_OK) return false;
  jclass cls = env->GetObjectClass(runner);
  if (!cls) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    return false;
  }
  jmethodID method = env->GetMethodID(cls, "infer", "([F[F[F)[[F");
  env->DeleteLocalRef(cls);
  if (!method) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    return false;
  }
  jobject ref = env->NewGlobalRef(runner);
  if (!ref) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    return false;
  }
  javaVm = vm;
  javaRunner = ref;
  inferenceMethod = method;
  verifyAgainstCpu.store(compareCpu);
  __android_log_print(ANDROID_LOG_INFO, "KataGoMTK", "MTK_GTP_BRIDGE_INSTALLED");
  return true;
}

void KataGoMediaTekBridge::reset(JNIEnv* env) {
  std::lock_guard<std::mutex> lock(runnerMutex);
  if (javaRunner) env->DeleteGlobalRef(javaRunner);
  javaRunner = nullptr;
  inferenceMethod = nullptr;
  verifyAgainstCpu.store(false);
  // Keep javaVm valid for an attached native thread that is finishing up.
  __android_log_print(ANDROID_LOG_INFO, "KataGoMTK", "MTK_GTP_BRIDGE_RELEASED");
}

bool KataGoMediaTekBridge::parityEnabled() { return verifyAgainstCpu.load(); }

bool KataGoMediaTekBridge::ready() {
  std::lock_guard<std::mutex> lock(runnerMutex);
  return javaRunner != nullptr && inferenceMethod != nullptr;
}

KataGoMediaTekRunner::KataGoMediaTekRunner(int board) : boardSize_(board) {
  if (board != 9 && board != 13 && board != 19)
    throw std::runtime_error("MediaTek LiteRT requires 9, 13 or 19 lines");
  if (!KataGoMediaTekBridge::ready())
    throw std::runtime_error("MediaTek LiteRT Java runner not installed");
}

void KataGoMediaTekRunner::infer(const float* spatial, const float* global,
                                float* pass, float* policy, float* value,
                                float* score, float* ownership) {
  ThreadEnv thread;
  JNIEnv* env = thread.env;
  jobject runner = nullptr;
  jmethodID method;
  {
    std::lock_guard<std::mutex> lock(runnerMutex);
    if (!javaRunner || !inferenceMethod)
      throw std::runtime_error("MediaTek GTP JNI callback no longer available");
    runner = env->NewLocalRef(javaRunner);
    method = inferenceMethod;
  }
  checkJava(env, "NewLocalRef");
  if (!runner) throw std::runtime_error("Could not retain MediaTek LiteRT runner");
  const int area = boardSize_ * boardSize_;
  jfloatArray spatialArr = nullptr, globalArr = nullptr, maskArr = nullptr;
  jobjectArray heads = nullptr;
  try {
    spatialArr = makeFloatArray(env, spatial, area * kSpatialChannels);
    globalArr = makeFloatArray(env, global, kGlobalChannels);
    // KataGo spatial channel zero is the board mask.
    std::vector<float> mask(area);
    for (int p = 0; p < area; p++) mask[p] = spatial[p * kSpatialChannels];
    maskArr = makeFloatArray(env, mask.data(), area);
    heads = static_cast<jobjectArray>(
      env->CallObjectMethod(runner, method, spatialArr, globalArr, maskArr));
    checkJava(env, "KataGoMediaTekLiteRt.infer");
    if (!heads || env->GetArrayLength(heads) != 5)
      throw std::runtime_error("MediaTek inference must return five heads");
    const std::array<int,5> sizes = {{1, area, 3, 4, area}};
    const std::array<float*,5> dests = {{pass, policy, value, score, ownership}};
    for (int h = 0; h < 5; h++) {
      jfloatArray head = static_cast<jfloatArray>(env->GetObjectArrayElement(heads, h));
      checkJava(env, "GetObjectArrayElement");
      if (!head || env->GetArrayLength(head) != sizes[h]) {
        if (head) env->DeleteLocalRef(head);
        throw std::runtime_error("MediaTek inference output tensor length mismatch");
      }
      env->GetFloatArrayRegion(head, 0, sizes[h], dests[h]);
      env->DeleteLocalRef(head);
      checkJava(env, "GetFloatArrayRegion");
      for (int i = 0; i < sizes[h]; i++)
        if (!std::isfinite(dests[h][i]))
          throw std::runtime_error("MediaTek inference produced NaN/Inf");
    }
  } catch (...) {
    if (heads) env->DeleteLocalRef(heads);
    if (spatialArr) env->DeleteLocalRef(spatialArr);
    if (globalArr) env->DeleteLocalRef(globalArr);
    if (maskArr) env->DeleteLocalRef(maskArr);
    env->DeleteLocalRef(runner);
    throw;
  }
  if (heads) env->DeleteLocalRef(heads);
  if (spatialArr) env->DeleteLocalRef(spatialArr);
  if (globalArr) env->DeleteLocalRef(globalArr);
  if (maskArr) env->DeleteLocalRef(maskArr);
  env->DeleteLocalRef(runner);
  __android_log_print(ANDROID_LOG_INFO, "KataGoMTK", "MTK_GTP_INFER board=%d", boardSize_);
}
