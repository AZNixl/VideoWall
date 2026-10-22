# 用来**一次性**编出 libwhisper_jni.so，结果提交到 app/src/main/jniLibs/<abi>/。
#
# 为什么不接到 Gradle 的 externalNativeBuild 里：那样 CI 每次构建都要装 NDK、编一遍
# ggml + whisper（几分钟），而且 AGP 自动下载 NDK 在 GitHub Actions 上不稳。
# 编好的 .so 一共几 MB，进 git 更省事，CI 也就不用 NDK 了。
#
# 重新编（在 Windows + 已装 NDK 的机器上，cmake/ninja 用 SDK 自带的那份）：
#
#   SDK=%LOCALAPPDATA%\Android\Sdk
#   NDK=%SDK%\ndk\28.0.13004108
#   SDK\cmake\3.22.1\bin\cmake.exe -G Ninja ^
#       -DCMAKE_TOOLCHAIN_FILE=%NDK%\build\cmake\android.toolchain.cmake ^
#       -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 ^
#       -DCMAKE_BUILD_TYPE=Release -B build-arm64 -S .
#   SDK\cmake\3.22.1\bin\cmake.exe --build build-arm64
#
# 换个 ANDROID_ABI 和 -B 目录，就是另一个 ABI。产物在
# build-<abi>/libwhisper_jni.so，拷到 app/src/main/jniLibs/<abi>/。

cmake_minimum_required(VERSION 3.22)
project(whisper_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp 源码目录（本文件同级放一份，或者用 -DWHISPER_SRC=... 指定）
if(NOT DEFINED WHISPER_SRC)
    set(WHISPER_SRC "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp")
endif()

if(NOT EXISTS "${WHISPER_SRC}/include/whisper.h")
    message(FATAL_ERROR "找不到 whisper.cpp 源码：${WHISPER_SRC}")
endif()

# 交叉编译时这几个选项很关键：
#   GGML_NATIVE=OFF  —— 否则会按宿主机的 CPU 指令集编译，装到手机上直接非法指令
#   GGML_OPENMP=OFF  —— 安卓的 libomp 部署麻烦，收益也不大
#   不要 examples / tests / server
set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_SRC}" "${CMAKE_BINARY_DIR}/whisper_build" EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE "${WHISPER_SRC}/include")
target_link_libraries(whisper_jni PRIVATE whisper log)

# 只保留 arm64 需要的符号体积；-O3 + 去掉异常/RTTI 能省一点
target_compile_options(whisper_jni PRIVATE -O3 -fno-exceptions -fno-rtti)

# 让 .so 只导出 JNI 入口，别把 whisper/ggml 的符号也导出去（体积 + 符号冲突）
target_link_options(whisper_jni PRIVATE
        "-Wl,--exclude-libs,ALL"
        "-Wl,--gc-sections")
