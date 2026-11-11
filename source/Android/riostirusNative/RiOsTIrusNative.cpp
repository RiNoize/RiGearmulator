// RiOsTIrus: minimal Android bridge for the Virus TI / TI2 / Snow engine (virusLib).
// Purpose: measure whether the TI DSPs reach realtime on the tablet. ROM boot,
// MIDI in, stereo out (outputs 1-2), CPU placement and per-thread diagnostics.
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include "virusLib/device.h"
#include "virusLib/deviceModel.h"
#include "virusLib/dspSingle.h"
#include "virusLib/microcontroller.h"
#include "virusLib/romfile.h"
#include "dsp56kEmu/dspthread.h"
#include "MidiBytes.h"
#include "ThreadPlacement.h"

namespace {
using Event = synthLib::SMidiEvent;
using Bytes = std::vector<std::uint8_t>;

constexpr std::size_t minRomBytes = 6 * 1024 * 1024, maxRomBytes = 9 * 1024 * 1024; // virusLib romloader limits
constexpr std::size_t maxShortEvents = 4096, maxSysexEvents = 256, maxSysexBytes = 1024 * 1024;

// Lock order is device -> MIDI. MIDI callbacks only take the MIDI lock.
std::mutex deviceMutex, midiMutex;
std::unique_ptr<virusLib::Device> device;
virusLib::DeviceModel deviceModel = virusLib::DeviceModel::Invalid;
std::unique_ptr<virusLib::ROMFile> romIndex;

// Current single (512 bytes: page A, B, 6E, 6F). Updated on patch selection, knob
// changes, external page A CCs, and re-read from the firmware's edit buffer.
// Guarded by midiMutex. currentKnown is false until the first read.
virusLib::ROMFile::TPreset currentSingle{};
bool currentKnown = false;
bool externalChange = false;   // Program Change / bank select from the keyboard: re-read edit buffer
std::uint32_t currentRevision = 1;
int currentBank = -1, currentProgram = -1;

std::deque<Event> shortQueue, sysexQueue;
std::size_t sysexBytes = 0;
riostirus::MidiBytes parser;
std::array<std::array<bool, 128>, 16> seenNotes{};
std::uint64_t midiMessages = 0, midiNotes = 0, midiCc = 0, midiPc = 0, midiSysex = 0, midiOverflow = 0;

std::array<std::vector<float>, 2> inputs;
std::array<std::vector<float>, 12> outputs;
std::vector<float> interleaved;
std::vector<Event> midiIn, midiOut;
int preparedFrames = 0;
std::atomic<float> targetGain{1.0f};
float appliedGain = 1.0f;
std::atomic<std::uint64_t> clips{0}, badSamples{0}, peakBits{0};

// CPU placement. TI/TI2 run two DSP threads ("DSP DSP A", "DSP DSP B"; the
// microcontroller runs inside DSP A's audio callback). Snow runs one ("DSP").
// The placer has its own mutex; the monitor never takes deviceMutex.
rinoize::ThreadPlacer placer;
std::atomic<dsp56k::DSPThread*> dspA{nullptr}, dspB{nullptr};
std::vector<std::string> placedThreads;
const std::vector<std::string> watchedThreads{"RiOsTIrus-Audio"};

void releaseDeviceLocked() {
    dspA.store(nullptr); dspB.store(nullptr);
    placer.forget();
    device.reset();
    romIndex.reset();
    deviceModel = virusLib::DeviceModel::Invalid;
    preparedFrames = 0;
    std::lock_guard<std::mutex> midiLock(midiMutex);
    currentKnown = false; currentBank = currentProgram = -1; ++currentRevision;
}

int pageOffset(int page) {
    switch (page) {
        case 0x70: return 0;
        case 0x71: return 128;
        case 0x6e: return 256;
        case 0x6f: return 384;
        default: return -1;
    }
}

// Re-read the firmware's single edit buffer (after a Program Change from the
// keyboard, or to confirm a selection). Caller holds deviceMutex, not midiMutex.
bool syncFromEngineLocked() {
    if (!device) return false;
    std::vector<std::uint8_t> state;
    if (!device->getState(state, synthLib::StateTypeCurrentProgram)) return false;
    // Single dump of the edit buffer: F0 00 20 33 01 dd 10 00 40 <256> cs <256> cs F7
    for (std::size_t i = 0; i + 524 <= state.size(); ++i) {
        if (state[i] != 0xf0 || state[i + 1] != 0x00 || state[i + 2] != 0x20 || state[i + 3] != 0x33 ||
            state[i + 6] != 0x10 || state[i + 7] != 0x00 || state[i + 8] != 0x40)
            continue;
        if (state[i + 523] != 0xf7) continue;
        std::lock_guard<std::mutex> midiLock(midiMutex);
        for (std::size_t b = 0; b < 256; ++b) currentSingle[b] = state[i + 9 + b];
        for (std::size_t b = 0; b < 256; ++b) currentSingle[256 + b] = state[i + 266 + b];
        currentKnown = true; ++currentRevision;
        return true;
    }
    return false;
}


void throwJava(JNIEnv* env, const char* text) {
    if (env->ExceptionCheck()) return;
    const auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) { env->ThrowNew(cls, text); env->DeleteLocalRef(cls); }
}
template<class R, class F> R guarded(JNIEnv* env, R fallback, F operation) {
    try { return operation(); }
    catch (const std::exception& e) { throwJava(env, e.what()); }
    catch (const std::string& e) { throwJava(env, e.c_str()); }
    catch (...) { throwJava(env, "Fallo del motor nativo OsTIrus."); }
    return fallback;
}
template<class F> void guardedVoid(JNIEnv* env, F operation) {
    guarded<int>(env, 0, [&] { operation(); return 1; });
}
std::string stringFromJava(JNIEnv* env, jstring value) {
    if (!value) return "firmware.bin";
    const auto* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("No se pudo leer el nombre del firmware.");
    std::string result(chars); env->ReleaseStringUTFChars(value, chars);
    return result;
}
void ensureDevice() {
    if (!device) throw std::runtime_error("Primero cargue el firmware del Virus TI.");
}
virusLib::DeviceModel modelFromJava(int model) {
    switch (model) {
        case 0: return virusLib::DeviceModel::TI;
        case 1: return virusLib::DeviceModel::TI2;
        case 2: return virusLib::DeviceModel::Snow;
        default: throw std::invalid_argument("Modelo invalido: TI, TI2 o Snow.");
    }
}
bool validRate(int rate) { return rate == 44100 || rate == 48000; }

