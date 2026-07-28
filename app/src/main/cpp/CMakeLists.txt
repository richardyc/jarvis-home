cmake_minimum_required(VERSION 3.22.1)
project(jarvisaec)

set(CMAKE_CXX_STANDARD 17)

# Prebuilt WebRTC Audio Processing (AEC3), armeabi-v7a.
add_library(webrtc_apm SHARED IMPORTED)
set_target_properties(webrtc_apm PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwebrtc-audio-processing-1.so)

add_library(jarvisaec SHARED aec.cpp)

# absl headers are included as "absl/..." so the parent dir goes on the path, not absl itself.
target_include_directories(jarvisaec PRIVATE
    ${CMAKE_SOURCE_DIR}/include/webrtc_audio_processing/webrtc-audio-processing-1
    ${CMAKE_SOURCE_DIR}/include)

find_library(log-lib log)
target_link_libraries(jarvisaec webrtc_apm ${log-lib})
