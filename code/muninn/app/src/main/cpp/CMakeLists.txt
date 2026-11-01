# Opus for calls (shared/docs/protocol.md 5.5). The libopus source is fetched and checked by Gradle
# (task fetchOpus in app/build.gradle.kts, pinned version and SHA-256) and passed in as OPUS_SRC.
cmake_minimum_required(VERSION 3.18)
project(muninn_native C CXX)

if(NOT OPUS_SRC OR NOT EXISTS "${OPUS_SRC}/CMakeLists.txt")
  message(FATAL_ERROR "OPUS_SRC is not set or has no libopus source: ${OPUS_SRC}")
endif()

# Only the codec: no programs, tests, install files or the large deep-learning extras.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory("${OPUS_SRC}" opus EXCLUDE_FROM_ALL)

add_library(muninn_opus SHARED opus_jni.c)
target_link_libraries(muninn_opus PRIVATE opus)
target_compile_options(muninn_opus PRIVATE -Wall -O2)
# Small and private: export only the JNI functions, drop unused code.
set_target_properties(muninn_opus PROPERTIES C_VISIBILITY_PRESET hidden)
if(ANDROID)
  target_link_options(muninn_opus PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
  target_link_libraries(muninn_opus PRIVATE log)
endif()

# Kids' TV voice processing (echo canceller, noise suppression, gain): a library of its own, see tvvoice/.
if(WEBRTC_APM_SRC)
  add_subdirectory(tvvoice)
endif()
