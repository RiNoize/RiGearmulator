cmake_minimum_required(VERSION 3.15)
project(rigearNative)

add_library(rigear_jni SHARED
    RiGearNative.cpp
)

target_link_libraries(rigear_jni
    PRIVATE
        virusLib
        log
)

target_include_directories(rigear_jni
    PRIVATE
        ${CMAKE_SOURCE_DIR}/source/axel
)

set_target_properties(rigear_jni PROPERTIES
    OUTPUT_NAME "rigear"
)
