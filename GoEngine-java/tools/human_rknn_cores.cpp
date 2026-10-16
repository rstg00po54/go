// Experimental, standalone NPU core scheduling benchmark. Does not touch APK.
// Model and input tensors are identical in each phase. A/B/A order helps
// identify clock or temperature drift instead of misattributing all changes
// to multi-core scheduling.
#define main human_rknn_probe_original_main
#include "human_rknn_probe.cpp"
#undef main
#include <numeric>
#include <sstream>

namespace {
struct Result {
    std::string label;
    std::vector<double> runs, totals;
    std::array<std::vector<float>,OUTPUT_COUNT> first;
};
double quantile(std::vector<double> sorted, double q) {
    if(sorted.empty()) throw std::runtime_error("No benchmark samples");
    std::sort(sorted.begin(), sorted.end());
    const double position=q*(sorted.size()-1);
    const size_t low=static_cast<size_t>(position);
    const size_t high=std::min(low+1,sorted.size()-1);
    return sorted[low]+(sorted[high]-sorted[low])*(position-low);
}
Result measure(const char* path, const char* label, rknn_core_mask mask,
               int warmup, int runs) {
    Probe probe(path,mask);
    Result result;
    result.label=label;
    for(int i=0;i<warmup+runs;i++) {
        std::array<double,3> phases{{0,0,0}};
        auto heads=probe.infer(17,false,true,&phases);
        if(i==warmup) result.first=std::move(heads);
        if(i>=warmup) {
            const double total=phases[0]+phases[1]+phases[2];
            if(phases[1]<=0 || phases[1]>total)
                throw std::runtime_error("Invalid benchmark sample time");
            result.runs.push_back(phases[1]);
            result.totals.push_back(total);
            std::cout << "HUMAN_BENCH_SAMPLE phase=" << label
                      << " index=" << i-warmup << " inputMs=" << std::fixed
                      << std::setprecision(3) << phases[0]
                      << " runMs=" << phases[1] << " outputMs=" << phases[2]
                      << " totalMs=" << total << "\n";
        }
    }
    std::cout << "HUMAN_BENCH_SUMMARY phase=" << label
              << " runs=" << runs << " warmup=" << warmup
              << " medianRunMs=" << quantile(result.runs,0.5)
              << " p90RunMs=" << quantile(result.runs,0.9)
              << " medianTotalMs=" << quantile(result.totals,0.5)
              << " minRunMs=" << *std::min_element(result.runs.begin(),result.runs.end())
              << " maxRunMs=" << *std::max_element(result.runs.begin(),result.runs.end())
              << "\n";
    return result;
}
double compare(const Result& a, const Result& b) {
    double delta=0;
    for(size_t h=0;h<OUTPUT_COUNT;h++) {
        if(a.first[h].size()!=b.first[h].size())
            throw std::runtime_error("NPU core choice changed output sizes");
        double headDelta=0;
        for(size_t i=0;i<a.first[h].size();i++) {
            headDelta=std::max(headDelta,
                std::abs(double(a.first[h][i])-double(b.first[h][i])));
        }
        std::cout<<"HUMAN_BENCH_HEAD_COMPARE a="<<a.label<<" b="<<b.label
                 <<" head="<<OUT_NAMES[h]<<" maxAbs="<<headDelta<<"\n";
        delta=std::max(delta,headDelta);
    }
    return delta;
}
}

int main(int argc, char** argv) {
    try {
        if(argc<2 || argc>5) {
            std::cerr<<"Usage: human_rknn_cores <model.rknn> [runs=24] [warmup=5] [multi|individual]\n";
            return 2;
        }
        const int runs=argc>=3?std::stoi(argv[2]):24;
        const int warmup=argc>=4?std::stoi(argv[3]):5;
        const std::string mode=argc>=5?argv[4]:"multi";
        if(runs<5 || runs>200 || warmup<0 || warmup>50 ||
           (mode!="multi" && mode!="individual"))
            throw std::runtime_error("runs 5-200, warmup 0-50, mode multi|individual");
        std::cout<<"HUMAN_BENCH_START mode="<<mode<<" runs="<<runs<<" warmup="<<warmup
                 <<" board=9 model=original_b18\n";
        const Result before=measure(argv[1],"core0_before",RKNN_NPU_CORE_0,warmup,runs);
        std::vector<Result> candidateResults;
        const std::vector<std::pair<const char*,rknn_core_mask>> candidates =
          mode=="individual" ?
          std::vector<std::pair<const char*,rknn_core_mask>>{
              {"core1",RKNN_NPU_CORE_1},{"core2",RKNN_NPU_CORE_2}} :
          std::vector<std::pair<const char*,rknn_core_mask>>{
              {"core012",RKNN_NPU_CORE_0_1_2}};
        for(const auto& candidate:candidates) {
            try {
                candidateResults.push_back(measure(argv[1],candidate.first,candidate.second,warmup,runs));
            } catch(const std::exception& ex) {
                std::cout<<"HUMAN_BENCH_CORE_UNAVAILABLE phase="<<candidate.first
                         <<" reason="<<ex.what()<<"\n";
            }
        }
        const Result after=measure(argv[1],"core0_after",RKNN_NPU_CORE_0,warmup,runs);
        constexpr double MAX_ALLOWED_UNSCALED_DELTA=0.01;
        double worstDelta=compare(before,after);
        for(const Result& candidate:candidateResults) {
            worstDelta=std::max(worstDelta,compare(before,candidate));
            worstDelta=std::max(worstDelta,compare(after,candidate));
        }
        if(worstDelta>MAX_ALLOWED_UNSCALED_DELTA)
            throw std::runtime_error("Cross-core output numerical consistency failed");
        const double baseline=(quantile(before.runs,0.5)+quantile(after.runs,0.5))/2;
        const auto medianFor=[&](const char* label)->double {
            for(const Result& result:candidateResults)
                if(result.label==label) return quantile(result.runs,0.5);
            return 0;
        };
        const double three=medianFor("core012"),one=medianFor("core1"),two=medianFor("core2");
        std::cout<<"HUMAN_BENCH_RESULT status=PASS mode="<<mode
                 <<" cores3Available="<<(three>0?1:0)
                 <<" core1Available="<<(one>0?1:0)
                 <<" core2Available="<<(two>0?1:0)
                 <<" core0RunMedianMs="<<baseline
                 <<" core3RunMedianMs="<<three
                 <<" core3VsCore0Ratio="<<(three>0?three/baseline:0.0)
                 <<" core1RunMedianMs="<<one
                 <<" core2RunMedianMs="<<two
                 <<" core0BeforeMs="<<quantile(before.runs,0.5)
                 <<" core0AfterMs="<<quantile(after.runs,0.5)
                 <<" maxOutputDelta="<<worstDelta
                 <<" samplesPerPhase="<<runs<<"\n";
        std::cout<<"NOTE: Same fixed synthetic 9x9 input; scheduling/latency only. "
                    "No change to Android app.\n";
        return 0;
    } catch(const std::exception& ex) {
        std::cerr<<"HUMAN_BENCH_RESULT status=FAIL reason="<<ex.what()<<"\n";
        return 1;
    }
}
