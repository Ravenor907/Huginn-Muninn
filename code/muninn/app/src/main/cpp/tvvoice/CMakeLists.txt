# Kids' TV voice processing: libmuninn_tvvoice.so, loaded only on a Kids' TV (tv/TvVoice.kt). Huginn builds the
# same code (tvvoice_core) for echo cancellation in calls on Linux, SteamOS and Windows (src/call/audioio.cpp).
#  - WebRTC Audio Processing 1.3 (BSD-3-Clause): AEC3 echo canceller, high-pass filter, AGC2, noise suppressor.
#  - RNNoise 0.2 (BSD-3-Clause): voice-focused noise suppression.
#  - Abseil (Apache-2.0), headers only, which WebRTC's code includes.
# The sources are fetched and checked by Gradle (task fetchTvVoice in app/build.gradle.kts, pinned versions
# and SHA-256) and passed in as WEBRTC_APM_SRC (its webrtc/ folder), ABSEIL_SRC and RNNOISE_SRC.
# A library of its own, so RNNoise's copies of a few CELT functions never meet libopus's.
cmake_minimum_required(VERSION 3.18)
project(muninn_tvvoice C CXX)

foreach(dir WEBRTC_APM_SRC ABSEIL_SRC RNNOISE_SRC)
  if(NOT ${dir} OR NOT EXISTS "${${dir}}")
    message(FATAL_ERROR "${dir} is not set or missing: ${${dir}}")
  endif()
endforeach()

# Plain C and C++ here: when Huginn builds this, Qt's moc/uic/rcc have nothing to do.
set(CMAKE_AUTOMOC OFF)
set(CMAKE_AUTOUIC OFF)
set(CMAKE_AUTORCC OFF)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(${CMAKE_CURRENT_LIST_DIR}/webrtc_apm_sources.cmake)
if(WIN32)
  # Windows has its own reader-writer lock (as webrtc-audio-processing's meson build picks it).
  list(TRANSFORM WEBRTC_APM_SOURCES REPLACE "rw_lock_posix\\.cc$" "rw_lock_win.cc")
endif()
list(TRANSFORM WEBRTC_APM_SOURCES PREPEND "${WEBRTC_APM_SRC}/")

if(WIN32)
  # The defines webrtc-audio-processing's meson build uses on Windows.
  set(defs WEBRTC_LIBRARY_IMPL NDEBUG WEBRTC_APM_DEBUG_DUMP=0 WEBRTC_WIN _WIN32 NOMINMAX _USE_MATH_DEFINES __STDC_FORMAT_MACROS=1)
else()
  set(defs WEBRTC_LIBRARY_IMPL NDEBUG WEBRTC_APM_DEBUG_DUMP=0 WEBRTC_POSIX WEBRTC_LINUX _GNU_SOURCE)
endif()
if(ANDROID)
  list(APPEND defs WEBRTC_ANDROID)
endif()
# Only for a tree whose rtc_base/system/arch.h has its detection taken out (the host test may use one).
if(TVVOICE_ARCH_DEFINES)
  list(APPEND defs WEBRTC_ARCH_LITTLE_ENDIAN)
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    list(APPEND defs WEBRTC_ARCH_X86_FAMILY WEBRTC_ARCH_X86_64 WEBRTC_ARCH_64_BITS)
  elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    list(APPEND defs WEBRTC_ARCH_ARM_FAMILY WEBRTC_ARCH_64_BITS)
  endif()
endif()

set(extra)
set(x86 FALSE)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|i686|x86")
  set(x86 TRUE)
