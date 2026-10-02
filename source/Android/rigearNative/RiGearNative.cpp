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
std::unique_ptr<virusLib::ROMFile> g_romIndex;
std::deque<synthLib::SMidiEvent> g_pendingMidi;

uint32_t g_validRomBankCount = 0;
uint32_t g_hardwareBankCount = 0;
int g_selectedBank = 0;
int g_selectedProgram = 0;
std::array<uint8_t, 128> g_currentPageA{};

std::array<std::vector<float>, 4> g_inputBuffers;
std::array<std::vector<float>, 12> g_outputBuffers;
std::vector<float> g_interleaved;

uint8_t g_runningStatus = 0;
uint8_t g_midiData[2] = {0, 0};
uint8_t g_midiDataCount = 0;
uint8_t g_midiDataNeeded = 0;
bool g_inSysex = false;

uint64_t g_externalMessageCount = 0;
uint64_t g_externalNoteOnCount = 0;
uint64_t g_externalNoteOffCount = 0;
uint32_t g_externalActiveNoteCount = 0;
std::array<bool, 16 * 128> g_externalActiveNotes{};

void resetMidiParserState()
{
    g_runningStatus = 0;
    g_midiData[0] = 0;
    g_midiData[1] = 0;
    g_midiDataCount = 0;
    g_midiDataNeeded = 0;
    g_inSysex = false;
}

void resetExternalMidiStatsLocked()
{
    g_externalMessageCount = 0;
    g_externalNoteOnCount = 0;
    g_externalNoteOffCount = 0;
    g_externalActiveNoteCount = 0;
    g_externalActiveNotes.fill(false);
    resetMidiParserState();
}

void clearActiveNotesForChannel(const uint8_t channel)
{
    const size_t base = static_cast<size_t>(channel) * 128;
    for (size_t note = 0; note < 128; ++note)
    {
        auto& active = g_externalActiveNotes[base + note];
        if (active)
        {
            active = false;
            if (g_externalActiveNoteCount > 0)
                --g_externalActiveNoteCount;
        }
    }
}

void updateExternalStatsForMessage(
    const uint8_t status,
    const uint8_t data1,
    const uint8_t data2)
{
    ++g_externalMessageCount;

    const uint8_t type = status & 0xf0;
    const uint8_t channel = status & 0x0f;

    if (type == synthLib::M_NOTEON && data2 != 0)
    {
        ++g_externalNoteOnCount;
        auto& active = g_externalActiveNotes[
            static_cast<size_t>(channel) * 128 + data1];

        if (!active)
        {
            active = true;
            ++g_externalActiveNoteCount;
        }
    }
    else if (type == synthLib::M_NOTEOFF ||
             (type == synthLib::M_NOTEON && data2 == 0))
    {
        ++g_externalNoteOffCount;
        auto& active = g_externalActiveNotes[
            static_cast<size_t>(channel) * 128 + data1];

        if (active)
        {
            active = false;
            if (g_externalActiveNoteCount > 0)
                --g_externalActiveNoteCount;
        }
    }
    else if (type == synthLib::M_CONTROLCHANGE &&
             (data1 == synthLib::MC_ALLNOTESOFF ||
              data1 == synthLib::MC_ALLSOUNDOFF))
    {
        clearActiveNotesForChannel(channel);
    }
}

void enqueueExternalChannelMessage(
    const uint8_t status,
    const uint8_t data1,
    const uint8_t data2)
{
    updateExternalStatsForMessage(status, data1, data2);

    g_pendingMidi.emplace_back(
        synthLib::MidiEventSource::Physical,
        status,
        data1,
        data2,
        0);
}

void parseExternalMidiBytes(const uint8_t* data, const size_t size)
{
    for (size_t i = 0; i < size; ++i)
    {
        const uint8_t byte = data[i];

        // MIDI realtime may appear between any two bytes and does not alter
        // running status. The Virus handles clock later; ignore it in this
        // first USB-MIDI validation build.
        if (byte >= 0xf8)
            continue;

        if (byte & 0x80)
        {
            if (byte == 0xf0)
            {
                g_inSysex = true;
                g_runningStatus = 0;
                g_midiDataCount = 0;
                continue;
            }

            if (byte == 0xf7)
            {
                g_inSysex = false;
                g_runningStatus = 0;
                g_midiDataCount = 0;
                continue;
            }

            if (byte >= 0xf0)
            {
                // System common messages are not needed for the first MIDI
                // keyboard test. They cancel channel running status.
                g_inSysex = false;
                g_runningStatus = 0;
                g_midiDataCount = 0;
                g_midiDataNeeded = 0;
                continue;
            }

            g_inSysex = false;
            g_runningStatus = byte;
            g_midiDataCount = 0;

            const uint8_t type = byte & 0xf0;
            g_midiDataNeeded =
                (type == synthLib::M_PROGRAMCHANGE ||
                 type == synthLib::M_AFTERTOUCH) ? 1 : 2;
            continue;
        }

        if (g_inSysex || g_runningStatus == 0 || g_midiDataNeeded == 0)
            continue;

        if (g_midiDataCount < 2)
            g_midiData[g_midiDataCount++] = byte & 0x7f;

        if (g_midiDataCount >= g_midiDataNeeded)
        {
            const uint8_t data1 = g_midiData[0];
            const uint8_t data2 =
                g_midiDataNeeded > 1 ? g_midiData[1] : 0;

            enqueueExternalChannelMessage(
                g_runningStatus,
                data1,
                data2);

            g_midiDataCount = 0;
        }
    }
}

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

