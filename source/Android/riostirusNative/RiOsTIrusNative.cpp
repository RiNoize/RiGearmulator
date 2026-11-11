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
    deviceModel = virusLib::DeviceModel::Invalid;
    preparedFrames = 0;
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
    } else if (command == 0xb0) ++midiCc;
    else if (command == 0xc0) ++midiPc;
    if (shortQueue.size() >= maxShortEvents) { ++midiOverflow; return false; }
    shortQueue.emplace_back(synthLib::MidiEventSource::Host, a, b, c, 0);
    return true;
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
        {
            const virusLib::ROMFile check(rom, filename, deviceModelRequested);
            if (!check.isValid()) throw std::runtime_error("El archivo no es un firmware de Virus TI reconocido.");
        }
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
        clearMeters();

        std::ostringstream out;
        out << "Virus " << virusLib::getModelName(deviceModel)
            << " | " << static_cast<int>(std::lround(device->getSamplerate())) << " Hz"
            << " | DSP " << (dspB.load() ? 2 : 1)
            << " | reloj DSP " << device->getDspClockHz() / 1000000 << " MHz"
            << " | salidas " << device->getChannelCountOut();
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
