cmake_minimum_required(VERSION 3.22)

# One dot only, read as a decimal: 0.3, 0.31, 0.4, 1.0.
set(HUGINN_VERSION "0.1" CACHE STRING "Version shown in the app")
# A pre-release build's label, "<next> beta <n>" (like "0.99 beta 12"), set by CI for the beta release.
# Empty: the same as HUGINN_VERSION (a stable build).
set(HUGINN_VERSION_LABEL "" CACHE STRING "Version label of a pre-release build")
if(HUGINN_VERSION_LABEL STREQUAL "")
    set(HUGINN_LABEL "${HUGINN_VERSION}")
else()
    set(HUGINN_LABEL "${HUGINN_VERSION_LABEL}")
endif()
project(huginn VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

# ------------------------------------------------------------- platforms
# Linux (the Flatpak): D-Bus, KDE's Prison for QR codes, SDL2 through pkg-config.
# Windows (the portable zip, see the Windows job in .github/workflows/build.yml): no D-Bus or Prison;
# OpenSSL, SDL2, libqrencode and Opus come from vcpkg (CMAKE_TOOLCHAIN_FILE=.../vcpkg.cmake).
if(WIN32)
    enable_language(RC)
    find_package(Qt6 6.6 REQUIRED COMPONENTS Core Gui Widgets Quick QuickControls2 Network WebSockets Bluetooth Multimedia)
    # The updater unpacks the release zip itself with Qt's zip reader (src/platform/winupdate.cpp).
    find_package(Qt6 6.6 REQUIRED COMPONENTS CorePrivate GuiPrivate)
    find_package(OpenSSL REQUIRED)
    find_package(SDL2 CONFIG REQUIRED)
    find_path(QRENCODE_INCLUDE_DIR qrencode.h REQUIRED)
    find_library(QRENCODE_LIBRARY NAMES qrencode qrencoded REQUIRED)
    find_package(Opus CONFIG QUIET)
    # libvpx (VP8 video calls and screen share), from vcpkg: its config package when there is one,
    # otherwise the header and the library found by name.
    find_package(unofficial-libvpx CONFIG QUIET)
    if(NOT TARGET unofficial::libvpx::libvpx)
        find_path(VPX_INCLUDE_DIR vpx/vpx_encoder.h)
        find_library(VPX_LIBRARY NAMES vpx vpxmd libvpx)
    endif()
    option(HUGINN_WINDOWS_PORTABLE "The CI build for Huginn-Windows.zip (updates itself from GitHub releases)" OFF)
    # Control Flow Guard checks in everything compiled for Huginn.exe (the linker half is further down).
    add_compile_options($<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/guard:cf>)
else()
    find_package(Qt6 6.6 REQUIRED COMPONENTS Core Gui Widgets Quick QuickControls2 Network DBus WebSockets Bluetooth Multimedia)
    find_package(KF6Prison REQUIRED)
    find_package(OpenSSL REQUIRED)
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(SDL2 REQUIRED IMPORTED_TARGET sdl2)
    pkg_check_modules(OPUS QUIET IMPORTED_TARGET opus)
    pkg_check_modules(VPX QUIET IMPORTED_TARGET vpx)
endif()

qt_standard_project_setup(REQUIRES 6.6)

qt_add_executable(huginn
    src/main.cpp
    src/settings.cpp src/settings.h
    src/talkkey.cpp src/talkkey.h
    src/globalkeys.cpp src/globalkeys.h
    src/system.cpp src/system.h
    src/gamepad.cpp src/gamepad.h
    src/transfer.cpp src/transfer.h
    src/livelist.cpp src/livelist.h
    src/updater.cpp src/updater.h
    src/ultraprivacy.cpp src/ultraprivacy.h
    src/labs.cpp src/labs.h
    src/activitylog.cpp src/activitylog.h
    src/appstorage.cpp src/appstorage.h
    src/perfcheck.cpp src/perfcheck.h src/busy.h
    src/fall.cpp src/fall.h
    src/icons.cpp src/icons.h
    src/localsend.cpp src/localsend.h
    src/chat.cpp src/chat.h
    src/direct.h
    src/chatcrypto.cpp src/chatcrypto.h
    src/chatwire.cpp src/chatwire.h
    src/proof.cpp src/proof.h
    src/ring.cpp src/ring.h
    src/chatgroups.cpp
    src/chatlimits.cpp
    src/chatpeople.cpp
    src/grouplinks.cpp
    src/move.cpp src/move.h
    src/groupwire.cpp src/groupwire.h
    src/richwire.cpp src/richwire.h
    src/morewire.cpp src/morewire.h
    src/chatmore.cpp
    src/chatfiles.cpp
    src/linkpreview.cpp src/linkpreview.h
    src/videomsg.cpp src/videomsg.h
    src/bigvideo.cpp src/bigvideo.h
    src/savedmedia.cpp src/savedmedia.h
    src/groupvideo.cpp src/groupvideo.h src/chatvideo.cpp
    src/thumbs.cpp src/thumbs.h
    src/msglimits.cpp src/msglimits.h
    src/modwire.cpp src/modwire.h
    src/moderation.cpp src/moderation.h
    src/trust.cpp src/trust.h
    src/profile.cpp src/profile.h
    src/filelink.cpp src/filelink.h
    src/torfiles.cpp src/torfiles.h
    src/sharing.cpp src/sharing.h
    src/link.cpp src/link.h
    src/ratchet.cpp src/ratchet.h
    src/vault.cpp src/vault.h
    src/photos.cpp src/photos.h
    src/voice.cpp src/voice.h
    src/schnorr.cpp src/schnorr.h
    src/nostr.cpp src/nostr.h
    src/ble.cpp src/ble.h
    src/bleframes.cpp src/bleframes.h
    src/tls.cpp src/tls.h
    src/lan.cpp src/lan.h
    src/wifiselftest.cpp src/wifiselftest.h
    # Read aloud (shared/docs/voices.md): the natural voices, with the speech engine loaded at run time.
    src/readaloud.cpp src/readaloud.h src/sherpatts.h
    src/dailyverse.cpp src/dailyverse.h
    # 1:1 calls (shared/docs/protocol.md section 5: TURN relay over the internet, direct between trusted
    # contacts, video and screen share between two c2 apps, 5.8)
    src/call/relayconfig.h
    src/call/callsignal.h
    src/call/callcrypto.cpp src/call/callcrypto.h
    src/call/stun.cpp src/call/stun.h
    src/call/turnclient.cpp src/call/turnclient.h
    src/call/jitterbuffer.cpp src/call/jitterbuffer.h
    src/call/audiodsp.cpp src/call/audiodsp.h
    src/call/opuscodec.cpp src/call/opuscodec.h
    src/call/audioio.cpp src/call/audioio.h
    src/call/directpath.cpp src/call/directpath.h
    src/call/videowire.cpp src/call/videowire.h
    src/call/vpxcodec.cpp src/call/vpxcodec.h
    src/call/videoengine.cpp src/call/videoengine.h
    src/call/callmedia.cpp src/call/callmedia.h
    src/call/callsession.cpp src/call/callsession.h
    src/call/callmanager.cpp src/call/callmanager.h
    src/call/callshare.cpp src/call/callshare.h
    src/call/callselftest.cpp src/call/callselftest.h
    # voice rooms and group calls (section 6): a mesh of the 1:1 calls above
    src/call/roomwire.cpp src/call/roomwire.h
    src/call/roommanager.cpp src/call/roommanager.h
)

target_compile_definitions(huginn PRIVATE HUGINN_VERSION="${HUGINN_VERSION}" HUGINN_VERSION_LABEL="${HUGINN_LABEL}")

# Small per-platform pieces (src/platform/). Direct connect needs NetworkManager, so Windows
# builds a version of it that explains the Windows way instead.
target_sources(huginn PRIVATE
    src/platform/notify.h
    src/platform/chime.h
    src/platform/qrcode.h
    src/platform/keepawake.h
    src/platform/singleinstance.cpp src/platform/singleinstance.h
    src/platform/crashlog.cpp src/platform/crashlog.h
    src/platform/scratch.cpp src/platform/scratch.h
    src/platform/portable.cpp src/platform/portable.h
    src/platform/winupdate.cpp src/platform/winupdate.h
    src/platform/winstart.cpp src/platform/winstart.h
)
if(WIN32)
    target_sources(huginn PRIVATE
        src/platform/notify_windows.cpp
        src/platform/chime_windows.cpp
        src/platform/qrcode_qrencode.cpp
        src/platform/keepawake_windows.cpp
        src/platform/direct_windows.cpp
        src/platform/globalkeys_windows.cpp
    )
    # Huginn.exe: a windowed app (no console), with the raven icon and version details.
    set_target_properties(huginn PROPERTIES WIN32_EXECUTABLE TRUE OUTPUT_NAME Huginn)
    string(REPLACE "." ";" HUGINN_VERSION_PARTS "${HUGINN_VERSION}")
    list(GET HUGINN_VERSION_PARTS 0 HUGINN_RC_MAJOR)
    list(LENGTH HUGINN_VERSION_PARTS HUGINN_VERSION_COUNT)
    if(HUGINN_VERSION_COUNT GREATER 1)
        list(GET HUGINN_VERSION_PARTS 1 HUGINN_RC_MINOR)
    else()
        set(HUGINN_RC_MINOR 0)
    endif()
    set(HUGINN_RC_ICON "${CMAKE_CURRENT_SOURCE_DIR}/packaging/windows/huginn.ico")
    configure_file(packaging/windows/huginn.rc.in ${CMAKE_CURRENT_BINARY_DIR}/huginn.rc @ONLY)
    # The application manifest: runs as the person (asInvoker), Windows 10/11, per-monitor DPI.
    # The portable zip keeps Huginn.exe alone at the top and its DLLs in lib\, a private assembly
    # (lib\lib.manifest, written by the Windows job in build.yml) that Windows loads them from.
    set(HUGINN_MANIFEST_LIB "")
    if(HUGINN_WINDOWS_PORTABLE)
        set(HUGINN_MANIFEST_LIB "  <dependency>\n    <dependentAssembly>\n      <assemblyIdentity type=\"win32\" name=\"lib\" version=\"1.0.0.0\" processorArchitecture=\"amd64\"/>\n    </dependentAssembly>\n  </dependency>\n")
    endif()
    configure_file(packaging/windows/huginn.manifest.in ${CMAKE_CURRENT_BINARY_DIR}/Huginn.exe.manifest @ONLY)
    target_sources(huginn PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/huginn.rc ${CMAKE_CURRENT_BINARY_DIR}/Huginn.exe.manifest)
    # Our manifest says asInvoker itself; the linker's own would be a second copy.
    target_link_options(huginn PRIVATE $<$<CXX_COMPILER_ID:MSVC>:/MANIFESTUAC:NO>)
    target_link_libraries(huginn PRIVATE Qt6::CorePrivate Qt6::GuiPrivate)
    if(HUGINN_WINDOWS_PORTABLE)
        # Qt finds its plugins and QML modules in lib\ (packaging/windows/qt.conf, built in as :/qt/etc/qt.conf).
        qt_add_resources(huginn "huginn_qtconf" PREFIX "/qt/etc" BASE packaging/windows FILES packaging/windows/qt.conf)
    endif()
    # Sources are UTF-8 (they use … and · in strings); MSVC needs to be told.
    target_compile_options(huginn PRIVATE $<$<CXX_COMPILER_ID:MSVC>:/utf-8 /Zc:__cplusplus /permissive->)
    target_compile_definitions(huginn PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN SDL_MAIN_HANDLED)
    target_include_directories(huginn PRIVATE ${QRENCODE_INCLUDE_DIR})
    if(HUGINN_WINDOWS_PORTABLE)
        target_compile_definitions(huginn PRIVATE HUGINN_WINDOWS_PORTABLE)
    endif()
    # Round 19 (security 15): the CI build pins lib/tor/tor.exe's SHA-256, so a changed copy is never run (ultraprivacy.cpp).
    set(HUGINN_TOR_SHA256 "" CACHE STRING "SHA-256 (hex) of the tor.exe that ships in lib/tor")
    if(NOT HUGINN_TOR_SHA256 STREQUAL "")
        if(NOT HUGINN_TOR_SHA256 MATCHES "^[0-9a-fA-F]+$")
            message(FATAL_ERROR "HUGINN_TOR_SHA256 must be 64 hex characters")
        endif()
        string(LENGTH "${HUGINN_TOR_SHA256}" _torlen)
        if(NOT _torlen EQUAL 64)
            message(FATAL_ERROR "HUGINN_TOR_SHA256 must be 64 hex characters")
        endif()
        target_compile_definitions(huginn PRIVATE HUGINN_TOR_SHA256="${HUGINN_TOR_SHA256}")
    endif()
    # Release: no debug info or PDB, unused code dropped, the same bytes for the same source.
    target_link_options(huginn PRIVATE $<$<CXX_COMPILER_ID:MSVC>:/DEBUG:NONE /OPT:REF /OPT:ICF /Brepro>)
    target_compile_options(huginn PRIVATE $<$<CXX_COMPILER_ID:MSVC>:/Brepro>)
    # Hardening Windows checks for (shared/tools/check-windows-binary.py fails the build without it): Control Flow
    # Guard, CET shadow stacks, a random load address anywhere in 64-bit memory, and no running code from data.
    target_link_options(huginn PRIVATE $<$<CXX_COMPILER_ID:MSVC>:/GUARD:CF /CETCOMPAT /DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT>)
else()
    target_sources(huginn PRIVATE
        src/platform/notify_linux.cpp
        src/platform/chime_linux.cpp
        src/platform/qrcode_prison.cpp
        src/platform/globalkeys_linux.cpp
        src/direct.cpp
    )
endif()

set_source_files_properties(qml/Theme.qml qml/Ui.qml qml/PeopleActions.qml qml/ModActions.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
if(WIN32)
    # Windows: only the compiled QML goes into Huginn.exe, not its source text (qmlcachegen still
    # compiles every file; the engine runs the compiled form, which matches the Qt shipped beside it).
    file(GLOB HUGINN_QML_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/qml/*.qml)
    set_source_files_properties(${HUGINN_QML_SOURCES} PROPERTIES QT_DISCARD_FILE_CONTENTS TRUE)
endif()

# Fonts are fetched by shared/tools/get-fonts.sh (CI does it before every build).
file(GLOB HUGINN_FONTS RELATIVE ${CMAKE_CURRENT_SOURCE_DIR} fonts/*.ttf)

qt_add_qml_module(huginn
    URI Huginn
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/Theme.qml
        qml/Ui.qml
        qml/Icon.qml
        qml/PageHeader.qml
        qml/PromptKey.qml
        qml/PromptBar.qml
        qml/HButton.qml
        qml/ToggleSwitch.qml
        qml/Segmented.qml
        qml/TextPrompt.qml
        qml/ConfirmDialog.qml
        qml/HoldToConfirm.qml
        qml/MoveCover.qml
        qml/Toast.qml
        qml/ListPicker.qml
        qml/Rail.qml
        qml/Splitter.qml
        qml/MemberList.qml
        qml/VoicePanel.qml
        qml/MeCard.qml
        qml/ProfileCard.qml
        qml/Avatar.qml
        qml/StatusDot.qml
        qml/PicturePicker.qml
        qml/Leaves.qml
        qml/ShortcutsPanel.qml
        qml/ReadAloudPage.qml
        qml/ChatPage.qml
        qml/GroupPage.qml
        qml/FilesPage.qml
        qml/CallPage.qml
        qml/IncomingCall.qml
        qml/RoomBar.qml
        qml/SettingsPage.qml
        qml/ContactsPage.qml
        qml/LockPanel.qml
        qml/LockCheck.qml
        qml/PatternPad.qml
        qml/Composer.qml
        qml/FindBar.qml
        qml/EmojiPicker.qml
        qml/Reactions.qml
        qml/QuoteLine.qml
        qml/HoverBar.qml
        qml/MessageRow.qml
        qml/QuickSwitcher.qml
        qml/StickPointer.qml
        qml/MoreParts.qml
        qml/MoreLayer.qml
        qml/SavedMedia.qml
        qml/PhotoViewer.qml
        qml/PollDialog.qml
        qml/PeopleActions.qml
        qml/ModActions.qml
        qml/FileStrip.qml
        qml/TransfersPanel.qml
        qml/ChatFiles.qml
        qml/MirrorPanel.qml
        qml/LinkPhone.qml
        qml/FirstSetup.qml
        qml/TopBar.qml
        qml/PersonPanel.qml
        qml/InvitePanel.qml
        qml/MoreButton.qml
        qml/LabsReport.qml
        qml/LabsInbox.qml
        qml/AppStoragePanel.qml
        qml/PerfPanel.qml
        qml/BackupPanel.qml
        qml/RightClickMenu.qml
        qml/FieldMenu.qml
    RESOURCES
        qml/assets/raven-gold.png
        qml/assets/valknut.png
        qml/assets/raven_message.wav
        qml/assets/cue_on.wav
        qml/assets/cue_off.wav
        qml/assets/cue_deaf_on.wav
        qml/assets/cue_deaf_off.wav
        ${HUGINN_FONTS}
)

# Keep resource paths short: qml/Main.qml is served as Main.qml.
foreach(f IN ITEMS Main Theme Ui Icon PageHeader PromptKey PromptBar HButton ToggleSwitch Segmented
                   TextPrompt ConfirmDialog HoldToConfirm Toast ListPicker Rail ChatPage GroupPage FilesPage SettingsPage
                   ContactsPage LockPanel PatternPad CallPage IncomingCall RoomBar Composer FindBar EmojiPicker Reactions QuoteLine
                   HoverBar MessageRow QuickSwitcher StickPointer MoreParts MoreLayer SavedMedia PhotoViewer PollDialog PeopleActions ModActions FileStrip MirrorPanel
                   Avatar PicturePicker Leaves MoreButton LabsReport LabsInbox AppStoragePanel LinkPhone FirstSetup
                   TransfersPanel ChatFiles)
    set_source_files_properties(qml/${f}.qml PROPERTIES QT_RESOURCE_ALIAS ${f}.qml)
endforeach()
# The desktop panels.
foreach(f IN ITEMS Splitter MemberList VoicePanel MeCard ProfileCard ShortcutsPanel TopBar PersonPanel InvitePanel RightClickMenu FieldMenu)
    set_source_files_properties(qml/${f}.qml PROPERTIES QT_RESOURCE_ALIAS ${f}.qml)
endforeach()
set_source_files_properties(qml/assets/raven-gold.png PROPERTIES QT_RESOURCE_ALIAS assets/raven-gold.png)
set_source_files_properties(qml/assets/valknut.png PROPERTIES QT_RESOURCE_ALIAS assets/valknut.png)
set_source_files_properties(qml/assets/raven_message.wav PROPERTIES QT_RESOURCE_ALIAS assets/raven_message.wav)
set_source_files_properties(qml/assets/cue_on.wav PROPERTIES QT_RESOURCE_ALIAS assets/cue_on.wav)
set_source_files_properties(qml/assets/cue_off.wav PROPERTIES QT_RESOURCE_ALIAS assets/cue_off.wav)
set_source_files_properties(qml/assets/cue_deaf_on.wav PROPERTIES QT_RESOURCE_ALIAS assets/cue_deaf_on.wav)
set_source_files_properties(qml/assets/cue_deaf_off.wav PROPERTIES QT_RESOURCE_ALIAS assets/cue_deaf_off.wav)
foreach(font IN LISTS HUGINN_FONTS)
    get_filename_component(name ${font} NAME)
    set_source_files_properties(${font} PROPERTIES QT_RESOURCE_ALIAS fonts/${name})
endforeach()

# Daily Havamal's verses: the one file both apps use (code/shared/havamal.json), served as :/huginn/havamal.json.
qt_add_resources(huginn "huginn_havamal" PREFIX "/huginn" BASE ${CMAKE_CURRENT_SOURCE_DIR}/../shared
    FILES ${CMAKE_CURRENT_SOURCE_DIR}/../shared/havamal.json ${CMAKE_CURRENT_SOURCE_DIR}/../shared/beta-tests.json)

# Read aloud: the voices' SHA-256 (code/shared/voices.sha256), served as :/huginn/voices.sha256.
qt_add_resources(huginn "huginn_voices" PREFIX "/huginn" BASE ${CMAKE_CURRENT_SOURCE_DIR}/../shared
    FILES ${CMAKE_CURRENT_SOURCE_DIR}/../shared/voices.sha256)
# The computer's own voice for Read aloud until the natural one is downloaded (Qt TextToSpeech, when this Qt has it).
find_package(Qt6 QUIET COMPONENTS TextToSpeech)
if(TARGET Qt6::TextToSpeech)
    target_link_libraries(huginn PRIVATE Qt6::TextToSpeech)
    target_compile_definitions(huginn PRIVATE HUGINN_HAVE_QTSPEECH)
else()
    message(STATUS "Qt TextToSpeech not found: Read aloud waits for the natural voice")
endif()

target_link_libraries(huginn PRIVATE
    Qt6::Core Qt6::Gui Qt6::Widgets Qt6::Quick Qt6::QuickControls2 Qt6::Network
    Qt6::WebSockets Qt6::Bluetooth Qt6::Multimedia
    OpenSSL::Crypto
)
if(WIN32)
    target_link_libraries(huginn PRIVATE
        $<IF:$<TARGET_EXISTS:SDL2::SDL2>,SDL2::SDL2,SDL2::SDL2-static>
        ${QRENCODE_LIBRARY}
        winmm      # PlaySound, for the raven call (src/platform/chime_windows.cpp)
        psapi      # Labs › Performance check's memory figure (src/perfcheck.cpp)
        ole32 uuid # the taskbar button's unread badge (ITaskbarList3, src/system.cpp)
        advapi32   # the data folder for this account only (SetNamedSecurityInfoW, src/platform/portable.cpp)
    )
else()
    target_link_libraries(huginn PRIVATE Qt6::DBus KF6::Prison PkgConfig::SDL2)
endif()
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    # Labs › Performance check's Hot spots name Huginn's own functions: they go in the dynamic symbol table (-rdynamic),
    # which stripping keeps. dladdr() comes from libdl on older systems.
    set_target_properties(huginn PROPERTIES ENABLE_EXPORTS ON)
    target_link_libraries(huginn PRIVATE ${CMAKE_DL_LIBS})
endif()
# Opus (voice calls, src/call/): linked and HUGINN_HAVE_OPUS defined when it's there.
if(TARGET Opus::opus)
    target_link_libraries(huginn PRIVATE Opus::opus)
    target_compile_definitions(huginn PRIVATE HUGINN_HAVE_OPUS)
elseif(TARGET PkgConfig::OPUS)
    target_link_libraries(huginn PRIVATE PkgConfig::OPUS)
    target_compile_definitions(huginn PRIVATE HUGINN_HAVE_OPUS)
elseif(WIN32 AND HUGINN_WINDOWS_PORTABLE)
    message(FATAL_ERROR "Opus wasn't found: the Windows build installs it with vcpkg (opus:x64-windows).")
elseif(NOT WIN32)
    # The Flatpak's runtime ships libopus (freedesktop base); without it calls would build but never sound.
    message(FATAL_ERROR "Opus wasn't found (pkg-config opus): needed for voice calls.")
endif()

# libvpx (video calls and screen share, 5.8.5): HUGINN_HAVE_VPX when it's there. Without it calls are
# sound only and the apps say video needs another build.
if(TARGET unofficial::libvpx::libvpx)
    target_link_libraries(huginn PRIVATE unofficial::libvpx::libvpx)
    target_compile_definitions(huginn PRIVATE HUGINN_HAVE_VPX)
elseif(VPX_INCLUDE_DIR AND VPX_LIBRARY)
    target_include_directories(huginn PRIVATE ${VPX_INCLUDE_DIR})
    target_link_libraries(huginn PRIVATE ${VPX_LIBRARY})
    target_compile_definitions(huginn PRIVATE HUGINN_HAVE_VPX)
elseif(TARGET PkgConfig::VPX)
    target_link_libraries(huginn PRIVATE PkgConfig::VPX)
    target_compile_definitions(huginn PRIVATE HUGINN_HAVE_VPX)
elseif(WIN32 AND HUGINN_WINDOWS_PORTABLE)
    message(FATAL_ERROR "libvpx wasn't found: the Windows build installs it with vcpkg (libvpx:x64-windows).")
else()
    message(STATUS "libvpx not found: video calls are left out of this build")
endif()

# Echo cancellation, noise suppression and level in calls (src/call/audioio.cpp): WebRTC Audio Processing, built
# exactly as for the Kids' TV (../muninn/app/src/main/cpp/tvvoice), with its pinned sources (cmake/voice.cmake).
option(HUGINN_VOICE "Echo cancellation and noise suppression in calls" ON)
if(HUGINN_VOICE)
    include(cmake/voice.cmake)
    add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../muninn/app/src/main/cpp/tvvoice ${CMAKE_BINARY_DIR}/tvvoice)
    target_link_libraries(huginn PRIVATE tvvoice_core)
    target_compile_definitions(huginn PRIVATE HUGINN_HAVE_VOICE)
endif()

include(GNUInstallDirs)
install(TARGETS huginn RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
if(WIN32)
    return()   # Windows is packaged by the CI job (windeployqt and a zip), not installed
endif()
install(FILES packaging/io.github.ravenor907.Huginn.desktop DESTINATION ${CMAKE_INSTALL_DATADIR}/applications)
install(FILES packaging/io.github.ravenor907.Huginn.metainfo.xml DESTINATION ${CMAKE_INSTALL_DATADIR}/metainfo)
# Read aloud's speech engine (bundled by the Flatpak manifest) and the natural voices: their licenses.
install(FILES ../shared/licenses/sherpa-onnx-Apache-2.0.txt ../shared/licenses/onnxruntime-MIT.txt ../shared/licenses/natural-voices.txt
        DESTINATION ${CMAKE_INSTALL_DATADIR}/licenses/io.github.ravenor907.Huginn)
install(FILES packaging/io.github.ravenor907.Huginn-256.png DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/256x256/apps RENAME io.github.ravenor907.Huginn.png)
install(FILES packaging/io.github.ravenor907.Huginn-512.png DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/512x512/apps RENAME io.github.ravenor907.Huginn.png)
