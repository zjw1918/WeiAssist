cmake_minimum_required(VERSION 3.22.1)
project(weiassist_adbpair CXX)

# BoringSSL (prefab from io.github.vvb2060.ndk:boringssl): only its SPAKE2, exactly what adbd uses for pairing.
find_package(boringssl REQUIRED CONFIG)

add_library(adbpair SHARED adbpair.cpp)
target_link_libraries(adbpair boringssl::crypto_static)
target_compile_options(adbpair PRIVATE -Wall -Werror -Os -fvisibility=hidden)
target_link_options(adbpair PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
