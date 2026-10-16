// Experimental Human SL b18 RKNN runner, deliberately separate from 10b.
// Requires explicit opt-in, fixed 9x9 model and four SGF metadata-aware inputs.
#include "humanrknnbackend.h"
#include "rknn_api.h"
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <chrono>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr int BOARD = 9;
constexpr int AREA = BOARD*BOARD;
constexpr int SPATIAL_CHANNELS = 22;
constexpr int GLOBAL_CHANNELS = 19;
constexpr int META_CHANNELS = 192;
// Official KataGo dumponnx uses activation scale8 by default; its metadata
// katago.postProcess.outputScaleMultiplier is 8. Eigen's raw b18 outputs
// are *unscaled*. Restore all five raw heads to native Eigen units before
// KataGo blends policy channels and performs its regular post-processing.
constexpr float HUMAN_ONNX_OUTPUT_SCALE = 8.0f;
constexpr int IN = 4;
constexpr int OUT = 5;
const char* const inputNames[IN] = {
  "InputSpatial", "InputGlobal", "InputMeta", "InputMask"
};
const char* const outputNames[OUT] = {
  "OutputPolicyPass", "OutputPolicy", "OutputValue",
  "OutputScoreValue", "OutputOwnership"
};
const std::array<uint32_t,IN> inputCounts = {{
  AREA*SPATIAL_CHANNELS,GLOBAL_CHANNELS,META_CHANNELS,AREA
}};
const std::array<uint32_t,OUT> outputCounts = {{
  2,2*AREA,3,6,AREA
}};
void check(int rc, const char* what) {
  if(rc != RKNN_SUCC)
    throw std::runtime_error(std::string("Human RKNN ") + what + " failed rc=" + std::to_string(rc));
}
int match(const char* value, const char* const* names, int count) {
  if(!value || !*value) throw std::runtime_error("Human RKNN unnamed tensor");
  for(int i=0;i<count;i++)
    if(std::string(value).find(names[i]) != std::string::npos)
      return i;
  throw std::runtime_error(std::string("Human RKNN unknown tensor ") + value);
}
}

struct KataGoHumanRknnRunner::Impl {
  rknn_context ctx = 0;
  bool available = false;
  uint64_t inferenceCount = 0;
  double inferenceElapsedMs = 0.0;
  std::array<int,IN> inSlot{{-1,-1,-1,-1}};
  std::array<int,OUT> outSlot{{-1,-1,-1,-1,-1}};
  std::vector<float> mask = std::vector<float>(AREA,0.0f);
  explicit Impl(const std::string& path, int boardSize) {
    if(boardSize != BOARD)
      throw std::runtime_error("Human b18 RKNN experimental backend only supports 9x9");
    std::ifstream file(path,std::ios::binary);
    if(!file) throw std::runtime_error("Human RKNN model file missing: "+path);
    std::vector<char> model((std::istreambuf_iterator<char>(file)),std::istreambuf_iterator<char>());
    if(model.empty()) throw std::runtime_error("Human RKNN model is empty");
    __android_log_print(ANDROID_LOG_INFO,"KataGoHumanRKNN",
      "HUMAN_RKNN_INIT board=%d bytes=%zu path=%s",boardSize,model.size(),path.c_str());
    check(rknn_init(&ctx,model.data(),static_cast<uint32_t>(model.size()),0,nullptr),"rknn_init");
    try {
      check(rknn_set_core_mask(ctx,RKNN_NPU_CORE_0),"rknn_set_core_mask");
      rknn_input_output_num io = {};
      check(rknn_query(ctx,RKNN_QUERY_IN_OUT_NUM,&io,sizeof(io)),"query io");
      if(io.n_input!=IN || io.n_output!=OUT)
        throw std::runtime_error("Human RKNN requires 4 inputs and 5 output heads");
      for(uint32_t index=0;index<io.n_input;index++) {
        rknn_tensor_attr attr = {};
        attr.index=index;
        check(rknn_query(ctx,RKNN_QUERY_INPUT_ATTR,&attr,sizeof(attr)),"query input");
        const int key=match(attr.name,inputNames,IN);
        if(inSlot[key]>=0 || attr.n_elems!=inputCounts[key] ||
           attr.fmt!=RKNN_TENSOR_NHWC)
          throw std::runtime_error(std::string("Human RKNN invalid input tensor ")+attr.name);
        inSlot[key]=static_cast<int>(index);
        __android_log_print(ANDROID_LOG_INFO,"KataGoHumanRKNN",
          "HUMAN_RKNN_INPUT key=%d slot=%u name=%s elems=%u",key,index,attr.name,attr.n_elems);
      }
      for(uint32_t index=0;index<io.n_output;index++) {
        rknn_tensor_attr attr = {};
        attr.index=index;
        check(rknn_query(ctx,RKNN_QUERY_OUTPUT_ATTR,&attr,sizeof(attr)),"query output");
        const int key=match(attr.name,outputNames,OUT);
        if(outSlot[key]>=0 || attr.n_elems!=outputCounts[key] ||
           attr.fmt!=RKNN_TENSOR_NCHW)
          throw std::runtime_error(std::string("Human RKNN invalid output tensor ")+attr.name);
        outSlot[key]=static_cast<int>(index);
        __android_log_print(ANDROID_LOG_INFO,"KataGoHumanRKNN",
          "HUMAN_RKNN_OUTPUT key=%d slot=%u name=%s elems=%u",key,index,attr.name,attr.n_elems);
      }
      for(int i:inSlot) if(i<0) throw std::runtime_error("Missing Human RKNN input");
      for(int i:outSlot) if(i<0) throw std::runtime_error("Missing Human RKNN output");
      available=true;
      __android_log_print(ANDROID_LOG_INFO,"KataGoHumanRKNN",
        "HUMAN_RKNN_READY board=9 inputs=4 outputs=5 scale8Compensation=8");
    } catch(...) {
      rknn_destroy(ctx);
      ctx=0;
      throw;
    }
  }
  ~Impl() { if(ctx) rknn_destroy(ctx); }

