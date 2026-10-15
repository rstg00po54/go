#ifndef KATAGO_MEDIATEK_BACKEND_H_
#define KATAGO_MEDIATEK_BACKEND_H_

#include <jni.h>

// Experimental, in-process Android JNI bridge: KataGo NN features -> Java
// LiteRT model -> KataGo NN heads. Only built with -DUSE_BACKEND=MEDIATEK.
// Verified Human SL may use a separate metadata-aware LiteRT graph.
class KataGoMediaTekRunner {
 public:
  explicit KataGoMediaTekRunner(int boardSize, bool humanModel);
  void infer(const float* spatial, const float* global, const float* meta,
             int metaChannels, int spatialChannels, int globalChannels,
             int policyChannels, int scoreChannels,
             float* pass, float* policy, float* value,
             float* score, float* ownership);
 private:
  const int boardSize_;
  const bool humanModel_;
};

// Install is called on the Java UI thread *before* GTP starts NN workers.
// Reset is called only after the native GTP session has shut down.
namespace KataGoMediaTekBridge {
  bool install(JNIEnv* env, jobject javaRunner, jobject javaHumanRunner, bool compareCpu);
  bool hasHumanRunner();
  bool parityEnabled();
  void reset(JNIEnv* env);
  bool ready();
}
#endif
