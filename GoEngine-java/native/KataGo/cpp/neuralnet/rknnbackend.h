#ifndef KATAGO_RKNNBACKEND_H_
#define KATAGO_RKNNBACKEND_H_

#include <memory>
#include <string>

// One RKNN context per KataGo NN-server thread. This bridge uses the existing
// KataGo input encoding and NNOutput postprocessing from eigenbackend.cpp.
class KataGoRknnRunner {
public:
    explicit KataGoRknnRunner(const std::string& modelPath, int boardSize);
    ~KataGoRknnRunner();
    bool ready() const;
    KataGoRknnRunner(const KataGoRknnRunner&) = delete;
    KataGoRknnRunner& operator=(const KataGoRknnRunner&) = delete;

    void infer(const float* spatialNHWC, const float* global, float* policyPass,
               float* policyNHWC, float* value, float* scoreValue, float* ownership);
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

#endif
