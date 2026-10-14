// RK3588 RKNPU2 adapter for independently compiled 9x9, 13x13, and 19x19 KataGo 10b graphs.
// Full KataGo search, inputs, symmetries, and head postprocessing remain upstream.
#include "rknnbackend.h"
#include "rknn_api.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void checkRknn(int rc, const char* api) {
    if(rc != RKNN_SUCC) throw std::runtime_error(std::string(api) + " failed: " + std::to_string(rc));
}
constexpr int SPATIAL = 22;
constexpr int GLOBAL = 19;
constexpr int INPUTS = 3;
constexpr int OUTPUTS = 5;
const char* inputNames[INPUTS] = {"InputSpatial", "InputGlobal", "InputMask"};
const char* outputNames[OUTPUTS] = {"OutputPolicyPass", "OutputPolicy", "OutputValue", "OutputScoreValue", "OutputOwnership"};

int resolveName(const char* candidate, const char* const* names, int count) {
    for(int i=0;i<count;i++) if(std::string(candidate).find(names[i]) != std::string::npos) return i;
    throw std::runtime_error(std::string("Unexpected RKNN tensor name: ") + candidate);
}
}

struct KataGoRknnRunner::Impl {
    rknn_context ctx = 0;
    std::array<int, INPUTS> inputSlots = {{-1, -1, -1}};
    std::array<int, OUTPUTS> outputSlots = {{-1, -1, -1, -1, -1}};
    const int boardSize;
    const int area;
    const std::array<uint32_t, INPUTS> inputCounts;
    const std::array<uint32_t, OUTPUTS> outputCounts;
    std::vector<float> mask;

