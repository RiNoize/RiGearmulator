#include <jni.h>

#include <algorithm>
#include <cctype>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "virusLib/device.h"
#include "virusLib/deviceModel.h"
#include "virusLib/midiFileToRomData.h"
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

std::string lowercase(std::string s)
{
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c)
    {
        return static_cast<char>(std::tolower(c));
    });
    return s;
}

bool hasExtension(const std::string& name, const char* ext)
{
    const auto n = lowercase(name);
    const std::string e = ext;
    return n.size() >= e.size() && n.compare(n.size() - e.size(), e.size(), e) == 0;
}

virusLib::DeviceModel detectAbcModel(const std::vector<uint8_t>& data)
{
    const auto versionString = virusLib::ROMFile::readOsVersion(data);
    if (versionString.empty())
        return virusLib::DeviceModel::Invalid;

    const auto starts = [&versionString](const char* key)
    {
        return versionString.find(key) == 0;
    };

    if (starts("vb") || starts("vcl") || starts("vrt"))
        return virusLib::DeviceModel::B;

    if (starts("vc") || starts("vr_6"))
        return virusLib::DeviceModel::C;

    if (starts("vr"))
        return virusLib::DeviceModel::B;

    if (starts("v2"))
        return virusLib::DeviceModel::A;

    return virusLib::DeviceModel::Invalid;
}

bool convertMidiFirmware(std::vector<uint8_t>& data, std::string& error)
{
    synthLib::SysexBuffer midiData;
    midiData.insert(midiData.end(), data.begin(), data.end());

    virusLib::MidiFileToRomData midiLoader;
    if (!midiLoader.load(midiData, true) || !midiLoader.isValid())
    {
        error = "MIDI ROM conversion failed.";
        return false;
    }

    // Sector 0 is firmware. Sector 8 is a preset-bank MIDI file, which is useful
    // later but cannot boot the DSP by itself.
    if (midiLoader.getFirstSector() != 0)
    {
        std::ostringstream out;
        out << "MIDI file is not firmware (first sector "
            << static_cast<unsigned>(midiLoader.getFirstSector())
            << ").";
        error = out.str();
        return false;
    }

    data = midiLoader.getData();

    // Old Virus A OS updater is $2000 shorter; upstream ROMLoader pads it.
    if (data.size() == 0x38000)
        data.resize(virusLib::ROMFile::getRomSizeModelABC() >> 1, 0xff);

    const auto expectedHalf = virusLib::ROMFile::getRomSizeModelABC() >> 1;
    if (data.size() != expectedHalf)
    {
        std::ostringstream out;
        out << "Converted MIDI has unexpected ROM size: "
            << data.size() << " bytes.";
        error = out.str();
        return false;
    }

    return true;
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
        const bool midiSource = hasExtension(name, ".mid") || hasExtension(name, ".midi");

        if (midiSource)
        {
            std::string conversionError;
            if (!convertMidiFirmware(data, conversionError))
            {
                const std::string s = std::string("ROM VALID: FAILED\n") +
                                      conversionError + "\nFile: " + name;
                return env->NewStringUTF(s.c_str());
            }
        }

        const auto model = detectAbcModel(data);
        if (model == virusLib::DeviceModel::Invalid)
        {
            std::ostringstream out;
            out << "ROM VALID: FAILED\n"
                << "Could not identify Virus A/B/C firmware.\n"
                << "File: " << name << "\n"
                << "Input size: " << size << " bytes";
            const auto s = out.str();
            return env->NewStringUTF(s.c_str());
        }

        virusLib::ROMFile probe(data, name, model);

        if (!probe.isValid())
        {
            std::ostringstream out;
            out << "ROM VALID: FAILED\n"
                << "Firmware container was recognized but ROM parsing failed.\n"
                << "File: " << name << "\n"
                << "ROM size after conversion: " << data.size() << " bytes";
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

        auto device = std::make_unique<virusLib::Device>(params, false);

        if (!device->isValid())
            return env->NewStringUTF("DSP BOOT: FAILED\nDevice object is not valid.");

        std::ostringstream out;
        out << "ROM VALID: OK\n"
            << "Source: " << (midiSource ? "MIDI OS update" : "binary ROM") << "\n"
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
