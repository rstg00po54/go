#include "main.h"
#include <sstream>

#ifdef NO_GIT_REVISION
#define GIT_REVISION "<omitted>"
#else
#include "program/gitinfo.h"
#endif

#include "core/using.h"

string Version::getKataGoVersion() {
  return string("1.15.0");
}

string Version::getKataGoVersionForHelp() {
  return string("KataGo v1.15.0");
}

string Version::getKataGoVersionFullInfo() {
  ostringstream out;
  out << Version::getKataGoVersionForHelp() << endl;
  out << "Git revision: " << Version::getGitRevision() << endl;
  out << "Compile Time: " << __DATE__ << " " << __TIME__ << endl;
#if defined(USE_CUDA_BACKEND)
  out << "Using CUDA backend" << endl;
#if defined(CUDA_TARGET_VERSION)
#define STRINGIFY(x) #x
#define STRINGIFY2(x) STRINGIFY(x)
  out << "Compiled with CUDA version " << STRINGIFY2(CUDA_TARGET_VERSION) << endl;
#endif
#elif defined(USE_TENSORRT_BACKEND)
  out << "Using TensorRT backend" << endl;
#elif defined(USE_OPENCL_BACKEND)
  out << "Using OpenCL backend" << endl;
#elif defined(USE_EIGEN_BACKEND)
  out << "Using Eigen(CPU) backend" << endl;
#else
  out << "Using dummy backend" << endl;
#endif

#if defined(USE_AVX2)
  out << "Compiled with AVX2 and FMA instructions" << endl;
#endif
#if defined(COMPILE_MAX_BOARD_LEN)
  out << "Compiled to allow boards of size up to " << COMPILE_MAX_BOARD_LEN << endl;
#endif
#if defined(CACHE_TENSORRT_PLAN) && defined(USE_TENSORRT_BACKEND)
  out << "Compiled with TensorRT plan cache" << endl;
#elif defined(BUILD_DISTRIBUTED)
  out << "Compiled to support contributing to online distributed selfplay" << endl;
#endif

  return out.str();
}

string Version::getGitRevision() {
  return string(GIT_REVISION);
}

string Version::getGitRevisionWithBackend() {
  string s = string(GIT_REVISION);

#if defined(USE_CUDA_BACKEND)
  s += "-cuda";
#elif defined(USE_TENSORRT_BACKEND)
  s += "-trt";
#elif defined(USE_OPENCL_BACKEND)
  s += "-opencl";
#elif defined(USE_EIGEN_BACKEND)
  s += "-eigen";
#else
  s += "-dummy";
#endif
  return s;
}
