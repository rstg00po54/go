// Run identical input tensors through RK3588 RKNN and compare against original
// b18 Human SL ONNX CPU outputs. Independent of Android app / KataGo search.
// Shares the exact tensor handling already tested in human_rknn_probe.cpp.
#define main human_rknn_probe_existing_main
#include "human_rknn_probe.cpp"
#undef main

#include <cstring>
#include <limits>

namespace {
void readExact(std::ifstream& file, char* dst, size_t bytes) {
    if(bytes > static_cast<size_t>(std::numeric_limits<std::streamsize>::max()))
        throw std::runtime_error("Reference record too large");
    file.read(dst, static_cast<std::streamsize>(bytes));
    if(!file) throw std::runtime_error("Truncated or invalid CPU reference");
}
uint32_t readU32(std::ifstream& f) {
    unsigned char raw[4]{};
    readExact(f, reinterpret_cast<char*>(raw), sizeof(raw));
    return uint32_t(raw[0]) | (uint32_t(raw[1]) << 8) |
           (uint32_t(raw[2]) << 16) | (uint32_t(raw[3]) << 24);
}
std::vector<float> readFloatArray(std::ifstream& f, uint32_t count) {
    static_assert(sizeof(float) == 4, "Require 32-bit IEEE-754 float");
    std::vector<float> result(count);
    readExact(f, reinterpret_cast<char*>(result.data()), count * sizeof(float));
    for(float v:result) if(!std::isfinite(v))
        throw std::runtime_error("CPU reference has NaN/Inf");
    return result;
}
}

int main(int argc, char** argv) {
    try {
        if(argc != 3) {
            std::cerr << "Usage: human_rknn_parity <model.rknn> <human_b18_cpu_reference.bin>\n";
            return 2;
        }
        std::ifstream reference(argv[2], std::ios::binary);
        if(!reference) throw std::runtime_error("CPU reference file missing");
        char magic[8]{};
        readExact(reference, magic, sizeof(magic));
        if(std::memcmp(magic, "B18REF01", 8) != 0)
            throw std::runtime_error("Invalid CPU fixture magic");
        uint32_t size = readU32(reference), cases = readU32(reference);
        if((size != 9 && size != 13 && size != 19) || cases != 4)
            throw std::runtime_error("Incorrect reference board/case count");
        std::cout << "HUMAN_CPU_REFERENCE_LOADED board=" << size << " cases=" << cases << "\n";
        Probe probe(argv[1], RKNN_NPU_CORE_0, size);
        int badHeads = 0;
        double globalMax = 0.0;
        // Initial float16 NPU smoke tolerance; tighten only after measuring
        // systematic errors. Never silently relax this to force PASS.
        constexpr double ABS_TOL = 0.05;
        constexpr double REL_TOL = 0.03;
        for(uint32_t trial=0;trial<cases;trial++) {
            for(int i=0;i<INPUT_COUNT;i++)
                probe.input[i] = readFloatArray(reference, probe.inputCounts[i]);
            std::array<std::vector<float>,OUTPUT_COUNT> expected;
            for(int i=0;i<OUTPUT_COUNT;i++)
                expected[i] = readFloatArray(reference, probe.outputCounts[i]);
            // InputSpatial was serialized NHWC; global and meta also match
            // RKNN NHWC 1x1 input tensors. Outputs stay original ONNX NCHW.
            const auto actual = probe.infer(0, true);
            for(int key=0;key<OUTPUT_COUNT;key++) {
                if(actual[key].size()!=expected[key].size())
                    throw std::runtime_error("Head count mismatch");
                double maxAbs=0, sumAbs=0;
                uint32_t badValues=0;
                size_t maxIndex=0;
                for(size_t i=0;i<expected[key].size();i++) {
                    double error=std::abs(double(actual[key][i])-double(expected[key][i]));
                    sumAbs+=error;
                    if(error>maxAbs){maxAbs=error;maxIndex=i;}
                    if(error>ABS_TOL + REL_TOL*std::abs(double(expected[key][i])))
                        badValues++;
                }
                if(badValues) badHeads++;
                globalMax=std::max(globalMax,maxAbs);
                std::cout << "HUMAN_HEAD_PARITY case=" << trial
                          << " name=" << OUT_NAMES[key]
                          << " maxAbs=" << std::fixed << std::setprecision(7) << maxAbs
                          << " meanAbs=" << sumAbs/expected[key].size()
                          << " worstIndex=" << maxIndex
                          << " cpu=" << expected[key][maxIndex]
                          << " npu=" << actual[key][maxIndex]
                          << " outOfTolerance=" << badValues
                          << " status=" << (badValues ? "FAIL" : "PASS") << "\n";
            }
        }
        if(reference.peek()!=std::char_traits<char>::eof())
            throw std::runtime_error("Unexpected trailing reference data");
        std::cout << "HUMAN_CPU_NPU_PARITY " << (badHeads ? "FAIL" : "PASS")
                  << " cases=" << cases << " heads=" << cases*OUTPUT_COUNT
                  << " badHeads=" << badHeads
                  << " maxAbs=" << std::fixed << std::setprecision(7) << globalMax
                  << " atol=" << ABS_TOL << " rtol=" << REL_TOL << "\n";
        std::cout << "NOTE: test compares synthetic CPU/NPU tensors; valid native"
                     " KataGo rank features and real-game accuracy are not tested.\n";
        return badHeads ? 1 : 0;
    } catch(const std::exception& e) {
        std::cerr << "HUMAN_CPU_NPU_PARITY_FAIL reason=" << e.what() << "\n";
        return 1;
    }
}