  void infer(const float* spatial, const float* global, const float* meta,
             float* pass, float* policy, float* value, float* score,
             float* ownership) {
    if(!available || !spatial || !global || !meta || !pass || !policy ||
       !value || !score || !ownership)
      throw std::runtime_error("Human RKNN inputs/outputs are not initialized");
    for(int point=0;point<AREA;point++)
      mask[point]=spatial[point*SPATIAL_CHANNELS];
    std::array<const float*,IN> sources={{spatial,global,meta,mask.data()}};
    std::array<rknn_input,IN> inputs={};
    for(int key=0;key<IN;key++) {
      const int slot=inSlot[key];
      inputs[slot].index=static_cast<uint32_t>(slot);
      inputs[slot].buf=const_cast<float*>(sources[key]);
      inputs[slot].size=inputCounts[key]*sizeof(float);
      inputs[slot].pass_through=0;
      inputs[slot].type=RKNN_TENSOR_FLOAT32;
      inputs[slot].fmt=RKNN_TENSOR_NHWC;
    }
    const auto stamp0 = std::chrono::steady_clock::now();
    check(rknn_inputs_set(ctx,IN,inputs.data()),"rknn_inputs_set");
    const auto stamp1 = std::chrono::steady_clock::now();
    check(rknn_run(ctx,nullptr),"rknn_run");
    const auto stamp2 = std::chrono::steady_clock::now();
    std::array<rknn_output,OUT> outputs={};
    for(int i=0;i<OUT;i++) {
      outputs[i].index=static_cast<uint32_t>(i);
      outputs[i].want_float=1;
    }
    check(rknn_outputs_get(ctx,OUT,outputs.data(),nullptr),"rknn_outputs_get");
    try {
      std::array<std::vector<float>,OUT> values;
      for(int key=0;key<OUT;key++) {
        const rknn_output& item=outputs[outSlot[key]];
        if(!item.buf || item.size!=outputCounts[key]*sizeof(float))
          throw std::runtime_error(std::string("Human RKNN output size mismatch ")+outputNames[key]);
        const float* data=static_cast<const float*>(item.buf);
        for(uint32_t i=0;i<outputCounts[key];i++)
          if(!std::isfinite(data[i]))
            throw std::runtime_error(std::string("Human RKNN NaN/Inf ")+outputNames[key]);
        values[key].assign(data,data+outputCounts[key]);
      }
      // The RKNN is generated from dumponnx without -skip-scale8, so its
      // raw five-head outputs are 1/8 of native Eigen's raw outputs. This
      // compensates for the ONNX metadata outputScaleMultiplier=8, which the
      // custom RKNN runner (unlike KataGo's ONNX backend) must apply itself.
      for(auto& head : values) {
        for(float& v : head) {
          v *= HUMAN_ONNX_OUTPUT_SCALE;
          if(!std::isfinite(v))
            throw std::runtime_error("Human RKNN scaled output NaN/Inf");
        }
      }
      // NPU returns OutputPolicy in NCHW: [channel][board point].
      // KataGo Eigen TENSORMAP4 consumes interleaved channels (NHWC).
      // The other heads are scalar vectors or a one-channel board map.
      std::copy(values[0].begin(),values[0].end(),pass);
      for(int p=0;p<AREA;p++) {
        policy[p*2]=values[1][p];
        policy[p*2+1]=values[1][AREA+p];
      }
      std::copy(values[2].begin(),values[2].end(),value);
      std::copy(values[3].begin(),values[3].end(),score);
      std::copy(values[4].begin(),values[4].end(),ownership);
    } catch(...) {
      rknn_outputs_release(ctx,OUT,outputs.data());
      throw;
    }
    check(rknn_outputs_release(ctx,OUT,outputs.data()),"rknn_outputs_release");
    const auto stamp3 = std::chrono::steady_clock::now();
    const auto elapsed = [](std::chrono::steady_clock::time_point a,
                            std::chrono::steady_clock::time_point b) {
      return std::chrono::duration<double, std::milli>(b-a).count();
    };
    const double totalMs=elapsed(stamp0,stamp3);
    ++inferenceCount;
    inferenceElapsedMs+=totalMs;
    // Bound log volume: short warm-up samples and periodic steady-state checks.
    if(inferenceCount<=8 || inferenceCount%32==0) {
      __android_log_print(ANDROID_LOG_INFO,"KataGoHumanRKNN",
        "HUMAN_RKNN_INFER_TIMING count=%llu inputMs=%.3f runMs=%.3f outputMs=%.3f totalMs=%.3f avgMs=%.3f",
        (unsigned long long)inferenceCount,elapsed(stamp0,stamp1),
        elapsed(stamp1,stamp2),elapsed(stamp2,stamp3),
        totalMs,inferenceElapsedMs/static_cast<double>(inferenceCount));
    }
  }
};

KataGoHumanRknnRunner::KataGoHumanRknnRunner(const std::string& modelPath,int boardSize)
  : impl_(new Impl(modelPath,boardSize)) {}
KataGoHumanRknnRunner::~KataGoHumanRknnRunner()=default;
bool KataGoHumanRknnRunner::ready() const { return impl_->available; }
void KataGoHumanRknnRunner::infer(const float* spatial,const float* global,
   const float* meta,float* pass,float* policy,float* value,
   float* score,float* ownership) {
  impl_->infer(spatial,global,meta,pass,policy,value,score,ownership);
}