bool decodeMidiImage(
    std::vector<uint8_t>& data,
    uint8_t& firstSector,
    std::string& error)
{
    synthLib::SysexBuffer midiData;
    midiData.insert(midiData.end(), data.begin(), data.end());

    virusLib::MidiFileToRomData loader;
    if (!loader.load(midiData, true) || !loader.isValid())
    {
        error = "MIDI conversion failed.";
        return false;
    }

    firstSector = loader.getFirstSector();
    data = loader.getData();

    if (firstSector == 0 && data.size() == 0x38000)
        data.resize(virusLib::ROMFile::getRomSizeModelABC() >> 1, 0xff);

    const auto halfSize = virusLib::ROMFile::getRomSizeModelABC() >> 1;
    if (data.size() != halfSize)
    {
        std::ostringstream out;
        out << "MIDI image has unexpected size: " << data.size();
        error = out.str();
        return false;
    }

    return true;
}

int sourceRomBankForHardwareBank(const int hardwareBank)
{
    if (hardwareBank < 0)
        return -1;

    // Virus B/C expose RAM A/B followed by ROM C..H. At startup the emulation
    // initializes RAM A/B from the first two ROM banks, so the browser can use
    // the ROM copy for names and initial parameter values.
    return hardwareBank < 2 ? hardwareBank : hardwareBank - 2;
}

bool readBrowserPreset(
    const int hardwareBank,
    const int program,
    virusLib::ROMFile::TPreset& preset)
{
    if (!g_romIndex || program < 0 || program >= 128)
        return false;

    const int sourceBank = sourceRomBankForHardwareBank(hardwareBank);
    if (sourceBank < 0 ||
        sourceBank >= static_cast<int>(g_validRomBankCount))
        return false;

    return g_romIndex->getSingle(sourceBank, program, preset);
}

void cachePresetPageA(const virusLib::ROMFile::TPreset& preset)
{
    for (size_t i = 0; i < g_currentPageA.size(); ++i)
        g_currentPageA[i] = preset[i];
}

