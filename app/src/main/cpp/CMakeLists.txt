# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.18.1)

#project(root)
project("ehviewer")

include(ExternalProject)
include(FetchContent)

# liblzma
option(XZ_DOC OFF)
option(XZ_LZIP_DECODER OFF)
option(XZ_MICROLZMA_DECODER OFF)
option(XZ_MICROLZMA_ENCODER OFF)
option(XZ_TOOL_LZMADEC OFF)
option(XZ_TOOL_LZMAINFO OFF)
option(XZ_TOOL_XZ OFF)
option(XZ_TOOL_XZDEC OFF)
FetchContent_Declare(
        liblzma
        GIT_REPOSITORY https://github.com/tukaani-project/xz.git
        GIT_TAG v5.8.2
        GIT_SHALLOW 1
)
FetchContent_MakeAvailable(liblzma)
include_directories(${liblzma_SOURCE_DIR}/src/liblzma/api)

# Configure lzma support for libarchive
SET(HAVE_LIBLZMA 1)
SET(HAVE_LZMA_H 1)
SET(HAVE_LZMADEC_H 1)
SET(HAVE_LIBLZMADEC 1)

# libarchive
option(ENABLE_OPENSSL OFF)
option(ENABLE_TAR OFF)
option(ENABLE_CPIO OFF)
option(ENABLE_CAT OFF)
option(ENABLE_UNZIP OFF)
option(ENABLE_TEST OFF)

FetchContent_Declare(
        libarchive
        GIT_REPOSITORY https://github.com/libarchive/libarchive.git
        GIT_TAG v3.8.5
        GIT_SHALLOW 1
)
FetchContent_MakeAvailable(libarchive)
target_link_libraries(archive_static liblzma)
include_directories(${libarchive_SOURCE_DIR}/libarchive)

add_subdirectory(jni)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

add_library( # Sets the name of the library.
#        root
        "ehviewer"
        # Sets the library as a shared library.
        SHARED
        gifutils.c
        fdutils.c
        image.c
        gif/native-lib.cpp
        gif/dgif_lib.c
        gif/gifalloc.c
        archive.c
        natsort/strnatcmp.c
)

# Ensure generated binary aligns segments for 16KB page size devices.
target_link_options(
        ehviewer
        PRIVATE
        -Wl,-z,max-page-size=16384)

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.
find_library( # Sets the name of the path variable.
        jnigraphics-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        jnigraphics)
find_library(
        log-lib
        log)
find_library(
        glesv2-lib
        GLESv2)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

find_library(
        z-lib
        z)

target_link_libraries( # Specifies the target library.
        ehviewer

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib}
        ${jnigraphics-lib}
        ${glesv2-lib}
        archive_static
        ${z-lib}
)