endif()
if(ANDROID_ABI STREQUAL "arm64-v8a" OR (NOT ANDROID AND CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64"))
  list(APPEND defs WEBRTC_ARCH_ARM64 WEBRTC_HAS_NEON)
  foreach(f ${WEBRTC_APM_NEON})
    list(APPEND extra "${WEBRTC_APM_SRC}/${f}")
  endforeach()
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  # Plain C on 32-bit ARM, as webrtc-audio-processing builds it by default (no NEON, no assembly).
  set_source_files_properties("${WEBRTC_APM_SRC}/third_party/pffft/src/pffft.c" PROPERTIES COMPILE_DEFINITIONS PFFFT_SIMD_DISABLE)
elseif(x86)
  list(APPEND defs WEBRTC_ENABLE_AVX2)
  foreach(f ${WEBRTC_APM_X86_SSE2})
    list(APPEND extra "${WEBRTC_APM_SRC}/${f}")
    if(NOT MSVC)   # MSVC on x64 always has SSE2
      set_source_files_properties("${WEBRTC_APM_SRC}/${f}" PROPERTIES COMPILE_OPTIONS "-msse2")
    endif()
  endforeach()
  # Used only when the CPU says it has AVX2 (checked at run time).
  foreach(f ${WEBRTC_APM_X86_AVX2})
    list(APPEND extra "${WEBRTC_APM_SRC}/${f}")
    if(MSVC)
      set_source_files_properties("${WEBRTC_APM_SRC}/${f}" PROPERTIES COMPILE_OPTIONS "/arch:AVX2")
    else()
      set_source_files_properties("${WEBRTC_APM_SRC}/${f}" PROPERTIES COMPILE_OPTIONS "-mavx2;-mfma")
    endif()
  endforeach()
endif()

add_library(webrtc_apm STATIC ${WEBRTC_APM_SOURCES} ${extra})
target_include_directories(webrtc_apm PUBLIC "${WEBRTC_APM_SRC}" "${ABSEIL_SRC}")
target_compile_definitions(webrtc_apm PUBLIC ${defs})
if(MSVC)
  target_compile_options(webrtc_apm PRIVATE /O2 /w)
  target_link_libraries(webrtc_apm PUBLIC winmm)
else()
  target_compile_options(webrtc_apm PRIVATE -O2 -w -fno-strict-aliasing)
endif()
# WebRTC 1.3's headers use uint32_t and friends without including <cstdint>; GCC 13 and up (the Flatpak's) don't
# bring it in by the way, so every C++ file here and everything including these headers gets it first.
if(MSVC)
  set(TVVOICE_CSTDINT "$<$<COMPILE_LANGUAGE:CXX>:/FIcstdint>")
else()
  set(TVVOICE_CSTDINT "$<$<COMPILE_LANGUAGE:CXX>:SHELL:-include cstdint>")
endif()
target_compile_options(webrtc_apm PRIVATE ${TVVOICE_CSTDINT})

set(RNNOISE_SOURCES denoise.c rnn.c pitch.c kiss_fft.c celt_lpc.c nnet.c nnet_default.c parse_lpcnet_weights.c rnnoise_data.c rnnoise_tables.c)
list(TRANSFORM RNNOISE_SOURCES PREPEND "${RNNOISE_SRC}/src/")
add_library(rnnoise STATIC ${RNNOISE_SOURCES})
target_include_directories(rnnoise PUBLIC "${RNNOISE_SRC}/include" PRIVATE "${RNNOISE_SRC}/src" "${CMAKE_CURRENT_LIST_DIR}/rnnoise_shim")
# The model is built in; no cpu-specific code paths (RNNoise's x86 RTCD is left out).
target_compile_definitions(rnnoise PRIVATE RNNOISE_BUILD)
if(MSVC)
  target_compile_options(rnnoise PRIVATE /O2 /w)
else()
  target_compile_options(rnnoise PRIVATE -O2 -w)
  target_link_libraries(rnnoise PRIVATE m)
endif()

add_library(tvvoice_core STATIC tv_voice.cc)
target_link_libraries(tvvoice_core PUBLIC webrtc_apm rnnoise)
target_include_directories(tvvoice_core PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
target_compile_options(tvvoice_core PRIVATE ${TVVOICE_CSTDINT})
if(MSVC)
  target_compile_options(tvvoice_core PRIVATE /O2)
else()
  target_compile_options(tvvoice_core PRIVATE -Wall -O2)
endif()

if(ANDROID)
  add_library(muninn_tvvoice SHARED tv_voice_jni.cc)
  target_link_libraries(muninn_tvvoice PRIVATE tvvoice_core log)
  set_target_properties(muninn_tvvoice PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
  target_link_options(muninn_tvvoice PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
endif()

# Host check (not built for Android): tv_voice_test (app/src/test/cpp) echo-cancels a made-up room.
if(TVVOICE_HOST_TEST)
  find_package(Threads REQUIRED)
  add_executable(tv_voice_test ${CMAKE_CURRENT_SOURCE_DIR}/../../../test/cpp/tv_voice_test.cc)
  target_include_directories(tv_voice_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(tv_voice_test PRIVATE tvvoice_core Threads::Threads)
endif()
