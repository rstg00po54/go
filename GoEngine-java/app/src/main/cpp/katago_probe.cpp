#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <string>

namespace {

using ClGetPlatformIDs = int (*)(uint32_t, void**, uint32_t*);

std::string checkOpenCL() {
    std::string result;
    const char* candidates[] = {"libOpenCL.so", "/vendor/lib64/libOpenCL.so"};
    for (const char* library : candidates) {
        dlerror();
        void* handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            const char* error = dlerror();
            result += std::string(library) + ": dlopen failed: " + (error ? error : "unknown") + "; ";
            continue;
        }

        dlerror();
        auto getPlatforms = reinterpret_cast<ClGetPlatformIDs>(dlsym(handle, "clGetPlatformIDs"));
        const char* symbolError = dlerror();
        if (symbolError != nullptr || getPlatforms == nullptr) {
            result += std::string(library) + ": loaded but clGetPlatformIDs missing: " +
                      (symbolError ? symbolError : "unknown") + "; ";
            dlclose(handle);
            continue;
        }

        uint32_t platformCount = 0;
        int errorCode = getPlatforms(0, nullptr, &platformCount);
        result += std::string(library) + ": loaded; clGetPlatformIDs error=" +
                  std::to_string(errorCode) + ", platforms=" + std::to_string(platformCount);
        dlclose(handle);
        return result;
    }
    return result.empty() ? "OpenCL probe did not check any libraries" : result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_badukai_engine_KataGoOpenCLProbe_nativeProbe(JNIEnv* env, jclass) {
    const std::string result = checkOpenCL();
    return env->NewStringUTF(result.c_str());
}
