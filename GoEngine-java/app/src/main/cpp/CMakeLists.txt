cmake_minimum_required(VERSION 3.18.1)
project(katago_android_probe LANGUAGES CXX)

# Lightweight JNI/OpenCL access probe only. KataGo still uses the existing
# ProcessBuilder executable until the real JNI engine migration is complete.
add_library(katago_probe SHARED katago_probe.cpp)
target_compile_features(katago_probe PRIVATE cxx_std_17)
target_link_libraries(katago_probe PRIVATE dl)
