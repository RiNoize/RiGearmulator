#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cctype>
#include <deque>
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
std::mutex g_deviceMutex;
std::mutex g_midiMutex;
std::unique_ptr<virusLib::Device> g_device;
std::deque<synthLib::SMidiEvent> g_pendingMidi;

std::array<std::vector<float>, 4> g_inputBuffers;
std::array<std::vector<float>, 12> g_outputBuffers;
std::vector<float> g_interleaved;

void clearPendingMidi()
{
    std::lock_guard<std::mutex> lock(g_midiMutex);
    g_pendingMidi.clear();
}

void ensureAudioScratch(const size_t frames)
{
    for (auto& b : g_inputBuffers)
    {
        if (b.size() != frames)
            b.assign(frames, 0.0f);
        else
            std::fill(b.begin(), b.end(), 0.0f);
    }

    for (auto& b : g_outputBuffers)
    {
        if (b.size() != frames)
            b.assign(frames, 0.0f);
        else
            std::fill(b.begin(), b.end(), 0.0f);
    }

    if (g_interleaved.size() != frames * 2)
        g_interleaved.assign(frames * 2, 0.0f);
}

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

        std::lock_guard<std::mutex> lock(g_deviceMutex);
        g_device.reset();
        clearPendingMidi();

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

extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeGetDeviceSampleRate(
    JNIEnv*,
    jclass)
{
    std::lock_guard<std::mutex> lock(g_deviceMutex);
    if (!g_device)
        return 0;
    return static_cast<jint>(std::lround(g_device->getSamplerate()));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeRenderTestNote(
    JNIEnv* env,
    jclass,
    jint note,
    jint velocity,
    jint durationMs)
{
    std::lock_guard<std::mutex> lock(g_deviceMutex);

    if (!g_device)
        return env->NewFloatArray(0);

    const int midiNote = std::clamp(static_cast<int>(note), 0, 127);
    const int midiVelocity = std::clamp(static_cast<int>(velocity), 1, 127);
    const int heldMs = std::clamp(static_cast<int>(durationMs), 100, 5000);

    const uint32_t sampleRate =
        static_cast<uint32_t>(std::lround(g_device->getSamplerate()));
    constexpr uint32_t blockSize = 64;

    const uint32_t heldFramesRaw =
        static_cast<uint32_t>((static_cast<uint64_t>(sampleRate) * heldMs) / 1000);
    const uint32_t heldFrames =
        ((heldFramesRaw + blockSize - 1) / blockSize) * blockSize;

    const uint32_t tailFramesRaw = sampleRate;
    const uint32_t tailFrames =
        ((tailFramesRaw + blockSize - 1) / blockSize) * blockSize;

    const uint32_t totalFrames = heldFrames + tailFrames;

    std::vector<float> interleaved(static_cast<size_t>(totalFrames) * 2, 0.0f);

    std::array<std::vector<float>, 4> inputBuffers;
    std::array<std::vector<float>, 12> outputBuffers;

    for (auto& b : inputBuffers)
        b.assign(blockSize, 0.0f);
    for (auto& b : outputBuffers)
        b.assign(blockSize, 0.0f);

    synthLib::TAudioInputs inputs{};
    synthLib::TAudioOutputs outputs{};

    for (size_t i = 0; i < inputBuffers.size(); ++i)
        inputs[i] = inputBuffers[i].data();
    for (size_t i = 0; i < outputBuffers.size(); ++i)
        outputs[i] = outputBuffers[i].data();

    std::vector<synthLib::SMidiEvent> midiIn;
    std::vector<synthLib::SMidiEvent> midiOut;

    for (uint32_t pos = 0; pos < totalFrames; pos += blockSize)
    {
        for (auto& b : outputBuffers)
            std::fill(b.begin(), b.end(), 0.0f);

        midiIn.clear();

        if (pos == 0)
        {
            midiIn.emplace_back(
                synthLib::MidiEventSource::Host,
                synthLib::M_NOTEON,
                static_cast<uint8_t>(midiNote),
                static_cast<uint8_t>(midiVelocity),
                0);
        }

        if (pos == heldFrames)
        {
            midiIn.emplace_back(
                synthLib::MidiEventSource::Host,
                synthLib::M_NOTEOFF,
                static_cast<uint8_t>(midiNote),
                0,
                0);
        }

        g_device->process(inputs, outputs, blockSize, midiIn, midiOut);

        const auto* left = outputBuffers[0].data();
        const auto* right = outputBuffers[1].data();

        for (uint32_t i = 0; i < blockSize; ++i)
        {
            const size_t dst = static_cast<size_t>(pos + i) * 2;
            interleaved[dst] = std::clamp(left[i], -1.0f, 1.0f);
            interleaved[dst + 1] = std::clamp(right[i], -1.0f, 1.0f);
        }
    }

    auto result = env->NewFloatArray(static_cast<jsize>(interleaved.size()));
    if (!result)
        return nullptr;

    env->SetFloatArrayRegion(
        result,
        0,
        static_cast<jsize>(interleaved.size()),
        interleaved.data());

    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeNoteOn(
    JNIEnv*,
    jclass,
    jint note,
    jint velocity)
{
    const int n = std::clamp(static_cast<int>(note), 0, 127);
    const int v = std::clamp(static_cast<int>(velocity), 1, 127);

    std::lock_guard<std::mutex> lock(g_midiMutex);
    g_pendingMidi.emplace_back(
        synthLib::MidiEventSource::Host,
        synthLib::M_NOTEON,
        static_cast<uint8_t>(n),
        static_cast<uint8_t>(v),
        0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeNoteOff(
    JNIEnv*,
    jclass,
    jint note)
{
    const int n = std::clamp(static_cast<int>(note), 0, 127);

    std::lock_guard<std::mutex> lock(g_midiMutex);
    g_pendingMidi.emplace_back(
        synthLib::MidiEventSource::Host,
        synthLib::M_NOTEOFF,
        static_cast<uint8_t>(n),
        0,
        0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_NativeBridge_nativePanic(
    JNIEnv*,
    jclass)
{
    std::lock_guard<std::mutex> lock(g_midiMutex);
    g_pendingMidi.clear();

    for (int note = 0; note < 128; ++note)
    {
        g_pendingMidi.emplace_back(
            synthLib::MidiEventSource::Host,
            synthLib::M_NOTEOFF,
            static_cast<uint8_t>(note),
            0,
            0);
    }

    g_pendingMidi.emplace_back(
        synthLib::MidiEventSource::Host,
        synthLib::M_CONTROLCHANGE,
        synthLib::MC_ALLSOUNDOFF,
        0,
        0);

    g_pendingMidi.emplace_back(
        synthLib::MidiEventSource::Host,
        synthLib::M_CONTROLCHANGE,
        synthLib::MC_ALLNOTESOFF,
        0,
        0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeProcessAudio(
    JNIEnv* env,
    jclass,
    jfloatArray output,
    jint frames)
{
    if (!output || frames <= 0 || frames > 2048)
        return 0;

    const auto frameCount = static_cast<size_t>(frames);
    if (env->GetArrayLength(output) < frames * 2)
        return 0;

    std::lock_guard<std::mutex> deviceLock(g_deviceMutex);

    if (!g_device)
        return 0;

    ensureAudioScratch(frameCount);

    synthLib::TAudioInputs inputs{};
    synthLib::TAudioOutputs outputs{};

    for (size_t i = 0; i < g_inputBuffers.size(); ++i)
        inputs[i] = g_inputBuffers[i].data();

    for (size_t i = 0; i < g_outputBuffers.size(); ++i)
        outputs[i] = g_outputBuffers[i].data();

    thread_local std::vector<synthLib::SMidiEvent> midiIn;
    thread_local std::vector<synthLib::SMidiEvent> midiOut;
    midiIn.clear();
    midiOut.clear();

    {
        std::lock_guard<std::mutex> midiLock(g_midiMutex);
        if (midiIn.capacity() < g_pendingMidi.size())
            midiIn.reserve(g_pendingMidi.size());

        while (!g_pendingMidi.empty())
        {
            midiIn.emplace_back(std::move(g_pendingMidi.front()));
            g_pendingMidi.pop_front();
        }
    }

    g_device->process(inputs, outputs, frameCount, midiIn, midiOut);

    const auto* left = g_outputBuffers[0].data();
    const auto* right = g_outputBuffers[1].data();

    for (size_t i = 0; i < frameCount; ++i)
    {
        g_interleaved[i * 2] = std::clamp(left[i], -1.0f, 1.0f);
        g_interleaved[i * 2 + 1] = std::clamp(right[i], -1.0f, 1.0f);
    }

    env->SetFloatArrayRegion(
        output,
        0,
        static_cast<jsize>(frameCount * 2),
        g_interleaved.data());

    return frames;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeRelease(
    JNIEnv*,
    jclass)
{
    std::lock_guard<std::mutex> lock(g_deviceMutex);
    g_device.reset();
    clearPendingMidi();
}
