#ifndef KATAGO_MEDIATEK_BACKEND_H_
#define KATAGO_MEDIATEK_BACKEND_H_

#include <jni.h>

// Experimental, in-process Android JNI bridge: KataGo NN features -> Java
// LiteRT model -> KataGo NN heads. Only built with -DUSE_BACKEND=MEDIATEK.
// Human SL continues to use the Eigen implementation.
class KataGoMediaTekRunner {
 public:
  explicit KataGoMediaTekRunner(int boardSize);
  void infer(const float* spatial, const float* global,
             float* pass, float* policy, float* value,
             float* score, float* ownership);
 private:
  const int boardSize_;
};

// Install is called on the Java UI thread *before* GTP starts NN workers.
// Reset is called only after the native GTP session has shut down.
namespace KataGoMediaTekBridge {
  bool install(JNIEnv* env, jobject javaRunner, bool compareCpu);
  bool parityEnabled();
  void reset(JNIEnv* env);
  bool ready();
}
#endif
