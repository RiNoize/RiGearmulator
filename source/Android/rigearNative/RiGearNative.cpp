#include <jni.h>

#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "virusLib/device.h"
#include "virusLib/deviceModel.h"
#include "virusLib/romfile.h"

namespace
{
std::mutex g_mutex;
std::unique_ptr<virusLib::Device> g_device;

std::string getCoreInfo()
{
    return std::string("RiGear Android | Osirus | ") +
           virusLib::getModelName(virusLib::DeviceModel::C) +
           " | ARM64 core linked";
}

virusLib::DeviceModel detectAbcModel(const std::vector<uint8_t>& data)
{
    const auto os = virusLib::ROMFile::readOsVersion(data);

    if (os.rfind("vc", 0) == 0)
        return virusLib::DeviceModel::C;
    if (os.rfind("vb", 0) == 0)
        return virusLib::DeviceModel::B;
    if (os.rfind("v", 0) == 0)
        return virusLib::DeviceModel::A;

    // A/B/C ROMs share the same basic ROM container. C is a safe probe default
    // when an unusual firmware image does not expose the normal version string.
    return virusLib::DeviceModel::C;
}

std::string formatHz(uint64_t hz)
{
    std::ostringstream out;
    if (hz >= 1000000)
    {
        out.setf(std::ios::fixed);
        out.precision(2);
        out << (static_cast<double>(hz) / 1000000.0) << " MHz";
    }
    else
    {
        out << hz << " Hz";
    }
    return out.str();
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeLoadRom(
    JNIEnv* env,
    jclass,
    jbyteArray romBytes,
    jstring romName)
{
    if (!romBytes)
        return env->NewStringUTF("ROM LOAD: FAILED\nNo ROM data received.");

    const jsize size = env->GetArrayLength(romBytes);
    if (size <= 0)
        return env->NewStringUTF("ROM LOAD: FAILED\nROM file is empty.");

    // Keep accidental huge file selections from exhausting the app process.
    constexpr jsize maxRomBytes = 16 * 1024 * 1024;
    if (size > maxRomBytes)
        return env->NewStringUTF("ROM LOAD: FAILED\nFile is larger than 16 MB.");

    std::vector<uint8_t> data(static_cast<size_t>(size));
    env->GetByteArrayRegion(
        romBytes,
        0,
        size,
        reinterpret_cast<jbyte*>(data.data()));

    std::string name = "virus.bin";
    if (romName)
    {
        const char* chars = env->GetStringUTFChars(romName, nullptr);
        if (chars)
        {
            name = chars;
            env->ReleaseStringUTFChars(romName, chars);
        }
    }

    try
    {
        const auto model = detectAbcModel(data);
        virusLib::ROMFile probe(data, name, model);

        if (!probe.isValid())
        {
            std::ostringstream out;
            out << "ROM VALID: FAILED\n"
                << "This RiGear 0.2 test expects a raw Virus A/B/C .bin ROM.\n"
                << "File: " << name << "\n"
                << "Size: " << data.size() << " bytes";
            const auto s = out.str();
            return env->NewStringUTF(s.c_str());
        }

        synthLib::DeviceCreateParams params;
        params.hostSamplerate = 48000.0f;
        params.preferredSamplerate = 0.0f;
        params.romName = name;
        params.romData = data;
        params.customData = static_cast<uint32_t>(model);

        std::lock_guard<std::mutex> lock(g_mutex);
        g_device.reset();

        // The Device constructor parses the ROM, creates the DSP56300 instance,
        // boots the firmware and waits until the Virus microcontroller reports
        // that the DSP has completed booting.
        auto device = std::make_unique<virusLib::Device>(params, false);

        if (!device->isValid())
            return env->NewStringUTF("DSP BOOT: FAILED\nDevice object is not valid.");

        std::ostringstream out;
        out << "ROM VALID: OK\n"
            << "Model: Virus " << virusLib::getModelName(model) << "\n";

        const auto os = probe.getOsVersion();
        if (!os.empty())
            out << "OS: " << os << "\n";

        out << "DSP BOOT: OK\n"
            << "Device sample rate: " << static_cast<uint32_t>(device->getSamplerate()) << " Hz\n"
            << "DSP clock: " << formatHz(device->getDspClockHz()) << "\n"
            << "Audio outputs: " << device->getChannelCountOut();

        g_device = std::move(device);

        const auto s = out.str();
        return env->NewStringUTF(s.c_str());
    }
    catch (const std::exception& e)
    {
        const std::string s = std::string("DSP BOOT: EXCEPTION\n") + e.what();
        return env->NewStringUTF(s.c_str());
    }
    catch (const std::string& e)
    {
        const std::string s = std::string("DSP BOOT: EXCEPTION\n") + e;
        return env->NewStringUTF(s.c_str());
    }
    catch (...)
    {
        return env->NewStringUTF("DSP BOOT: EXCEPTION\nUnknown native error.");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeRelease(
    JNIEnv*,
    jclass)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    g_device.reset();
}
