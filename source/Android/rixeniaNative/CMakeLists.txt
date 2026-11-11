cmake_minimum_required(VERSION 3.15)
project(rixeniaNative)

add_library(rixenia_jni SHARED RiXeniaNative.cpp)
target_link_libraries(rixenia_jni PRIVATE xtLib log)
# Shared Android helpers (ThreadPlacement.h).
target_include_directories(rixenia_jni PRIVATE ${CMAKE_SOURCE_DIR}/source/Android/common)
set_target_properties(rixenia_jni PROPERTIES OUTPUT_NAME "rixenia")
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(rixenia_jni PRIVATE -fno-fast-math)
endif()
if(ANDROID)
    target_link_options(rixenia_jni PRIVATE "-Wl,-z,max-page-size=16384")
endif()