std::string bootPreparedRom(
    std::vector<uint8_t> data,
    const std::string& name,
    const std::string& sourceDescription)
{
    const auto model = detectAbcModel(data);
    if (model == virusLib::DeviceModel::Invalid)
    {
        return "ROM VALID: FAILED\nCould not identify Virus A/B/C firmware.";
    }

    auto romIndex = std::make_unique<virusLib::ROMFile>(data, name, model);
    if (!romIndex->isValid())
    {
        return "ROM VALID: FAILED\nFirmware was recognized but ROM parsing failed.";
    }

    synthLib::DeviceCreateParams params;
    params.hostSamplerate = 48000.0f;
    params.preferredSamplerate = 0.0f;
    params.romName = name;
    params.romData = data;
    params.customData = static_cast<uint32_t>(model);

    std::lock_guard<std::mutex> deviceLock(g_deviceMutex);

    g_device.reset();
    g_romIndex.reset();
    clearPendingMidi();

    {
        std::lock_guard<std::mutex> midiLock(g_midiMutex);
        resetExternalMidiStatsLocked();
        g_currentPageA.fill(0);
    }

    auto device = std::make_unique<virusLib::Device>(params, false);
    if (!device->isValid())
        return "DSP BOOT: FAILED\nDevice object is not valid.";

    uint32_t validRomBanks = 0;
    for (uint32_t bank = 0; bank < 8; ++bank)
    {
        virusLib::ROMFile::TPreset first{};
        virusLib::ROMFile::TPreset last{};

        if (!romIndex->getSingle(static_cast<int>(bank), 0, first) ||
            !romIndex->getSingle(static_cast<int>(bank), 127, last))
            break;

        if (virusLib::ROMFile::getSingleName(first).size() != 10 ||
            virusLib::ROMFile::getSingleName(last).size() != 10)
            break;

        ++validRomBanks;
    }

    g_validRomBankCount = validRomBanks;
    g_hardwareBankCount =
        validRomBanks > 0 ? std::min<uint32_t>(validRomBanks + 2, 8) : 0;
    g_selectedBank = 0;
    g_selectedProgram = 0;

    if (g_hardwareBankCount > 0)
    {
        virusLib::ROMFile::TPreset preset{};
        if (readBrowserPreset(0, 0, preset))
        {
            std::lock_guard<std::mutex> midiLock(g_midiMutex);
            cachePresetPageA(preset);
        }
    }

    const auto os = romIndex->getOsVersion();

    g_romIndex = std::move(romIndex);
    g_device = std::move(device);

    std::ostringstream out;
    out << "ROM VALID: OK\n"
        << "Source: " << sourceDescription << "\n"
        << "Model: Virus " << virusLib::getModelName(model) << "\n";

    if (!os.empty())
        out << "OS: " << os << "\n";

    out << "DSP BOOT: OK\n"
        << "Device sample rate: "
        << static_cast<uint32_t>(g_device->getSamplerate()) << " Hz\n"
        << "DSP clock: " << formatHz(g_device->getDspClockHz()) << "\n"
        << "Audio outputs: " << g_device->getChannelCountOut() << "\n"
        << "Patch banks: " << g_hardwareBankCount;

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
        {
            std::lock_guard<std::mutex> midiLock(g_midiMutex);
            resetExternalMidiStatsLocked();
        }

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

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeLoadRomBundle(
    JNIEnv* env,
    jclass,
    jobjectArray dataArrays,
    jobjectArray nameArrays)
{
    if (!dataArrays || !nameArrays)
        return env->NewStringUTF("ROM LOAD: FAILED\nNo files received.");

    const jsize count =
        std::min(env->GetArrayLength(dataArrays), env->GetArrayLength(nameArrays));

    std::vector<uint8_t> binaryImage;
    std::string binaryName;

    std::vector<uint8_t> firmwareImage;
    std::string firmwareName;

    std::vector<uint8_t> presetImage;
    std::string presetName;

    for (jsize i = 0; i < count; ++i)
    {
        auto bytes = static_cast<jbyteArray>(
            env->GetObjectArrayElement(dataArrays, i));
        auto nameString = static_cast<jstring>(
            env->GetObjectArrayElement(nameArrays, i));

        if (!bytes || !nameString)
        {
            if (bytes)
                env->DeleteLocalRef(bytes);
            if (nameString)
                env->DeleteLocalRef(nameString);
            continue;
        }

        const jsize size = env->GetArrayLength(bytes);
        std::vector<uint8_t> data(static_cast<size_t>(std::max<jsize>(size, 0)));
        if (size > 0)
        {
            env->GetByteArrayRegion(
                bytes,
                0,
                size,
                reinterpret_cast<jbyte*>(data.data()));
        }

        const char* chars = env->GetStringUTFChars(nameString, nullptr);
        std::string name = chars ? chars : "virus.mid";
        if (chars)
            env->ReleaseStringUTFChars(nameString, chars);

        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(nameString);

        if (hasExtension(name, ".bin"))
        {
            if (binaryImage.empty())
            {
                binaryImage = std::move(data);
                binaryName = name;
            }
            continue;
        }

        if (!hasExtension(name, ".mid") && !hasExtension(name, ".midi"))
            continue;

        uint8_t firstSector = 0xff;
        std::string error;

        if (!decodeMidiImage(data, firstSector, error))
            continue;

        if (firstSector == 0 && firmwareImage.empty())
        {
            firmwareImage = std::move(data);
            firmwareName = name;
        }
        else if (firstSector == 8 && presetImage.empty())
        {
            presetImage = std::move(data);
            presetName = name;
        }
    }

    try
    {
        std::string result;

        if (!binaryImage.empty())
        {
            result = bootPreparedRom(
                std::move(binaryImage),
                binaryName,
                "binary ROM");
        }
        else if (!firmwareImage.empty())
        {
            std::string source = "MIDI OS update";
            std::string name = firmwareName;

            if (!presetImage.empty())
            {
                firmwareImage.insert(
                    firmwareImage.end(),
                    presetImage.begin(),
                    presetImage.end());

                source = "MIDI OS + preset image";
                name += " + " + presetName;
            }

            result = bootPreparedRom(
                std::move(firmwareImage),
                name,
                source);
        }
        else
        {
            result = "ROM VALID: FAILED\nNo bootable Virus firmware found.";
        }

        return env->NewStringUTF(result.c_str());
    }
    catch (const std::exception& e)
    {
        const std::string s =
            std::string("DSP BOOT: EXCEPTION\n") + e.what();
        return env->NewStringUTF(s.c_str());
    }
    catch (...)
    {
        return env->NewStringUTF(
            "DSP BOOT: EXCEPTION\nUnknown native error.");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeGetPatchBankCount(
    JNIEnv*,
    jclass)
{
    std::lock_guard<std::mutex> lock(g_deviceMutex);
    return static_cast<jint>(g_hardwareBankCount);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeGetPatchName(
    JNIEnv* env,
    jclass,
    jint bank,
    jint program)
{
    std::lock_guard<std::mutex> lock(g_deviceMutex);

    virusLib::ROMFile::TPreset preset{};
    if (!readBrowserPreset(bank, program, preset))
        return env->NewStringUTF("");

    const auto name = virusLib::ROMFile::getSingleName(preset);
    return env->NewStringUTF(name.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeSelectPatch(
    JNIEnv* env,
    jclass,
    jint bank,
    jint program)
{
    std::lock_guard<std::mutex> deviceLock(g_deviceMutex);

    if (bank < 0 ||
        bank >= static_cast<jint>(g_hardwareBankCount) ||
        program < 0 ||
        program >= 128)
        return env->NewStringUTF("");

    virusLib::ROMFile::TPreset preset{};
    if (!readBrowserPreset(bank, program, preset))
        return env->NewStringUTF("");

    {
        std::lock_guard<std::mutex> midiLock(g_midiMutex);

        g_pendingMidi.emplace_back(
            synthLib::MidiEventSource::Host,
            synthLib::M_CONTROLCHANGE,
            synthLib::MC_BANKSELECTLSB,
            static_cast<uint8_t>(bank + 1),
            0);

        g_pendingMidi.emplace_back(
            synthLib::MidiEventSource::Host,
            synthLib::M_PROGRAMCHANGE,
            static_cast<uint8_t>(program),
            0,
            0);

        cachePresetPageA(preset);
    }

    g_selectedBank = bank;
    g_selectedProgram = program;

    const auto name = virusLib::ROMFile::getSingleName(preset);
    return env->NewStringUTF(name.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeGetParameter(
    JNIEnv*,
    jclass,
    jint cc)
{
    if (cc < 0 || cc >= 128)
        return 0;

    std::lock_guard<std::mutex> lock(g_midiMutex);
    return g_currentPageA[static_cast<size_t>(cc)];
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeSetParameter(
    JNIEnv*,
    jclass,
    jint cc,
    jint value)
{
    if (cc < 0 || cc >= 128)
        return JNI_FALSE;

    const int v = std::clamp(static_cast<int>(value), 0, 127);

    std::lock_guard<std::mutex> lock(g_midiMutex);

    g_currentPageA[static_cast<size_t>(cc)] =
        static_cast<uint8_t>(v);

    g_pendingMidi.emplace_back(
        synthLib::MidiEventSource::Host,
        synthLib::M_CONTROLCHANGE,
        static_cast<uint8_t>(cc),
        static_cast<uint8_t>(v),
        0);

    return JNI_TRUE;
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

    g_externalActiveNotes.fill(false);
    g_externalActiveNoteCount = 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeSendMidiBytes(
    JNIEnv* env,
    jclass,
    jbyteArray bytes,
    jint offset,
    jint count)
{
    if (!bytes || offset < 0 || count <= 0)
        return JNI_FALSE;

    const jsize length = env->GetArrayLength(bytes);
    if (offset > length || count > length - offset)
        return JNI_FALSE;

    std::vector<uint8_t> data(static_cast<size_t>(count));
    env->GetByteArrayRegion(
        bytes,
        offset,
        count,
        reinterpret_cast<jbyte*>(data.data()));

    std::lock_guard<std::mutex> lock(g_midiMutex);
    parseExternalMidiBytes(data.data(), data.size());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeResetMidiStats(
    JNIEnv*,
    jclass)
{
    std::lock_guard<std::mutex> lock(g_midiMutex);
    resetExternalMidiStatsLocked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rigear_NativeBridge_nativeGetMidiStats(
    JNIEnv* env,
    jclass)
{
    std::lock_guard<std::mutex> lock(g_midiMutex);

    std::ostringstream out;
    out << "USB MIDI messages: " << g_externalMessageCount
        << "   Note On: " << g_externalNoteOnCount
        << "   Note Off: " << g_externalNoteOffCount
        << "   Active: " << g_externalActiveNoteCount;

    const auto s = out.str();
    return env->NewStringUTF(s.c_str());
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
    g_romIndex.reset();
    g_validRomBankCount = 0;
    g_hardwareBankCount = 0;
    g_selectedBank = 0;
    g_selectedProgram = 0;
    clearPendingMidi();
    {
        std::lock_guard<std::mutex> midiLock(g_midiMutex);
        resetExternalMidiStatsLocked();
    }
}
