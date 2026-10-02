#include <jni.h>

#include <string>

#include "virusLib/deviceModel.h"

namespace
{
std::string getCoreInfo()
{
    return std::string("RiGear Android | Osirus | ") +
           virusLib::getModelName(virusLib::DeviceModel::C) +
           " | ARM64 core linked";
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeGetCoreInfo(
    JNIEnv* env,
    jclass)
{
    const auto info = getCoreInfo();
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeSelfTest(
    JNIEnv*,
    jclass)
{
    const auto name = virusLib::getModelName(virusLib::DeviceModel::C);
    return name.empty() ? 0 : 1;
}
