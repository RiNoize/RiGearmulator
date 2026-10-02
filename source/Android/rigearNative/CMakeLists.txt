cmake_minimum_required(VERSION 3.15)
project(rigearNative)

# RiGearRuntime includes the 0.6 bridge in ONE translation unit. Do not also
# compile RiGearNative.cpp: its existing JNI methods remain exported here.
add_library(rigear_jni SHARED RiGearRuntime.cpp)
target_link_libraries(rigear_jni PRIVATE virusLib log)
target_include_directories(rigear_jni PRIVATE ${CMAKE_SOURCE_DIR}/source/axel)
set_target_properties(rigear_jni PROPERTIES OUTPUT_NAME "rigear")

# Preserve robust gain/NaN diagnostics even when upstream Release uses -Ofast.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(rigear_jni PRIVATE -fno-fast-math)
endif()
if(ANDROID)
    target_link_options(rigear_jni PRIVATE "-Wl,-z,max-page-size=16384")
endif()
