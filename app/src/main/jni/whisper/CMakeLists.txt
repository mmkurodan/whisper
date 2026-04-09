cmake_minimum_required(VERSION 3.22.1)

project(whisper-android LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_ROOT ${CMAKE_SOURCE_DIR}/../../../../../third_party/whisper.cpp)
set(GGML_ROOT ${WHISPER_ROOT}/ggml)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_MUSA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_WEBGPU OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_OPENVINO OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(GGML_CANN OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_HBM OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS_3RD_PARTY OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${GGML_ROOT})
FetchContent_MakeAvailable(ggml)

find_library(android-lib android)
find_library(log-lib log)

add_library(
    whisper-jni
    SHARED
    jni.c
    ${WHISPER_ROOT}/src/whisper.cpp
)

target_compile_definitions(
    whisper-jni
    PRIVATE
    GGML_USE_CPU
    WHISPER_VERSION=\"android-local\"
)

target_include_directories(
    whisper-jni
    PRIVATE
    ${WHISPER_ROOT}/include
    ${WHISPER_ROOT}/src
    ${GGML_ROOT}/include
    ${GGML_ROOT}/src
    ${GGML_ROOT}/src/ggml-cpu
)

target_link_libraries(
    whisper-jni
    PRIVATE
    ggml
    ${android-lib}
    ${log-lib}
)
