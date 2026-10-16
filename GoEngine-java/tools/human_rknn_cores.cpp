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
        if(argc<2 || argc>4) {
            std::cerr<<"Usage: human_rknn_cores <model.rknn> [runs=24] [warmup=5]\n";
            return 2;
        }
        int runs=argc>=3?std::stoi(argv[2]):24;
        int warmup=argc>=4?std::stoi(argv[3]):5;
        if(runs<5 || runs>200 || warmup<0 || warmup>50)
            throw std::runtime_error("runs 5-200, warmup 0-50");
        std::cout<<"HUMAN_BENCH_START runs="<<runs<<" warmup="<<warmup
                 <<" board=9 model=original_b18\n";
        const Result a=measure(argv[1],"core0_before",RKNN_NPU_CORE_0,warmup,runs);
        bool supported=false;
        Result b;
        std::string unsupportedReason;
        try {
            b=measure(argv[1],"core012",RKNN_NPU_CORE_0_1_2,warmup,runs);
            supported=true;
        } catch(const std::exception& ex) {
            unsupportedReason=ex.what();
            std::cout<<"HUMAN_BENCH_CORE3_UNAVAILABLE reason="<<unsupportedReason<<"\n";
        }
        const Result c=measure(argv[1],"core0_after",RKNN_NPU_CORE_0,warmup,runs);
        const double baselineDrift=compare(a,c);
        double core3Delta=0;
        if(supported) core3Delta=std::max(compare(a,b),compare(c,b));
        constexpr double MAX_ALLOWED_UNSCALED_DELTA=0.01;
        if(baselineDrift>MAX_ALLOWED_UNSCALED_DELTA ||
           (supported && core3Delta>MAX_ALLOWED_UNSCALED_DELTA))
            throw std::runtime_error("Cross-core output numerical consistency failed");
        const double oneCoreMedian=(quantile(a.runs,0.5)+quantile(c.runs,0.5))/2;
        const double threeCoreMedian=supported?quantile(b.runs,0.5):0;
        std::cout<<"HUMAN_BENCH_RESULT status=PASS cores3Available="<<(supported?1:0)
                 <<" core0RunMedianMs="<<oneCoreMedian
                 <<" core3RunMedianMs="<<threeCoreMedian
                 <<" core3VsCore0Ratio="<<(supported?threeCoreMedian/oneCoreMedian:0.0)
                 <<" core0BeforeMs="<<quantile(a.runs,0.5)
                 <<" core0AfterMs="<<quantile(c.runs,0.5)
                 <<" maxOutputDelta="<<std::max(baselineDrift,core3Delta)
                 <<" samplesPerPhase="<<runs<<"\n";
        std::cout<<"NOTE: identical synthetic 9x9 inputs; test scheduling/latency only."
                    " Real KataGo move times and Human rank behavior are not part of this benchmark.\n";
        return 0;
    } catch(const std::exception& ex) {
        std::cerr<<"HUMAN_BENCH_RESULT status=FAIL reason="<<ex.what()<<"\n";
        return 1;
    }
}
