# 编 libllama_jni.so（字幕翻译引擎），结果提交到 app/src/main/jniLibs/<abi>/。
# 构建方式与 ../whisper-jni 一致，产物约 3–5MB/ABI。
#
#   SDK=%LOCALAPPDATA%\Android\Sdk
#   NDK=%SDK%\ndk\28.0.13004108
#   SDK\cmake\3.22.1\bin\cmake.exe -G Ninja ^
#       -DCMAKE_MAKE_PROGRAM=%SDK%\cmake\3.22.1\bin\ninja.exe ^
#       -DCMAKE_TOOLCHAIN_FILE=%NDK%\build\cmake\android.toolchain.cmake ^
#       -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 ^
#       -DCMAKE_BUILD_TYPE=Release -DLLAMA_SRC=<llama.cpp 源码目录> ^
#       -B build-arm64 -S .
#   SDK\cmake\3.22.1\bin\cmake.exe --build build-arm64
#   %NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe --strip-unneeded build-arm64\libllama_jni.so
#
# 不接 Gradle externalNativeBuild 的理由同 whisper：CI 不该为了它装 NDK。

cmake_minimum_required(VERSION 3.22)
project(llama_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_SRC)
    set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
endif()
if(NOT EXISTS "${LLAMA_SRC}/include/llama.h")
    message(FATAL_ERROR "找不到 llama.cpp 源码：${LLAMA_SRC}")
endif()

# 交叉编译必设项：
#   GGML_NATIVE=OFF   否则按宿主机 CPU 指令集编，手机上非法指令
#   LLAMA_CURL=OFF    Android 上没有 curl，也不需要下模型
#   示例/测试/服务端全关
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama_build" EXCLUDE_FROM_ALL)

add_library(llama_jni SHARED llama_jni.cpp)
target_include_directories(llama_jni PRIVATE "${LLAMA_SRC}/include")
target_link_libraries(llama_jni PRIVATE llama log)

target_compile_options(llama_jni PRIVATE -O3 -fno-exceptions -fno-rtti)
target_link_options(llama_jni PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
