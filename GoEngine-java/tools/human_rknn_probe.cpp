// Standalone RK3588 Android NPU smoke for original-weight b18 Human SL.
// This intentionally does not alter the working 10b KataGo Android backend.
#include "rknn_api.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr int N = 9, AREA = N*N, INPUT_COUNT = 4, OUTPUT_COUNT = 5;
constexpr const char* IN_NAMES[INPUT_COUNT] = {
    "InputSpatial", "InputGlobal", "InputMeta", "InputMask"};
constexpr const char* OUT_NAMES[OUTPUT_COUNT] = {
    "OutputPolicyPass", "OutputPolicy", "OutputValue",
    "OutputScoreValue", "OutputOwnership"};
constexpr std::array<uint32_t, INPUT_COUNT> IN_COUNTS = {
    22*AREA, 19, 192, AREA};
constexpr std::array<uint32_t, OUTPUT_COUNT> OUT_COUNTS = {
    2, 2*AREA, 3, 6, AREA};

void check(int rc, const char* where) {
    if(rc != RKNN_SUCC)
        throw std::runtime_error(std::string(where) + " failed rc=" + std::to_string(rc));
}
int nameIndex(const char* name, const char* const* names, int count) {
    if(!name) throw std::runtime_error("Unnamed RKNN tensor");
    for(int i=0;i<count;i++)
        if(std::string(name).find(names[i]) != std::string::npos) return i;
    throw std::runtime_error(std::string("Unknown RKNN tensor name: ") + name);
}
std::string dims(const rknn_tensor_attr& a) {
    std::string out;
    for(uint32_t i=0;i<a.n_dims;i++) {
        if(i) out += "x";
        out += std::to_string(a.dims[i]);
    }
    return out;
}
struct Context {
    rknn_context ctx = 0;
    ~Context() { if(ctx) rknn_destroy(ctx); }
};

struct Probe {
    Context runtime;
    int board, area;
    std::array<uint32_t,INPUT_COUNT> inputCounts;
    std::array<uint32_t,OUTPUT_COUNT> outputCounts;
    std::array<int, INPUT_COUNT> inputSlot{{-1,-1,-1,-1}};
    std::array<int, OUTPUT_COUNT> outputSlot{{-1,-1,-1,-1,-1}};
    std::array<std::vector<float>,INPUT_COUNT> input;
    explicit Probe(const char* path, rknn_core_mask coreMask = RKNN_NPU_CORE_0, int boardSize=9)
      : board(boardSize), area(boardSize*boardSize),
        inputCounts{{static_cast<uint32_t>(22*area),19,192,static_cast<uint32_t>(area)}},
        outputCounts{{2,static_cast<uint32_t>(2*area),3,6,static_cast<uint32_t>(area)}} {
        if(boardSize != 9 && boardSize != 11 && boardSize != 13 && boardSize != 15 && boardSize != 19)
            throw std::runtime_error("Human probe only supports 9/11/13/15/19");
        std::ifstream file(path, std::ios::binary);
        if(!file) throw std::runtime_error(std::string("Cannot open ") + path);
        std::vector<char> model((std::istreambuf_iterator<char>(file)), {});
        if(model.empty()) throw std::runtime_error("Empty RKNN model");
        std::cout << "HUMAN_RKNN_LOAD bytes=" << model.size() << "\n";
        check(rknn_init(&runtime.ctx, model.data(), uint32_t(model.size()), 0, nullptr),
              "rknn_init");
        check(rknn_set_core_mask(runtime.ctx, coreMask), "rknn_set_core_mask");
        rknn_input_output_num io{};
        check(rknn_query(runtime.ctx, RKNN_QUERY_IN_OUT_NUM, &io, sizeof(io)),
              "RKNN_QUERY_IN_OUT_NUM");
        if(io.n_input != INPUT_COUNT || io.n_output != OUTPUT_COUNT)
            throw std::runtime_error("Not Human b18 four-input five-head graph");
        for(uint32_t i=0;i<io.n_input;i++) {
            rknn_tensor_attr a{};
            a.index = i;
            check(rknn_query(runtime.ctx, RKNN_QUERY_INPUT_ATTR, &a, sizeof(a)),
                  "RKNN_QUERY_INPUT_ATTR");
            int key = nameIndex(a.name, IN_NAMES, INPUT_COUNT);
            std::cout << "HUMAN_INPUT slot=" << i << " name=" << a.name
                      << " dims=" << dims(a) << " elements=" << a.n_elems
                      << " fmt=" << int(a.fmt) << " type=" << int(a.type) << "\n";
            if(inputSlot[key] >= 0 || a.n_elems != inputCounts[key])
                throw std::runtime_error("Incorrect/duplicate input " + std::string(a.name));
            inputSlot[key] = i;
        }
        for(uint32_t i=0;i<io.n_output;i++) {
            rknn_tensor_attr a{};
            a.index = i;
            check(rknn_query(runtime.ctx, RKNN_QUERY_OUTPUT_ATTR, &a, sizeof(a)),
                  "RKNN_QUERY_OUTPUT_ATTR");
            int key = nameIndex(a.name, OUT_NAMES, OUTPUT_COUNT);
            std::cout << "HUMAN_OUTPUT slot=" << i << " name=" << a.name
                      << " dims=" << dims(a) << " elements=" << a.n_elems
                      << " fmt=" << int(a.fmt) << " type=" << int(a.type) << "\n";
            if(outputSlot[key] >= 0 || a.n_elems != outputCounts[key])
                throw std::runtime_error("Incorrect/duplicate output " + std::string(a.name));
            outputSlot[key] = i;
        }
        for(int x:inputSlot) if(x<0) throw std::runtime_error("Missing Human input");
        for(int x:outputSlot) if(x<0) throw std::runtime_error("Missing Human head");
        for(int i=0;i<INPUT_COUNT;i++) input[i].assign(inputCounts[i], 0.0f);
        // NHWC: feature plane 0 holds the board mask, as in the working 10b bridge.
        for(int point=0;point<area;point++) input[0][point*22] = 1.0f;
        std::fill(input[3].begin(), input[3].end(), 1.0f);
        std::cout << "HUMAN_RKNN_READY board=" << board << " inputs=4 outputs=5\n";
    }

