# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("oboe_recorder_demo")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
# Collect sources automatically
file(GLOB ROOT_SOURCES
        *.cpp
        *.c)
file(GLOB LATENCY_SOURCES
        latency/*.cpp
        latency/*.c
        latency/audio/*.cpp
        latency/audio/*.c
        latency/ffmpeg/*.cpp
        latency/ffmpeg/*.c)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ${ROOT_SOURCES}
        ${LATENCY_SOURCES})

# Find the Oboe package
find_package (oboe REQUIRED CONFIG)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
    android
    log
    oboe::oboe)

set_target_properties( oboe::oboe PROPERTIES INTERFACE_COMPILE_OPTIONS "-D OBOE_DEBUG=1")

# ---- Latency tester + FFmpeg (merged into main library) ----
set(FFMPEG_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../libs/${ANDROID_ABI})
set(FFMPEG_INC_DIR ${CMAKE_SOURCE_DIR}/../../../libs/${ANDROID_ABI}/include)
message(STATUS "ANDROID_ABI=${ANDROID_ABI}")
message(STATUS "FFMPEG_LIB_DIR=${FFMPEG_LIB_DIR}")
message(STATUS "FFMPEG_INC_DIR=${FFMPEG_INC_DIR}")
if(NOT EXISTS ${FFMPEG_LIB_DIR})
    message(WARNING "FFMPEG_LIB_DIR does not exist: ${FFMPEG_LIB_DIR}")
endif()
if(NOT EXISTS ${FFMPEG_INC_DIR})
    message(WARNING "FFMPEG_INC_DIR does not exist: ${FFMPEG_INC_DIR}")
endif()

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${FFMPEG_INC_DIR}
        ${CMAKE_SOURCE_DIR}
        ${CMAKE_SOURCE_DIR}/latency
        ${CMAKE_SOURCE_DIR}/latency/audio
        ${CMAKE_SOURCE_DIR}/latency/ffmpeg)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.so)
add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.so)
add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.so)
add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswresample.so)
add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswscale.so)
add_library(avfilter SHARED IMPORTED)
set_target_properties(avfilter PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavfilter.so)
add_library(avdevice SHARED IMPORTED)
set_target_properties(avdevice PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavdevice.so)

target_link_libraries(${CMAKE_PROJECT_NAME}
        avcodec
        avformat
        avutil
        swresample
        swscale
        avfilter
        avdevice
        c++_shared)
