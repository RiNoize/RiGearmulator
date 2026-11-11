cmake_minimum_required(VERSION 3.15)
project(riostirusNative)

add_library(riostirus_jni SHARED RiOsTIrusNative.cpp)
target_link_libraries(riostirus_jni PRIVATE virusLib log)
# Shared Android helpers (ThreadPlacement.h).
target_include_directories(riostirus_jni PRIVATE ${CMAKE_SOURCE_DIR}/source/Android/common)
set_target_properties(riostirus_jni PROPERTIES OUTPUT_NAME "riostirus")
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(riostirus_jni PRIVATE -fno-fast-math)
endif()
if(ANDROID)
    target_link_options(riostirus_jni PRIVATE "-Wl,-z,max-page-size=16384")
endif()
