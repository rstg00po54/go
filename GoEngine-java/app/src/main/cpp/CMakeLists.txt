cmake_minimum_required(VERSION 3.18)
project(katago_neuron_jni LANGUAGES CXX)

add_library(katago_neuron_jni SHARED neuron_katago_jni.cpp)
target_compile_features(katago_neuron_jni PRIVATE cxx_std_17)
target_compile_options(katago_neuron_jni PRIVATE -Wall -Wextra)
target_link_libraries(katago_neuron_jni PRIVATE dl)
# The MediaTek Neuron Adapter is loaded at runtime with dlopen.
# No proprietary library or Qualcomm dependency is linked into the APK.