    explicit Impl(const std::string& path, int board)
        : boardSize(board), area(board * board),
          inputCounts{{static_cast<uint32_t>(SPATIAL * area), GLOBAL, static_cast<uint32_t>(area)}},
          outputCounts{{1, static_cast<uint32_t>(area), 3, 4, static_cast<uint32_t>(area)}},
          mask(static_cast<size_t>(area)) {
        if(board != 9 && board != 13 && board != 19)
            throw std::runtime_error("RKNN model requires 9x9, 13x13 or 19x19");
        std::ifstream file(path, std::ios::binary);
        if(!file) throw std::runtime_error("RKNN model not found: " + path);
        std::vector<char> model((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        if(model.empty()) throw std::runtime_error("Empty RKNN model: " + path);
        checkRknn(rknn_init(&ctx, model.data(), static_cast<uint32_t>(model.size()), 0, nullptr), "rknn_init");
        try {
            checkRknn(rknn_set_core_mask(ctx, RKNN_NPU_CORE_0), "rknn_set_core_mask");
            rknn_input_output_num io = {};
            checkRknn(rknn_query(ctx, RKNN_QUERY_IN_OUT_NUM, &io, sizeof(io)), "RKNN_QUERY_IN_OUT_NUM");
            if(io.n_input != INPUTS || io.n_output != OUTPUTS)
                throw std::runtime_error("Expected three RKNN inputs and five output heads");
            for(uint32_t i=0;i<io.n_input;i++) {
                rknn_tensor_attr attr = {};
                attr.index = i;
                checkRknn(rknn_query(ctx, RKNN_QUERY_INPUT_ATTR, &attr, sizeof(attr)), "RKNN_QUERY_INPUT_ATTR");
                int key = resolveName(attr.name, inputNames, INPUTS);
                if(inputSlots[key] >= 0 || attr.n_elems != inputCounts[key])
                    throw std::runtime_error("Duplicate/mismatched RKNN input: " + std::string(attr.name));
                inputSlots[key] = static_cast<int>(i);
            }
            for(uint32_t i=0;i<io.n_output;i++) {
                rknn_tensor_attr attr = {};
                attr.index = i;
                checkRknn(rknn_query(ctx, RKNN_QUERY_OUTPUT_ATTR, &attr, sizeof(attr)), "RKNN_QUERY_OUTPUT_ATTR");
                int key = resolveName(attr.name, outputNames, OUTPUTS);
                if(outputSlots[key] >= 0 || attr.n_elems != outputCounts[key])
                    throw std::runtime_error("Duplicate/mismatched RKNN output: " + std::string(attr.name));
                outputSlots[key] = static_cast<int>(i);
            }
            for(int index : inputSlots) if(index < 0) throw std::runtime_error("Missing RKNN input");
            for(int index : outputSlots) if(index < 0) throw std::runtime_error("Missing RKNN output");
        } catch(...) {
            rknn_destroy(ctx);
            ctx = 0;
            throw;
        }
    }
    ~Impl() { if(ctx != 0) rknn_destroy(ctx); }

    void infer(const float* spatial, const float* global, float* pass, float* policy,
               float* value, float* score, float* owner) {
        // KataGo spatial plane 0 is the board mask for this NN buffer.
        // InputSpatial is already NHWC: it is transformed by KataGo's symmetry
        // code before reaching this function. No NCHW transpose is necessary.
        for(int i=0;i<area;i++) mask[i] = spatial[i * SPATIAL];
        std::array<const float*, INPUTS> sources = {{spatial, global, mask.data()}};
        std::array<rknn_input, INPUTS> inputs = {};
        for(int key=0;key<INPUTS;key++) {
            int slot = inputSlots[key];
            inputs[slot].index = static_cast<uint32_t>(slot);
            inputs[slot].buf = const_cast<float*>(sources[key]);
            inputs[slot].size = inputCounts[key] * sizeof(float);
            inputs[slot].pass_through = 0;
            inputs[slot].type = RKNN_TENSOR_FLOAT32;
            inputs[slot].fmt = RKNN_TENSOR_NHWC;
        }
        checkRknn(rknn_inputs_set(ctx, INPUTS, inputs.data()), "rknn_inputs_set");
        checkRknn(rknn_run(ctx, nullptr), "rknn_run");
        std::array<rknn_output, OUTPUTS> outputs = {};
        for(int i=0;i<OUTPUTS;i++) { outputs[i].index = static_cast<uint32_t>(i); outputs[i].want_float = 1; }
        checkRknn(rknn_outputs_get(ctx, OUTPUTS, outputs.data(), nullptr), "rknn_outputs_get");
        try {
            std::array<float*, OUTPUTS> destinations = {{pass, policy, value, score, owner}};
            for(int key=0;key<OUTPUTS;key++) {
                const rknn_output& output = outputs[outputSlots[key]];
                const auto* floats = static_cast<const float*>(output.buf);
                if(!floats || output.size != outputCounts[key] * sizeof(float))
                    throw std::runtime_error("RKNN output head has invalid length: " + std::string(outputNames[key]));
                for(uint32_t k=0;k<outputCounts[key];k++) {
                    if(!std::isfinite(floats[k])) throw std::runtime_error("RKNN produced NaN/Inf");
                }
                // This particular g170 10b has a single spatial policy/owner
                // channel. Its NCHW and NHWC flattened board maps are identical.
                std::copy(floats, floats + outputCounts[key], destinations[key]);
            }
        } catch(...) {
            rknn_outputs_release(ctx, OUTPUTS, outputs.data());
            throw;
        }
        checkRknn(rknn_outputs_release(ctx, OUTPUTS, outputs.data()), "rknn_outputs_release");
    }
};

KataGoRknnRunner::KataGoRknnRunner(const std::string& path, int boardSize) : impl_(new Impl(path, boardSize)) {}
KataGoRknnRunner::~KataGoRknnRunner() = default;
void KataGoRknnRunner::infer(const float* s, const float* g, float* p, float* pm,
                            float* v, float* sc, float* o) {
    impl_->infer(s,g,p,pm,v,sc,o);
}
