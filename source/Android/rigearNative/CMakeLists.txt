cmake_minimum_required(VERSION 3.15)
project(rigearNative)

# Studio includes Runtime, which includes the original native bridge.
# Exactly ONE translation unit owns the existing engine globals and JNI methods.
add_library(rigear_jni SHARED RiGearStudio.cpp)
target_link_libraries(rigear_jni PRIVATE virusLib log)
target_include_directories(rigear_jni PRIVATE ${CMAKE_SOURCE_DIR}/source/axel)
set_target_properties(rigear_jni PROPERTIES OUTPUT_NAME "rigear")
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(rigear_jni PRIVATE -fno-fast-math)
endif()
if(ANDROID)
    target_link_options(rigear_jni PRIVATE "-Wl,-z,max-page-size=16384")
endif()
