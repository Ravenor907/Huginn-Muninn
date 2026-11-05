# The sources for echo cancellation in calls: the same pinned versions the Kids' TV uses
# (muninn/app/build.gradle.kts, fetchTvVoice). The Flatpak lists them in its manifest and unpacks them into
# third_party/ (nothing is downloaded while it builds); any other build fetches them once into the build folder
# and checks their SHA-256. Sets WEBRTC_APM_SRC, ABSEIL_SRC and RNNOISE_SRC.
set(HUGINN_VOICE_PINS
    "webrtc-audio-processing-1.3|https://freedesktop.org/software/pulseaudio/webrtc-audio-processing/webrtc-audio-processing-1.3.tar.xz|2365e93e778d7b61b5d6e02d21c47d97222e9c7deff9e1d0838ad6ec2e86f1b9|tar.xz"
    "abseil-cpp-20230125.1|https://github.com/abseil/abseil-cpp/archive/20230125.1.tar.gz|81311c17599b3712069ded20cca09a62ab0bf2a89dfa16993786c8782b7ed145|tar.gz"
    "rnnoise-0.2|https://github.com/xiph/rnnoise/releases/download/v0.2/rnnoise-0.2.tar.gz|90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37|tar.gz")

foreach(pin IN LISTS HUGINN_VOICE_PINS)
    string(REPLACE "|" ";" parts "${pin}")
    list(GET parts 0 name)
    list(GET parts 1 url)
    list(GET parts 2 sha)
    list(GET parts 3 ext)
    set(dir "${CMAKE_CURRENT_SOURCE_DIR}/third_party/${name}")
    if(NOT EXISTS "${dir}")
        set(dir "${CMAKE_BINARY_DIR}/voice/${name}")
        if(NOT EXISTS "${dir}")
            set(archive "${CMAKE_BINARY_DIR}/voice/${name}.${ext}")
            message(STATUS "Fetching ${name} for echo cancellation")
            file(DOWNLOAD "${url}" "${archive}" EXPECTED_HASH SHA256=${sha} STATUS st TLS_VERIFY ON)
            list(GET st 0 code)
            if(NOT code EQUAL 0)
                message(FATAL_ERROR "${name} couldn't be downloaded (${st}). Build with -DHUGINN_VOICE=OFF to leave echo cancellation out.")
            endif()
            # Every archive holds one top folder with the name above.
            file(ARCHIVE_EXTRACT INPUT "${archive}" DESTINATION "${CMAKE_BINARY_DIR}/voice")
        endif()
    endif()
    if(name MATCHES "^webrtc")
        set(WEBRTC_APM_SRC "${dir}/webrtc")
    elseif(name MATCHES "^abseil")
        set(ABSEIL_SRC "${dir}")
    else()
        set(RNNOISE_SRC "${dir}")
    endif()
endforeach()
