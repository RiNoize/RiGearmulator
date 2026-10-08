// One translation unit, preserving the 0.9 DSP, SRC and AudioTrack JNI bridge.
#include "RiGearRuntime.cpp"
#include <limits>

namespace {
using Bytes = std::vector<uint8_t>;
Bytes studioBytes(JNIEnv* env, jbyteArray data, size_t maximum = 267 * 18) {
    if (!data) throw std::invalid_argument("Missing SysEx data");
    const auto count = env->GetArrayLength(data);
    if (count <= 0 || static_cast<size_t>(count) > maximum)
        throw std::invalid_argument("Invalid SysEx size");
    Bytes result(static_cast<size_t>(count));
    env->GetByteArrayRegion(data, 0, count, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read JNI data");
    return result;
}
template<class Allocator>
jbyteArray studioArray(JNIEnv* env, const std::vector<uint8_t, Allocator>& data) {
    auto result = env->NewByteArray(static_cast<jsize>(data.size()));
    if (result && !data.empty()) env->SetByteArrayRegion(result, 0,
        static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
    return result;
}
void studioChecksum(Bytes& data) {
    unsigned sum = 0;
    for (size_t i = 5; i < 265; ++i) sum += data[i];
    data[265] = static_cast<uint8_t>(sum & 127);
}
std::vector<Bytes> studioValidate(const Bytes& data) {
    if (data.empty() || data.size() % 267 != 0 || data.size() > 267 * 18)
        throw std::invalid_argument("Only complete Virus A/B/C preset dumps are supported");
    std::vector<Bytes> packets;
    for (size_t pos = 0; pos < data.size(); pos += 267) {
        Bytes p(data.begin() + pos, data.begin() + pos + 267);
        if (p[0] != 0xf0 || p[1] != 0 || p[2] != 0x20 || p[3] != 0x33 || p[4] != 1 ||
            (p[6] != 0x10 && p[6] != 0x11) || p[266] != 0xf7)
            throw std::invalid_argument("Not a Virus A/B/C sound dump");
        for (size_t i = 1; i < 266; ++i) if (p[i] > 127)
            throw std::invalid_argument("Non MIDI data in sound dump");
        if (p[6] == 0x10 && p[9] >= 7)
            throw std::invalid_argument("TI sound data is not compatible with Virus A/B/C");
        const auto checksum = p[265]; studioChecksum(p);
        if (checksum != p[265]) throw std::invalid_argument("Invalid Virus SysEx checksum");
        packets.emplace_back(std::move(p));
    }
    return packets;
}
synthLib::SMidiEvent studioParam(uint8_t page, uint8_t part, uint8_t index, uint8_t value) {
    return rgSysex({0xf0, 0, 0x20, 0x33, 1, 0x10, page, part, index, value, 0xf7});
}
}

// Only call on the serialized control executor, never from the audio thread.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_rinoize_rigear_StudioBridge_snapshot(JNIEnv* env, jclass) {
    try {
        std::lock_guard<std::mutex> deviceLock(g_deviceMutex);
        if (!g_device) return nullptr;
        { std::lock_guard<std::mutex> midiLock(g_midiMutex);
          if (!g_pendingMidi.empty()) return nullptr; }
        Bytes state;
        if (!g_device->getState(state, synthLib::StateTypeCurrentProgram)) return nullptr;
        // Device::getState supplies the real edit buffers, including all 16 parts.
        return studioArray(env, state);
    } catch (const std::exception& e) { rgThrow(env, e.what()); }
      catch (...) { rgThrow(env, "Could not read current instrument state"); }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rigear_StudioBridge_firmwareInfo(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_deviceMutex);
    if (!g_romIndex) return env->NewStringUTF("Sin ROM");
    std::string text = "Virus " + g_romIndex->getModelName() + " / " + g_romIndex->getOsVersion();
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rinoize_rigear_StudioBridge_factory(JNIEnv* env, jclass) {
    try {
        std::lock_guard<std::mutex> lock(g_deviceMutex);
        if (!g_romIndex) return nullptr;
        jclass byteArrayClass = env->FindClass("[B");
        if (!byteArrayClass) return nullptr;
        auto result = env->NewObjectArray(static_cast<jsize>(g_hardwareBankCount * 128), byteArrayClass, nullptr);
        env->DeleteLocalRef(byteArrayClass);
        if (!result) return nullptr;
        for (uint32_t b = 0; b < g_hardwareBankCount; ++b) {
            for (int p = 0; p < 128; ++p) {
                virusLib::ROMFile::TPreset preset{};
                if (!readBrowserPreset(static_cast<int>(b), p, preset)) continue;
                auto dump = virusLib::Microcontroller::createSingleDump(*g_romIndex,
                    static_cast<virusLib::BankNumber>(b + 1), static_cast<uint8_t>(p), preset);
                auto bytes = studioArray(env, dump);
                if (!bytes) return nullptr;
                env->SetObjectArrayElement(result, static_cast<jsize>(b * 128 + p), bytes);
                env->DeleteLocalRef(bytes);
                if (env->ExceptionCheck()) return nullptr;
            }
        }
        return result;
    } catch (const std::exception& e) { rgThrow(env, e.what()); }
      catch (...) { rgThrow(env, "Could not index factory sounds"); }
    return nullptr;
}

// Select Factory exactly like a hardware/MIDI patch change.
// Single mode: CC32 Bank Select LSB + Program Change. Multi: the same bank/program
// is addressed directly to the selected part through the Virus multi parameter page.
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_StudioBridge_selectFactory(JNIEnv* env, jclass, jint bank, jint program, jint part) {
    try {
        if (bank < 1 || bank > 8 || program < 0 || program > 127)
            throw std::invalid_argument("Invalid Factory bank/program");
        if ((part < 0 || part > 15) && part != 64)
            throw std::invalid_argument("Invalid target part");
        std::lock_guard<std::mutex> lock(g_midiMutex);
        if (g_pendingMidi.size() > 3072) throw std::runtime_error("MIDI queue busy");
        if (part == 64) {
            // This is the same path that already works from an external controller.
            g_pendingMidi.emplace_back(synthLib::MidiEventSource::Editor, 0xb0, 32,
                                       static_cast<uint8_t>(bank), 0);
            g_pendingMidi.emplace_back(synthLib::MidiEventSource::Editor, 0xc0,
                                       static_cast<uint8_t>(program), 0, 0);
        } else {
            // In Multi, address the selected part directly; no dependence on its current MIDI channel.
            g_pendingMidi.push_back(studioParam(0x72, static_cast<uint8_t>(part), 31,
                                                static_cast<uint8_t>(bank)));
            g_pendingMidi.push_back(studioParam(0x72, static_cast<uint8_t>(part), 33,
                                                static_cast<uint8_t>(program)));
        }
    } catch (const std::exception& e) { rgThrow(env, e.what()); }
      catch (...) { rgThrow(env, "Factory patch selection failed"); }
}

// kind=0: a single to target 0..15 or 64. kind=1: multi configuration/arrangement.
// All imported bank destinations are rewritten to edit buffers: NEVER overwrite factory/RAM banks.
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_StudioBridge_load(JNIEnv* env, jclass, jbyteArray data, jint target, jint kind) {
    try {
        auto packets = studioValidate(studioBytes(env, data));
        if ((target < 0 || target > 15) && target != 64) throw std::invalid_argument("Invalid part");
        if (kind != 0 && kind != 1) throw std::invalid_argument("Invalid preset kind");
        if (kind == 0 && (packets.size() != 1 || packets[0][6] != 0x10))
            throw std::invalid_argument("Select one Single sound");
        int multiCount = 0;
        for (const auto& p : packets) if (p[6] == 0x11) ++multiCount;
        if (kind == 1 && multiCount != 1) throw std::invalid_argument("Select one Multi arrangement");
        std::vector<synthLib::SMidiEvent> commands;
        if (kind == 0) {
            auto p = packets.front(); p[5] = 0x10; p[7] = 0; p[8] = static_cast<uint8_t>(target); studioChecksum(p);
            synthLib::SMidiEvent e(synthLib::MidiEventSource::Editor); e.sysex.assign(p.begin(), p.end()); commands.emplace_back(std::move(e));
        } else {
            const auto it = std::find_if(packets.begin(), packets.end(), [](const Bytes& p) { return p[6] == 0x11; });
            Bytes multi = *it; multi[5] = 0x10; multi[7] = 0; multi[8] = 0; studioChecksum(multi);
            synthLib::SMidiEvent e(synthLib::MidiEventSource::Editor); e.sysex.assign(multi.begin(), multi.end()); commands.emplace_back(std::move(e));
            for (int part = 0; part < 16; ++part) {
                auto single = std::find_if(packets.begin(), packets.end(), [part](const Bytes& p) {
                    return p[6] == 0x10 && p[7] == 0 && p[8] == part;
                });
                if (single != packets.end()) {
                    Bytes p = *single; p[5] = 0x10; p[7] = 0; studioChecksum(p);
                    synthLib::SMidiEvent se(synthLib::MidiEventSource::Editor); se.sysex.assign(p.begin(), p.end()); commands.emplace_back(std::move(se));
                } else {
                    // Standalone Multi dumps only reference bank/program sounds.
                    commands.push_back(studioParam(0x72, part, 31, (*it)[9 + 32 + part]));
                    commands.push_back(studioParam(0x72, part, 33, (*it)[9 + 48 + part]));
                }
            }
        }
        std::lock_guard<std::mutex> lock(g_midiMutex);
        if (g_pendingMidi.size() > 3072) throw std::runtime_error("MIDI queue busy; try again after START");
        rgPanicLocked(false);
        for (auto& command : commands) g_pendingMidi.emplace_back(std::move(command));
    } catch (const std::exception& e) { rgThrow(env, e.what()); }
      catch (...) { rgThrow(env, "Could not queue sound"); }
}

// Triples {page,index,value}. One lock keeps paired filter updates adjacent.
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_StudioBridge_parameters(JNIEnv* env, jclass, jint part, jintArray triples) {
    try {
        if ((part < 0 || part > 15) && part != 64) throw std::invalid_argument("Invalid part");
        if (!triples) return;
        auto count = env->GetArrayLength(triples);
        if (count < 3 || count > 96 || count % 3 != 0) throw std::invalid_argument("Invalid parameter batch");
        std::array<jint, 96> values{}; env->GetIntArrayRegion(triples, 0, count, values.data());
        if (env->ExceptionCheck()) return;
        for (int i = 0; i < count; i += 3)
            if (values[i] < 0x70 || values[i] > 0x72 || values[i+1] < 0 || values[i+1] > 127 || values[i+2] < 0 || values[i+2] > 127)
                throw std::invalid_argument("Invalid Virus parameter");
        std::lock_guard<std::mutex> lock(g_midiMutex);
        if (g_pendingMidi.size() > 3072) throw std::runtime_error("MIDI queue busy");
        for (int i = 0; i < count; i += 3) {
            auto command = studioParam(values[i], part, values[i+1], values[i+2]);
            // Coalesce only the immediately preceding same parameter; never cross notes/patches.
            if (!g_pendingMidi.empty()) {
                auto& last = g_pendingMidi.back().sysex;
                if (last.size() == 11 && last[6] == values[i] && last[7] == part && last[8] == values[i+1]) {
                    last[9] = static_cast<uint8_t>(values[i+2]); continue;
                }
            }
            g_pendingMidi.emplace_back(std::move(command));
        }
    } catch (const std::exception& e) { rgThrow(env, e.what()); }
      catch (...) { rgThrow(env, "Parameter update failed"); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_StudioBridge_mode(JNIEnv* env, jclass, jboolean multi) {
    try {
        std::lock_guard<std::mutex> lock(g_midiMutex);
        if (g_pendingMidi.size() > 3072) throw std::runtime_error("MIDI queue busy");
        rgPanicLocked(false);
        g_pendingMidi.push_back(studioParam(0x72, 0, 122, multi ? 2 : 0));
    } catch (const std::exception& e) { rgThrow(env, e.what()); }
}