    std::array<std::vector<float>,OUTPUT_COUNT> infer(int rankFeature, bool preserveMetadata=false, bool quiet=false, std::array<double,3>* phases=nullptr) {
        // In parity mode, all four inputs come directly from the original
        // ONNX CPU fixture. Do not overwrite its 192-dimensional metadata.
        if(!preserveMetadata) {
            std::fill(input[2].begin(), input[2].end(), 0.0f);
            if(rankFeature < 0 || rankFeature >= 192)
                throw std::runtime_error("Invalid rank-feature index");
            input[2][rankFeature] = 1.0f;
        }
        std::array<rknn_input, INPUT_COUNT> ins{};
        for(int i=0;i<INPUT_COUNT;i++) {
            int slot=inputSlot[i];
            ins[slot].index = uint32_t(slot);
            ins[slot].buf = input[i].data();
            ins[slot].size = uint32_t(input[i].size()*sizeof(float));
            ins[slot].pass_through = 0;
            ins[slot].type = RKNN_TENSOR_FLOAT32;
            ins[slot].fmt = RKNN_TENSOR_NHWC;
        }
        const auto start=std::chrono::steady_clock::now();
        check(rknn_inputs_set(runtime.ctx, INPUT_COUNT, ins.data()), "rknn_inputs_set");
        const auto afterInput=std::chrono::steady_clock::now();
        check(rknn_run(runtime.ctx, nullptr), "rknn_run");
        const auto afterRun=std::chrono::steady_clock::now();
        std::array<rknn_output, OUTPUT_COUNT> outs{};
        for(int i=0;i<OUTPUT_COUNT;i++) {
            outs[i].index=uint32_t(i);
            outs[i].want_float=1;
        }
        check(rknn_outputs_get(runtime.ctx, OUTPUT_COUNT, outs.data(), nullptr),
              "rknn_outputs_get");
        std::array<std::vector<float>,OUTPUT_COUNT> values;
        bool outputsAcquired=true;
        try {
            for(int key=0;key<OUTPUT_COUNT;key++) {
                const rknn_output& o=outs[outputSlot[key]];
                if(!o.buf || o.size != outputCounts[key]*sizeof(float))
                    throw std::runtime_error(std::string("Bad head length: ")+OUT_NAMES[key]);
                auto p=static_cast<const float*>(o.buf);
                values[key].assign(p, p+OUT_COUNTS[key]);
                for(float v:values[key])
                    if(!std::isfinite(v)) throw std::runtime_error("Human NPU NaN/Inf");
            }
        } catch(...) {
            if(outputsAcquired) rknn_outputs_release(runtime.ctx, OUTPUT_COUNT, outs.data());
            throw;
        }
        check(rknn_outputs_release(runtime.ctx, OUTPUT_COUNT, outs.data()),
              "rknn_outputs_release");
        const auto afterOutput=std::chrono::steady_clock::now();
        if(phases) {
            const auto ms=[](auto a,auto b) {
                return std::chrono::duration<double,std::milli>(b-a).count();
            };
            *phases={{ms(start,afterInput),ms(afterInput,afterRun),ms(afterRun,afterOutput)}};
        }
        auto elapsed=std::chrono::duration_cast<std::chrono::microseconds>(
            afterOutput-start).count();
        if(!quiet) std::cout << "HUMAN_INFER_OK metaIndex=" << rankFeature
                  << " totalMs=" << std::fixed << std::setprecision(3)
                  << elapsed/1000.0 << "\n";
        if(!quiet) for(int key=0;key<OUTPUT_COUNT;key++) {
            float first=values[key].front();
            float maxAbs=0.0f;
            for(float f:values[key]) maxAbs=std::max(maxAbs,std::abs(f));
            std::cout << "HUMAN_HEAD metaIndex=" << rankFeature
                      << " name=" << OUT_NAMES[key] << " count=" << values[key].size()
                      << " first=" << first << " maxAbs=" << maxAbs << "\n";
        }
        return values;
    }
};
}

int main(int argc, char** argv) {
    try {
        if(argc != 2 && argc != 3) {
            std::cerr << "Usage: human_rknn_probe <model.rknn> [9|11|13|15|19]\n";
            return 2;
        }
        int board = argc == 3 ? std::stoi(argv[2]) : 9;
        Probe probe(argv[1], RKNN_NPU_CORE_0, board);
        auto a=probe.infer(0);
        auto b=probe.infer(17);
        for(int key=0;key<OUTPUT_COUNT;key++) {
            float delta=0;
            for(size_t i=0;i<a[key].size();i++)
                delta=std::max(delta,std::abs(a[key][i]-b[key][i]));
            std::cout << "HUMAN_META_SENSITIVITY head=" << OUT_NAMES[key]
                      << " maxAbs=" << delta << "\n";
        }
        std::cout << "HUMAN_RKNN_SMOKE_PASS board=" << board << " noCpuFallback=1\n"
                  << "NOTE: synthetic inputs only; native KataGo rank encoding and "
                     "CPU/NPU parity remain unverified.\n";
        return 0;
    } catch(const std::exception& ex) {
        std::cerr << "HUMAN_RKNN_SMOKE_FAIL: " << ex.what() << "\n";
        return 1;
    }
}