bool enqueueShortLocked(std::uint8_t a, std::uint8_t b, std::uint8_t c) {
    ++midiMessages;
    const auto command = a & 0xf0;
    if (command == 0x90 || command == 0x80) {
        ++midiNotes;
        if (command == 0x90 && c) seenNotes[a & 15][b & 127] = true;
    } else if (command == 0xb0) {
        ++midiCc;
        // Virus page A parameters follow the CC numbers. Skip bank select and controllers
        // that are not patch data (mod wheel, breath, foot, data entry, volume, sustain...).
        if (currentKnown && b >= 5 && b != 6 && b != 7 && b != 32 && b < 120 && (b < 64 || b > 69)) {
            currentSingle[b] = c; ++currentRevision;
        }
    }
    else if (command == 0xc0) { ++midiPc; externalChange = true; }
    if (shortQueue.size() >= maxShortEvents) { ++midiOverflow; return false; }
    shortQueue.emplace_back(synthLib::MidiEventSource::Host, a, b, c, 0);
    return true;
}
// Knob changes: coalesce with a pending change of the same parameter so a fast drag
// never floods the one-SysEx-per-block queue.
bool enqueueParamLocked(std::uint8_t page, std::uint8_t index, std::uint8_t value) {
    for (auto it = sysexQueue.rbegin(); it != sysexQueue.rend(); ++it) {
        auto& m = it->sysex;
        if (m.size() == 11 && m[6] == page && m[7] == 0x40 && m[8] == index) { m[9] = value; return true; }
        if (!(m.size() == 11 && m[6] >= 0x6e && m[6] <= 0x73)) break;   // keep order with dumps
    }
    if (sysexQueue.size() >= maxSysexEvents) { ++midiOverflow; return false; }
    Event event(synthLib::MidiEventSource::Editor);
    const std::uint8_t bytes[] = {0xf0, 0x00, 0x20, 0x33, 0x01, 0x10, page, 0x40, index, value, 0xf7};
    event.sysex.assign(std::begin(bytes), std::end(bytes));
    sysexQueue.emplace_back(std::move(event)); sysexBytes += 11;
    return true;
}
std::string cleanName(const std::string& name) {
    std::string out;
    for (const char ch : name) out += (ch >= 32 && ch < 127 && ch != '\t') ? ch : ' ';
    while (!out.empty() && out.back() == ' ') out.pop_back();
    return out.empty() ? "(sin nombre)" : out;
}
bool enqueueSysexLocked(const Bytes& data) {
    ++midiMessages; ++midiSysex;
    if (sysexQueue.size() >= maxSysexEvents || data.size() > maxSysexBytes - sysexBytes) { ++midiOverflow; return false; }
    Event event(synthLib::MidiEventSource::Host);
    event.sysex.assign(data.begin(), data.end());
    sysexQueue.emplace_back(std::move(event)); sysexBytes += data.size();
    return true;
}
void panicLocked() {
    parser.reset();
    std::deque<Event> releases;
    for (int channel = 0; channel < 16; ++channel) {
        for (const int cc : {64, 120, 123})
            releases.emplace_back(synthLib::MidiEventSource::Host, static_cast<std::uint8_t>(0xb0 | channel),
                                  static_cast<std::uint8_t>(cc), 0, 0);
        for (int note = 0; note < 128; ++note) if (seenNotes[channel][note])
            releases.emplace_back(synthLib::MidiEventSource::Host, static_cast<std::uint8_t>(0x80 | channel),
                                  static_cast<std::uint8_t>(note), 0, 0);
        seenNotes[channel].fill(false);
    }
    while (!shortQueue.empty() && shortQueue.size() + releases.size() > maxShortEvents) shortQueue.pop_back();
    shortQueue.insert(shortQueue.begin(), releases.begin(), releases.end());
}
void clearMeters() { clips.store(0); badSamples.store(0); peakBits.store(0); }
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeGetCoreInfo(JNIEnv* env, jclass) {
    return guarded<jstring>(env, nullptr, [&] {
        return env->NewStringUTF("RiOsTIrus | Gearmulator virusLib | Virus TI / TI2 / Snow | ARM64");
    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeLoadRom(JNIEnv* env, jclass, jbyteArray data, jstring name, jint model, jint sampleRate) {
    return guarded<jstring>(env, nullptr, [&] {
        if (!data) throw std::invalid_argument("Falta el firmware.");
        const auto length = static_cast<std::size_t>(env->GetArrayLength(data));
        if (length < minRomBytes || length > maxRomBytes)
            throw std::invalid_argument("El firmware del Virus TI debe ser un .bin de 6 a 9 MiB.");
        if (!validRate(sampleRate)) throw std::invalid_argument("Frecuencia: 44100 o 48000 Hz.");
        const auto deviceModelRequested = modelFromJava(model);
        Bytes rom(length);
        env->GetByteArrayRegion(data, 0, static_cast<jsize>(length), reinterpret_cast<jbyte*>(rom.data()));
        if (env->ExceptionCheck()) throw std::runtime_error("No se pudo leer el firmware.");
        const auto filename = stringFromJava(env, name);

        std::lock_guard<std::mutex> lock(deviceMutex);
        // Java does this on its control worker, with audio stopped.
        releaseDeviceLocked();
        {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            shortQueue.clear(); sysexQueue.clear(); sysexBytes = 0; parser.reset();
            for (auto& channel : seenNotes) channel.fill(false);
        }
        auto index = std::make_unique<virusLib::ROMFile>(rom, filename, deviceModelRequested);
        if (!index->isValid()) throw std::runtime_error("El archivo no es un firmware de Virus TI reconocido.");
        synthLib::DeviceCreateParams params;
        params.romData = std::move(rom); params.romName = filename;
        params.preferredSamplerate = static_cast<float>(sampleRate);
        params.hostSamplerate = static_cast<float>(sampleRate);
        params.customData = static_cast<std::uint32_t>(deviceModelRequested);
        auto created = std::make_unique<virusLib::Device>(params, false);
        if (!created->isValid()) throw std::runtime_error("El DSP no pudo arrancar con este firmware.");

        dspA.store(created->getDspA() ? created->getDspA()->getDSPThread() : nullptr);
        dspB.store(created->getDspB() ? created->getDspB()->getDSPThread() : nullptr);
        placedThreads = deviceModelRequested == virusLib::DeviceModel::Snow
            ? std::vector<std::string>{"DSP"} : std::vector<std::string>{"DSP DSP A", "DSP DSP B"};
        deviceModel = deviceModelRequested;
        device = std::move(created);
        romIndex = std::move(index);
        clearMeters();
        syncFromEngineLocked();

        std::ostringstream out;
        out << "Virus " << virusLib::getModelName(deviceModel)
            << " | " << static_cast<int>(std::lround(device->getSamplerate())) << " Hz"
            << " | DSP " << (dspB.load() ? 2 : 1)
            << " | reloj DSP " << device->getDspClockHz() / 1000000 << " MHz"
            << " | salidas " << device->getChannelCountOut()
            << " | bancos ROM " << romIndex->getNumSingleBanks();
        if (!romIndex->getOsVersion().empty()) out << " | OS " << romIndex->getOsVersion();
        return env->NewStringUTF(out.str().c_str());
    });
}
extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeGetDeviceSampleRate(JNIEnv* env, jclass) {
    return guarded<jint>(env, 0, [] {
        std::lock_guard<std::mutex> lock(deviceMutex);
        return device ? static_cast<jint>(std::lround(device->getSamplerate())) : 0;
    });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeSendShort(JNIEnv* env, jclass, jint status, jint data1, jint data2) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        if (status < 0x80 || status > 0xef || data1 < 0 || data1 > 127 || data2 < 0 || data2 > 127)
            throw std::invalid_argument("Mensaje MIDI invalido.");
        std::lock_guard<std::mutex> lock(midiMutex);
        return static_cast<jboolean>(enqueueShortLocked(static_cast<std::uint8_t>(status),
            static_cast<std::uint8_t>(data1), static_cast<std::uint8_t>(data2)));
    });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeSendMidiBytes(JNIEnv* env, jclass, jbyteArray data, jint offset, jint count) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        if (!data || offset < 0 || count < 0 || count > 65536 || offset > env->GetArrayLength(data) - count)
            throw std::invalid_argument("Paquete MIDI invalido.");
        Bytes bytes(static_cast<std::size_t>(count));
        if (count) env->GetByteArrayRegion(data, offset, count, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return static_cast<jboolean>(JNI_FALSE);
        std::lock_guard<std::mutex> lock(midiMutex);
        const bool ok = parser.feed(bytes.data(), bytes.size(),
            [](std::uint8_t a, std::uint8_t b, std::uint8_t c) { return enqueueShortLocked(a, b, c); },
            [](const Bytes& sysex) { return enqueueSysexLocked(sysex); });
        return static_cast<jboolean>(ok);
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeResetMidiStats(JNIEnv* env, jclass) {
    guardedVoid(env, [] {
        std::lock_guard<std::mutex> lock(midiMutex);
        midiMessages = midiNotes = midiCc = midiPc = midiSysex = midiOverflow = 0;
    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeGetMidiStats(JNIEnv* env, jclass) {
    return guarded<jstring>(env, nullptr, [&] {
        std::ostringstream text;
        {
            std::lock_guard<std::mutex> lock(midiMutex);
            text << "MIDI " << midiMessages << " | notas " << midiNotes << " | CC " << midiCc << " | PC " << midiPc
                 << " | SysEx " << midiSysex << " | cola " << shortQueue.size() + sysexQueue.size()
                 << " | overflow " << midiOverflow;
        }
        return env->NewStringUTF(text.str().c_str());
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeRelease(JNIEnv* env, jclass) {
    guardedVoid(env, [] {
        std::lock_guard<std::mutex> lock(deviceMutex);
        releaseDeviceLocked();
        interleaved.clear();
        std::lock_guard<std::mutex> midiLock(midiMutex);
        shortQueue.clear(); sysexQueue.clear(); sysexBytes = 0; parser.reset();
        for (auto& channel : seenNotes) channel.fill(false);
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_prepare(JNIEnv* env, jclass, jint frames, jint clockPercent) {
    return guarded<jstring>(env, nullptr, [&] {
        if (frames != 256 && frames != 512 && frames != 1024)
            throw std::invalid_argument("Buffer permitido: 256, 512 o 1024 frames.");
        if (clockPercent != 50 && clockPercent != 75 && clockPercent != 100)
            throw std::invalid_argument("Reloj DSP: 50, 75 o 100%.");
        std::lock_guard<std::mutex> lock(deviceMutex); ensureDevice();
        if (!device->setDspClockPercent(static_cast<std::uint32_t>(clockPercent)))
            throw std::runtime_error("El motor rechazo el reloj DSP.");
        for (auto& in : inputs) in.assign(static_cast<std::size_t>(frames), 0.0f);
        for (auto& out : outputs) out.assign(static_cast<std::size_t>(frames), 0.0f);
        interleaved.assign(static_cast<std::size_t>(frames * 2), 0.0f);
        midiIn.reserve(512); midiOut.reserve(512);
        preparedFrames = frames; appliedGain = targetGain.load(); clearMeters();
        {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            parser.reset();
        }
#ifdef NDEBUG
        const char* build = "RELEASE";
#else
        const char* build = "DEBUG";
#endif
        std::ostringstream out;
        out << "OsTIrus " << build << " | Virus " << virusLib::getModelName(deviceModel)
            << " | DSP " << clockPercent << "% (" << device->getDspClockHz() / 1000000 << " MHz)"
            << " | " << static_cast<int>(std::lround(device->getSamplerate())) << " Hz | buffer " << frames;
        return env->NewStringUTF(out.str().c_str());
    });
}
extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_render(JNIEnv* env, jclass, jfloatArray buffer, jint frames) {
    return guarded<jint>(env, 0, [&] {
        if (!buffer) throw std::invalid_argument("Falta el buffer de audio.");
        std::lock_guard<std::mutex> lock(deviceMutex); ensureDevice();
        if (frames != preparedFrames || !preparedFrames)
            throw std::invalid_argument("El buffer de audio cambio sin preparar el motor.");
        if (env->GetArrayLength(buffer) < frames * 2) throw std::invalid_argument("Buffer Java insuficiente.");
        synthLib::TAudioInputs in{}; synthLib::TAudioOutputs out{};
        for (std::size_t i = 0; i < inputs.size(); ++i) in[i] = inputs[i].data();
        for (std::size_t i = 0; i < outputs.size(); ++i) out[i] = outputs[i].data();
        midiIn.clear(); midiOut.clear();
        {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            const auto budget = static_cast<std::size_t>(std::max(8, frames / 8));
            while (!shortQueue.empty() && midiIn.size() < budget) {
                midiIn.emplace_back(std::move(shortQueue.front())); shortQueue.pop_front();
            }
            if (!sysexQueue.empty()) {           // one SysEx per block keeps the firmware's input paced
                sysexBytes -= sysexQueue.front().sysex.size();
                midiIn.emplace_back(std::move(sysexQueue.front())); sysexQueue.pop_front();
            }
        }
        device->process(in, out, static_cast<std::size_t>(frames), midiIn, midiOut);
        const float goal = targetGain.load(std::memory_order_relaxed);
        const float step = (goal - appliedGain) / static_cast<float>(frames);
        std::uint64_t clipped = 0, bad = 0; float peak = 0.0f;
        for (int frame = 0; frame < frames; ++frame) {
            appliedGain += step;
            for (int channel = 0; channel < 2; ++channel) {
                float sample = outputs[static_cast<std::size_t>(channel)][static_cast<std::size_t>(frame)];
                if (!std::isfinite(sample)) { sample = 0.0f; ++bad; }
                peak = std::max(peak, std::abs(sample));
                if (std::abs(sample) >= 1.0f) ++clipped;
                interleaved[static_cast<std::size_t>(frame * 2 + channel)] = std::clamp(sample * appliedGain, -1.0f, 1.0f);
            }
        }
        appliedGain = goal;
        clips.fetch_add(clipped, std::memory_order_relaxed); badSamples.fetch_add(bad, std::memory_order_relaxed);
        std::uint32_t bits = 0; std::memcpy(&bits, &peak, sizeof(bits)); peakBits.store(bits, std::memory_order_relaxed);
        env->SetFloatArrayRegion(buffer, 0, frames * 2, interleaved.data());
        return env->ExceptionCheck() ? 0 : frames;
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_panic(JNIEnv* env, jclass) {
    guardedVoid(env, [] { std::lock_guard<std::mutex> lock(midiMutex); panicLocked(); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_setGainDb(JNIEnv* env, jclass, jint db) {
    guardedVoid(env, [&] { targetGain.store(std::pow(10.0f, std::clamp(static_cast<float>(db), -24.0f, 0.0f) / 20.0f)); });
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_signalStats(JNIEnv* env, jclass, jboolean reset) {
    return guarded<jlongArray>(env, nullptr, [&] {
        const jlong values[] = {static_cast<jlong>(clips.load()), static_cast<jlong>(badSamples.load()), static_cast<jlong>(peakBits.load())};
        if (reset) { clips.store(0); badSamples.store(0); }
        auto result = env->NewLongArray(3); if (result) env->SetLongArrayRegion(result, 0, 3, values); return result;
    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_setCpuPlacement(JNIEnv* env, jclass, jint mode) {
    return guarded<jstring>(env, nullptr, [&] {
        if (mode < 0 || mode > 2) throw std::invalid_argument("Modo de nucleos invalido.");
        // Control worker, audio stopped: the DSP threads exist since the firmware boot.
        std::lock_guard<std::mutex> lock(deviceMutex); ensureDevice();
        const auto summary = placer.apply(static_cast<rinoize::PlacementMode>(mode), placedThreads, watchedThreads);
        return env->NewStringUTF(summary.c_str());
    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_placementReport(JNIEnv* env, jclass) {
    return guarded<jstring>(env, nullptr, [&] {
        // Monitor thread only (500 ms): usage is measured between consecutive calls.
        std::string text = placer.report();
        // getCurrentMips() is refreshed by each DSP thread about twice per second;
        // an unsynchronized read of a diagnostic double, never used for control.
        const std::pair<const char*, dsp56k::DSPThread*> threads[] = {{"DSP A", dspA.load()}, {"DSP B", dspB.load()}};
        for (const auto& [label, thread] : threads) {
            if (!thread) continue;
            char mips[48];
            std::snprintf(mips, sizeof(mips), "%s: %.1f MIPS", label, thread->getCurrentMips());
            text += (text.empty() ? "" : "\n") + std::string(mips);
        }
        return env->NewStringUTF(text.c_str());
    });
}

// ---------------------------------------------------------------- library and editing

// One line per ROM single: bank \t program \t name \t category1 \t category2 \t version \t flags
// flags: 1 Arp, 2 Unison, 4 Chorus, 8 Phaser (same tags as the plugin's patch manager).
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeLibrary(JNIEnv* env, jclass) {
    return guarded<jstring>(env, nullptr, [&] {
        std::lock_guard<std::mutex> lock(deviceMutex); ensureDevice();
        std::string out;
        out.reserve(64 * 128 * 32);
        const auto banks = static_cast<int>(romIndex->getNumSingleBanks());
        for (int bank = 0; bank < banks; ++bank) {
            for (int program = 0; program < 128; ++program) {
                virusLib::ROMFile::TPreset preset{};
                if (!romIndex->getSingle(bank, program, preset)) continue;
                const int flags = (preset[128 + 15] ? 1 : 0) | (preset[384 + 120] ? 2 : 0) |
                                  (preset[105] ? 4 : 0) | (preset[128 + 85] ? 8 : 0);
                out += std::to_string(bank) + '\t' + std::to_string(program) + '\t' +
                       cleanName(virusLib::ROMFile::getSingleName(preset)) + '\t' +
                       std::to_string(preset[128 + 123]) + '\t' + std::to_string(preset[128 + 124]) + '\t' +
                       std::to_string(preset[0]) + '\t' + std::to_string(flags) + '\n';
            }
        }
        return env->NewStringUTF(out.c_str());
    });
}
// Loads a ROM single into the single edit buffer (the same dump the plugin sends).
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_NativeBridge_nativeSelectPatch(JNIEnv* env, jclass, jint bank, jint program) {
    return guarded<jstring>(env, nullptr, [&] {
        if (program < 0 || program > 127) throw std::invalid_argument("Programa fuera de rango.");
        std::lock_guard<std::mutex> lock(deviceMutex); ensureDevice();
        if (bank < 0 || bank >= static_cast<int>(romIndex->getNumSingleBanks())) throw std::invalid_argument("Banco fuera de rango.");
        virusLib::ROMFile::TPreset preset{};
        if (!romIndex->getSingle(bank, program, preset)) throw std::runtime_error("No se pudo leer ese patch de la ROM.");
        const auto dump = virusLib::Microcontroller::createSingleDump(*romIndex, virusLib::BankNumber::EditBuffer,
                                                                     virusLib::SINGLE, preset);
        std::lock_guard<std::mutex> midiLock(midiMutex);
        // A newer selection replaces pending edits and dumps: they belong to the previous sound.
        sysexQueue.clear(); sysexBytes = 0;
        Bytes bytes(dump.begin(), dump.end());
        if (!enqueueSysexLocked(bytes)) throw std::runtime_error("Cola MIDI llena.");
        --midiMessages; --midiSysex;     // internal, not counted as received MIDI
        currentSingle = preset; currentKnown = true; ++currentRevision;
        currentBank = bank; currentProgram = program; externalChange = false;
        const auto name = cleanName(virusLib::ROMFile::getSingleName(preset));
        return env->NewStringUTF(name.c_str());
    });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_setParameter(JNIEnv* env, jclass, jint page, jint index, jint value) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        const int offset = pageOffset(page);
        if (offset < 0 || index < 0 || index > 127 || value < 0 || value > 127)
            throw std::invalid_argument("Parametro fuera de rango.");
        if (page == 0x70 && index == 0) throw std::invalid_argument("La version del patch no se edita.");
        std::lock_guard<std::mutex> lock(midiMutex);
        if (!enqueueParamLocked(static_cast<std::uint8_t>(page), static_cast<std::uint8_t>(index), static_cast<std::uint8_t>(value)))
            return static_cast<jboolean>(JNI_FALSE);
        currentSingle[static_cast<std::size_t>(offset + index)] = static_cast<std::uint8_t>(value); ++currentRevision;
        return static_cast<jboolean>(JNI_TRUE);
    });
}
// Returns [revision, known, bank, program, values for each (page, index) pair...]; -1 when unknown.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_values(JNIEnv* env, jclass, jintArray pages, jintArray indices) {
    return guarded<jintArray>(env, nullptr, [&] {
        if (!pages || !indices || env->GetArrayLength(pages) != env->GetArrayLength(indices))
            throw std::invalid_argument("Listas de parametros invalidas.");
        const auto count = env->GetArrayLength(pages);
        std::vector<jint> p(static_cast<std::size_t>(count)), i(static_cast<std::size_t>(count));
        env->GetIntArrayRegion(pages, 0, count, p.data()); env->GetIntArrayRegion(indices, 0, count, i.data());
        std::vector<jint> result(static_cast<std::size_t>(count) + 4, -1);
        {
            std::lock_guard<std::mutex> lock(midiMutex);
            result[0] = static_cast<jint>(currentRevision & 0x7fffffffu);
            result[1] = (currentKnown ? 1 : 0) | (externalChange ? 2 : 0);
            result[2] = currentBank; result[3] = currentProgram;
            for (jsize k = 0; k < count; ++k) {
                const int offset = pageOffset(p[static_cast<std::size_t>(k)]);
                const int index = i[static_cast<std::size_t>(k)];
                if (currentKnown && offset >= 0 && index >= 0 && index < 128)
                    result[static_cast<std::size_t>(k) + 4] = currentSingle[static_cast<std::size_t>(offset + index)];
            }
        }
        auto array = env->NewIntArray(static_cast<jsize>(result.size()));
        if (array) env->SetIntArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
        return array;
    });
}
// Current edit buffer name (from the copy), "" when unknown.
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_currentName(JNIEnv* env, jclass) {
    return guarded<jstring>(env, nullptr, [&] {
        std::lock_guard<std::mutex> lock(midiMutex);
        return env->NewStringUTF(currentKnown ? cleanName(virusLib::ROMFile::getSingleName(currentSingle)).c_str() : "");
    });
}
// Re-read the firmware edit buffer, e.g. after a Program Change from the keyboard.
// Control thread only: briefly takes deviceMutex, like a render block.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_riostirus_RuntimeBridge_syncFromEngine(JNIEnv* env, jclass, jboolean external) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        std::lock_guard<std::mutex> lock(deviceMutex);
        {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            // Wait until queued edits and the Program Change itself reached the firmware.
            if (!sysexQueue.empty() || !shortQueue.empty()) return static_cast<jboolean>(JNI_FALSE);
        }
        const bool ok = syncFromEngineLocked();
        if (ok && external) {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            currentBank = currentProgram = -1; externalChange = false;
        }
        return static_cast<jboolean>(ok);
    });
}
