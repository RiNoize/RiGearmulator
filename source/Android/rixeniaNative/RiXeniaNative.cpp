#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
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

#include "xtLib/xtDevice.h"
#include "synthLib/device.h"
#include "XeniaProtocol.h"
#include "XeniaResampler.h"
#include "XeniaDispatch.h"

namespace {
using Event = synthLib::SMidiEvent;
using Bytes = rixenia::Bytes;
constexpr std::size_t maxShortEvents = 4096, maxSysexEvents = 4096;
constexpr std::size_t maxQueuedSysexBytes = 1024 * 1024;
static_assert(maxShortEvents >= 16 * (128 + 4), "Panic must fit every channel and note");

// Lock order is device -> MIDI. The UI/USB callbacks only take MIDI. Loading,
// preparing, rendering and releasing are serialized even if Java is misused.
std::mutex deviceMutex, midiMutex;
std::unique_ptr<synthLib::Device> device;
std::deque<Event> shortQueue, sysexQueue;
std::size_t sysexBytes = 0;
rixenia::MidiDecoder decoder;
std::array<Bytes, 256> patches;
std::array<int, 256> parameters = [] { std::array<int, 256> p{}; p.fill(-1); return p; }();
std::array<unsigned, 16> midiBanks{};
std::array<std::array<bool, 128>, 16> notes{};
// Keep note-on history until Panic, including notes whose Note Off was already
// received under a sustain pedal. Those still need a release on their channel.
std::array<std::array<bool, 128>, 16> seenNotes{};
int selectedBank = 0, selectedProgram = 0;
std::uint32_t revision = 1;
bool requestPanel = true;
bool singleModeScheduled = false;
rixenia::MidiDispatch dispatch;
rixenia::PatchChange patchChange;
int queryRetryFrames = 0;
std::uint64_t queryGeneration = 0;
std::int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
std::uint64_t midiMessages = 0, midiNotes = 0, midiCc = 0, midiPc = 0, midiBend = 0;
std::uint64_t midiSysex = 0, midiRejected = 0, midiOverflow = 0;
unsigned heldNotes = 0;

std::array<std::vector<float>, 2> inputs;
std::array<std::vector<float>, 4> outputs;
std::vector<float> interleaved, converted;
std::vector<Event> midiIn, midiOut;
int preparedFrames = 0, sysexCooldown = 0;
bool convert48 = false;
rixenia::XeniaResampler src;
std::atomic<float> targetGain{1.0f};
float appliedGain = 1.0f;
std::atomic<std::uint64_t> clips{0}, badSamples{0}, peakBits{0};
std::atomic<std::uint64_t> srcNanos{0}, srcMaxNanos{0}, srcInputs{0}, srcOutputs{0};

void throwJava(JNIEnv* env, const char* text) {
    if (env->ExceptionCheck()) return;
    const auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) { env->ThrowNew(cls, text); env->DeleteLocalRef(cls); }
}
template<class R, class F> R guarded(JNIEnv* env, R fallback, F operation) {
    try { return operation(); }
    catch (const std::exception& e) { throwJava(env, e.what()); }
    catch (const std::string& e) { throwJava(env, e.c_str()); }
    catch (...) { throwJava(env, "Fallo del motor nativo Xenia."); }
    return fallback;
}
template<class F> void guardedVoid(JNIEnv* env, F operation) {
    guarded<int>(env, 0, [&] { operation(); return 1; });
}
Bytes bytesFromJava(JNIEnv* env, jbyteArray data, std::size_t limit) {
    if (!data) throw std::invalid_argument("Falta el archivo.");
    const auto length = env->GetArrayLength(data);
    if (length <= 0 || static_cast<std::size_t>(length) > limit)
        throw std::invalid_argument("Tamano de archivo invalido.");
    Bytes result(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("No se pudo leer el archivo.");
    return result;
}
std::string stringFromJava(JNIEnv* env, jstring value) {
    if (!value) return "Microwave-II-XT.bin";
    const auto* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("No se pudo leer el nombre de la ROM.");
    std::string result(chars); env->ReleaseStringUTFChars(value, chars);
    return result;
}
Event sysexEvent(const Bytes& data) {
    Event event(synthLib::MidiEventSource::Editor);
    event.sysex.assign(data.begin(), data.end());
    return event;
}
bool enqueueSysexLocked(const Bytes& data) {
    if (sysexQueue.size() >= maxSysexEvents || data.size() > maxQueuedSysexBytes - sysexBytes) {
        ++midiOverflow; return false;
    }
    sysexQueue.emplace_back(sysexEvent(data)); sysexBytes += data.size(); return true;
}
bool enqueueShortLocked(std::uint8_t a, std::uint8_t b, std::uint8_t c) {
    if (shortQueue.size() >= maxShortEvents) { ++midiOverflow; return false; }
    shortQueue.emplace_back(synthLib::MidiEventSource::Host, a, b, c, 0); return true;
}
void ensureSingleModeLocked() {
    if (!singleModeScheduled) {
        if (!enqueueSysexLocked({0xf0, 0x3e, 0x0e, 0x7f, 0x17, 0, 0xf7}))
            throw std::runtime_error("Cola de modo Single llena.");
        singleModeScheduled = true;
    }
}
void removePendingEditLocked() {
    for (auto it = sysexQueue.begin(); it != sysexQueue.end();) {
        const auto& s = it->sysex;
        if ((s.size() == 265 && s[4] == 0x10 && s[5] == 0x20) ||
            (s.size() == 10 && s[4] == 0x20)) {
            sysexBytes -= s.size(); it = sysexQueue.erase(it);
        } else ++it;
    }
}
bool selectPatchLocked(int bank, int program) {
    const auto& patch = patches[static_cast<std::size_t>(bank * 128 + program)];
    if (patch.empty()) return false;
    if (sysexQueue.size() + 2 > maxSysexEvents || sysexBytes + 272 > maxQueuedSysexBytes)
        throw std::runtime_error("La cola SysEx esta llena; espere y vuelva a elegir el sonido.");
    removePendingEditLocked();
    ensureSingleModeLocked();
    enqueueSysexLocked(rixenia::singleEdit(patch));
    selectedBank = bank; selectedProgram = program;
    midiBanks.fill(static_cast<unsigned>(bank));
    patchChange.request(patch, nowMs());
    queryRetryFrames = 0; requestPanel = true; ++revision;
    return true;
}
void panicLocked(bool discard) {
    if (discard) { shortQueue.clear(); decoder.reset(); }
    std::deque<Event> releases;
    std::array<bool, 16> priorityChannels{};
    for (int channel = 0; channel < 16; ++channel) {
        priorityChannels[channel] = std::any_of(seenNotes[channel].begin(), seenNotes[channel].end(), [](bool v) { return v; });
        if (!priorityChannels[channel]) continue;
        for (const int cc : {64, 66, 120, 123})
            releases.emplace_back(synthLib::MidiEventSource::Host,
                static_cast<std::uint8_t>(0xb0 | channel), static_cast<std::uint8_t>(cc), 0, 0);
        for (int note = 0; note < 128; ++note) if (seenNotes[channel][note])
            releases.emplace_back(synthLib::MidiEventSource::Host,
                static_cast<std::uint8_t>(0x80 | channel), static_cast<std::uint8_t>(note), 0, 0);
    }
    for (int channel = 0; channel < 16; ++channel) if (!priorityChannels[channel])
        for (const int cc : {64, 66, 120, 123})
            releases.emplace_back(synthLib::MidiEventSource::Host,
                static_cast<std::uint8_t>(0xb0 | channel), static_cast<std::uint8_t>(cc), 0, 0);
    for (int note = 0; note < 128; ++note) if (!seenNotes[0][note])
        releases.emplace_back(synthLib::MidiEventSource::Host, 0x80, static_cast<std::uint8_t>(note), 0, 0);
    // Reserve space even after an input flood; at most 2112 release events.
    while (!shortQueue.empty() && shortQueue.size() + releases.size() > maxShortEvents) shortQueue.pop_back();
    shortQueue.insert(shortQueue.begin(), releases.begin(), releases.end());
    for (auto& channel : notes) channel.fill(false);
    for (auto& channel : seenNotes) channel.fill(false);
    heldNotes = 0;
}
bool receiveShortLocked(std::uint8_t a, std::uint8_t b, std::uint8_t c) {
    const unsigned channel = a & 15;
    const auto command = a & 0xf0;
    ++midiMessages;
    if (command == 0x90 || command == 0x80) {
        ++midiNotes;
        const bool on = command == 0x90 && c != 0;
        if (on) seenNotes[channel][b] = true;
        if (on && !notes[channel][b]) ++heldNotes;
        else if (!on && notes[channel][b] && heldNotes) --heldNotes;
        notes[channel][b] = on;
    } else if (command == 0xb0) {
        ++midiCc;
        if (b == 32) midiBanks[channel] = c;
    } else if (command == 0xe0) ++midiBend;
    else if (command == 0xc0) {
        ++midiPc;
        if (midiBanks[channel] < 2 && selectPatchLocked(static_cast<int>(midiBanks[channel]), b)) return true;
        selectedBank = midiBanks[channel] < 2 ? static_cast<int>(midiBanks[channel]) : -1;
        selectedProgram = b; parameters.fill(-1); ++revision;
        removePendingEditLocked(); patchChange.reset();
        requestPanel = true; queryRetryFrames = 4000;
    }
    return enqueueShortLocked(a, b, c);
}
void ensureDevice() {
    if (!device) throw std::runtime_error("Primero cargue la ROM Microwave II/XT.");
}
void finishFirmwareSetup() {
    // xt::Device queues its startup Global parameters at the end of its
    // constructor. Drain those before accepting the first user patch. All four
    // outputs must exist; synthLib::Device::dummyProcess only supplies two.
    std::array<std::array<float, 512>, 2> silent{};
    std::array<std::array<float, 512>, 4> discarded{};
    synthLib::TAudioInputs in{}; synthLib::TAudioOutputs out{};
    for (std::size_t i = 0; i < silent.size(); ++i) in[i] = silent[i].data();
    for (std::size_t i = 0; i < discarded.size(); ++i) out[i] = discarded[i].data();
    const std::vector<Event> empty;
    std::vector<Event> responses;
    for (int i = 0; i < 40; ++i) device->process(in, out, 512, empty, responses);
}
void clearMeters() {
    clips.store(0); badSamples.store(0); peakBits.store(0);
    srcNanos.store(0); srcMaxNanos.store(0); srcInputs.store(0); srcOutputs.store(0);
}
void validSlot(int bank, int program) {
    if (bank < 0 || bank > 1 || program < 0 || program > 127)
        throw std::invalid_argument("Banco o programa fuera de rango (A/B, 1-128).");
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeGetCoreInfo(JNIEnv* env, jclass) {
    return guarded<jstring>(env, nullptr, [&] {
        return env->NewStringUTF("RiXenia | Gearmulator xtLib | Waldorf Microwave II/XT | ARM64 | 1 DSP | 40000 Hz");
    });
}
extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeSelfTest(JNIEnv* env, jclass) {
    return guarded<jint>(env, 0, [] { return static_cast<jint>(sizeof(void*) == 8 && rixenia::XeniaResampler::maxOutputFrames(512) == 615); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeLoadRom(JNIEnv* env, jclass, jbyteArray data, jstring name) {
    return guarded<jstring>(env, nullptr, [&] {
        auto rom = bytesFromJava(env, data, rixenia::romSize);
        const int version = rixenia::validateRom(rom);
        auto filename = stringFromJava(env, name);
        std::lock_guard<std::mutex> lock(deviceMutex);
        // Java performs this on its serial control worker, with AudioTrack stopped.
        device.reset(); preparedFrames = 0; convert48 = false; sysexCooldown = 0;
        {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            shortQueue.clear(); sysexQueue.clear(); sysexBytes = 0; decoder.reset();
            for (auto& channel : notes) channel.fill(false);
            for (auto& channel : seenNotes) channel.fill(false);
            heldNotes = 0; parameters.fill(-1); requestPanel = true; ++revision;
            singleModeScheduled = false; patchChange.reset(); dispatch.reset();
            queryRetryFrames = 0; queryGeneration = 0;
        }
        synthLib::DeviceCreateParams params;
        params.romData = std::move(rom); params.romName = std::move(filename);
        params.preferredSamplerate = 40000.0f; params.hostSamplerate = 48000.0f;
        params.customData = 0; // Base 10-voice hardware; no three-DSP expansion.
        device = std::make_unique<xt::Device>(params);
        if (!device->isValid()) { device.reset(); throw std::runtime_error("Xenia no pudo iniciar la ROM."); }
        device->setDspClockPercent(100);
        finishFirmwareSetup();
        {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            ensureSingleModeLocked();
            if (selectedBank >= 0 && selectedBank < 2) selectPatchLocked(selectedBank, selectedProgram);
        }
        clearMeters();
        const std::string result = "Xenia listo | ROM " + std::to_string(version / 100) + "." +
            (version % 100 < 10 ? "0" : "") + std::to_string(version % 100) +
            " | 40000 Hz | 1 DSP / 10 voces";
        return env->NewStringUTF(result.c_str());
    });
}
extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeGetDeviceSampleRate(JNIEnv* env, jclass) {
    return guarded<jint>(env, 0, [] {
        std::lock_guard<std::mutex> lock(deviceMutex);
        return device ? static_cast<jint>(std::lround(device->getSamplerate())) : 0;
    });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeNoteOn(JNIEnv* env, jclass, jint note, jint velocity) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        if (note < 0 || note > 127 || velocity < 0 || velocity > 127) return static_cast<jboolean>(JNI_FALSE);
        std::lock_guard<std::mutex> lock(midiMutex);
        return static_cast<jboolean>(receiveShortLocked(0x90, note, velocity));
    });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeNoteOff(JNIEnv* env, jclass, jint note) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        if (note < 0 || note > 127) return static_cast<jboolean>(JNI_FALSE);
        std::lock_guard<std::mutex> lock(midiMutex);
        return static_cast<jboolean>(receiveShortLocked(0x80, note, 0));
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativePanic(JNIEnv* env, jclass) {
    guardedVoid(env, [] { std::lock_guard<std::mutex> lock(midiMutex); panicLocked(true); });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeSendMidiBytes(JNIEnv* env, jclass, jbyteArray data, jint offset, jint count) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        if (!data || offset < 0 || count < 0 || count > 65536 || offset > env->GetArrayLength(data) - count)
            throw std::invalid_argument("Paquete MIDI invalido.");
        Bytes bytes(static_cast<std::size_t>(count));
        if (count) env->GetByteArrayRegion(data, offset, count, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return static_cast<jboolean>(JNI_FALSE);
        std::lock_guard<std::mutex> lock(midiMutex);
        const bool ok = decoder.feed(bytes.data(), bytes.size(), [](std::uint8_t a, std::uint8_t b, std::uint8_t c, const Bytes* sysex) {
            if (!sysex) return receiveShortLocked(a, b, c);
            ++midiMessages; ++midiSysex;
            if (!rixenia::validLiveSysex(*sysex)) { ++midiRejected; return false; }
            auto normalized = *sysex;
            normalized[3] = 0x7f;
            if (rixenia::validDump(normalized)) rixenia::normalizeDump(normalized);
            if (normalized.size() == 7 && (normalized[4] == 0x17 || normalized[4] == 0x27))
                singleModeScheduled = normalized[5] == 0;
            return enqueueSysexLocked(normalized);
        });
        if (!ok) ++midiRejected;
        return static_cast<jboolean>(ok);
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeResetMidiStats(JNIEnv* env, jclass) {
    guardedVoid(env, [] {
        std::lock_guard<std::mutex> lock(midiMutex);
        midiMessages = midiNotes = midiCc = midiPc = midiBend = midiSysex = midiRejected = midiOverflow = 0;
    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeGetMidiStats(JNIEnv* env, jclass) {
    return guarded<jstring>(env, nullptr, [&] {
        std::ostringstream text;
        {
            std::lock_guard<std::mutex> lock(midiMutex);
            text << "MIDI " << midiMessages << " | Notas " << heldNotes << " | CC " << midiCc
                 << " | PC " << midiPc << " | Bend " << midiBend << " | SysEx " << midiSysex
                 << " | Cola " << shortQueue.size() + sysexQueue.size()
                 << " | Rechazados " << midiRejected << " | Overflow " << midiOverflow;
        }
        return env->NewStringUTF(text.str().c_str());
    });
}
extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeGetPatchBankCount(JNIEnv*, jclass) { return 2; }
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeGetPatchName(JNIEnv* env, jclass, jint bank, jint program) {
    return guarded<jstring>(env, nullptr, [&] {
        validSlot(bank, program);
        std::lock_guard<std::mutex> lock(midiMutex);
        const auto name = rixenia::patchName(patches[static_cast<std::size_t>(bank * 128 + program)]);
        return env->NewStringUTF(name.c_str());
    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeSelectPatch(JNIEnv* env, jclass, jint bank, jint program) {
    return guarded<jstring>(env, nullptr, [&] {
        validSlot(bank, program);
        std::lock_guard<std::mutex> lock(midiMutex);
        if (!selectPatchLocked(bank, program)) {
            selectedBank = bank; selectedProgram = program;
            midiBanks.fill(static_cast<unsigned>(bank));
            removePendingEditLocked(); patchChange.reset();
            parameters.fill(-1); ++revision; requestPanel = true;
            return env->NewStringUTF("");
        }
        const auto name = rixenia::patchName(patches[static_cast<std::size_t>(bank * 128 + program)]);
        return env->NewStringUTF(name.c_str());
    });
}
extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeImportSysex(JNIEnv* env, jclass, jbyteArray data) {
    return guarded<jint>(env, 0, [&] {
        const auto raw = bytesFromJava(env, data, rixenia::maxImportBytes);
        auto messages = rixenia::splitDumps(raw); // Validate ALL before changing anything.
        std::lock_guard<std::mutex> lock(midiMutex);
        std::size_t queuedCount = 0, queuedBytes = 0;
        for (const auto& message : messages) if (message[4] != 0x10) { ++queuedCount; queuedBytes += message.size(); }
        if (sysexQueue.size() + queuedCount + 2 > maxSysexEvents ||
            queuedBytes + 272 > maxQueuedSysexBytes - sysexBytes)
            throw std::runtime_error("Demasiados datos SysEx pendientes. Inicie audio y espere antes de importar otro banco.");
        int firstBank = -1, firstProgram = -1;
        for (const auto& message : messages) {
            if (message[4] == 0x10) {
                const int bank = message[5] < 2 ? message[5] : std::max(0, selectedBank);
                const int program = message[5] < 2 ? message[6] : selectedProgram;
                patches[static_cast<std::size_t>(bank * 128 + program)] = message;
                if (firstBank < 0) { firstBank = bank; firstProgram = program; }
            } else enqueueSysexLocked(message);
        }
        midiSysex += messages.size(); ++revision;
        // Sounds live in our explicit A/B library; selection injects the actual
        // selected bytes into the firmware edit buffer. No assumed ROM presets.
        if (firstBank >= 0) selectPatchLocked(firstBank, firstProgram);
        requestPanel = true;
        return static_cast<jint>(messages.size());
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rixenia_NativeBridge_nativeRelease(JNIEnv* env, jclass) {
    guardedVoid(env, [] {
        std::lock_guard<std::mutex> lock(deviceMutex);
        device.reset(); preparedFrames = 0; convert48 = false; sysexCooldown = 0;
        interleaved.clear(); converted.clear();
        std::lock_guard<std::mutex> midiLock(midiMutex);
        shortQueue.clear(); sysexQueue.clear(); sysexBytes = 0; decoder.reset();
        parameters.fill(-1); ++revision;
        patchChange.reset(); dispatch.reset(); singleModeScheduled = false; queryRetryFrames = 0;
        for (auto& channel : notes) channel.fill(false);
        for (auto& channel : seenNotes) channel.fill(false);
        heldNotes = 0;
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_prepare(JNIEnv* env, jclass, jint frames, jint clockPercent, jint extraFrames) {
    return guarded<jstring>(env, nullptr, [&] {
        if (frames != 256 && frames != 512 && frames != 1024)
            throw std::invalid_argument("Buffer permitido: 256, 512 o 1024 frames.");
        if (clockPercent != 50 && clockPercent != 75 && clockPercent != 100 &&
            clockPercent != 125 && clockPercent != 150 && clockPercent != 200)
            throw std::invalid_argument("Reloj DSP invalido.");
        if (extraFrames != 0 && extraFrames != 128 && extraFrames != 256 && extraFrames != 512 &&
            extraFrames != 1024 && extraFrames != 2048 && extraFrames != 4096 && extraFrames != 8192)
            throw std::invalid_argument("Latencia DSP invalida.");
        std::lock_guard<std::mutex> lock(deviceMutex); ensureDevice();
        if (!device->setDspClockPercent(static_cast<std::uint32_t>(clockPercent)))
            throw std::runtime_error("Xenia rechazo el reloj DSP.");
        device->setExtraLatencySamples(static_cast<std::uint32_t>(extraFrames));
        for (auto& in : inputs) in.assign(static_cast<std::size_t>(frames), 0.0f);
        for (auto& out : outputs) out.assign(static_cast<std::size_t>(frames), 0.0f);
        interleaved.assign(static_cast<std::size_t>(frames * 2), 0.0f);
        converted.assign(rixenia::XeniaResampler::maxOutputFrames(frames) * 2, 0.0f);
        midiIn.reserve(256); midiOut.reserve(256);
        src.reset(); preparedFrames = frames; convert48 = false;
        appliedGain = targetGain.load(); clearMeters();
        {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            decoder.reset();
            // Stop/Panic already queues its releases, even while audio is
            // stopped. Do not add another 192 MIDI messages on every start.
            if (std::any_of(seenNotes.begin(), seenNotes.end(), [](const auto& channel) {
                return std::any_of(channel.begin(), channel.end(), [](bool value) { return value; });
            })) panicLocked(false);
            requestPanel = true;
        }
#ifdef NDEBUG
        const std::string build = "RELEASE";
#else
        const std::string build = "DEBUG";
#endif
        const std::string text = "Xenia " + build + " | DSP " + std::to_string(clockPercent) +
            "% | 40000 Hz | buffer " + std::to_string(frames) + " | extra " + std::to_string(extraFrames);
        return env->NewStringUTF(text.c_str());
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_configureOutput(JNIEnv* env, jclass, jint outputRate) {
    guardedVoid(env, [&] {
        std::lock_guard<std::mutex> lock(deviceMutex); ensureDevice();
        if (!preparedFrames) throw std::runtime_error("Prepare el motor antes de configurar la salida.");
        if (outputRate != 40000 && outputRate != 48000)
            throw std::invalid_argument("Xenia admite salida nativa 40000 Hz o SRC a 48000 Hz.");
        if (std::abs(device->getSamplerate() - 40000.0f) > 0.01f)
            throw std::runtime_error("El motor cambio su frecuencia nativa.");
        convert48 = outputRate == 48000;
        if (convert48) src.prepare(); else src.reset();
    });
}
extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_render(JNIEnv* env, jclass, jfloatArray buffer, jint frames) {
    return guarded<jint>(env, 0, [&] {
        if (!buffer) throw std::invalid_argument("Falta el buffer de audio.");
        std::lock_guard<std::mutex> lock(deviceMutex); ensureDevice();
        if (frames != preparedFrames || !preparedFrames)
            throw std::invalid_argument("El buffer de audio cambio sin preparar el motor.");
        const auto maxFrames = convert48 ? rixenia::XeniaResampler::maxOutputFrames(frames) : static_cast<std::size_t>(frames);
        if (static_cast<std::size_t>(env->GetArrayLength(buffer)) < maxFrames * 2)
            throw std::invalid_argument("Buffer Java insuficiente para SRC 40000 a 48000 Hz.");
        synthLib::TAudioInputs in{}; synthLib::TAudioOutputs out{};
        for (std::size_t i = 0; i < inputs.size(); ++i) in[i] = inputs[i].data();
        for (std::size_t i = 0; i < outputs.size(); ++i) {
            std::fill(outputs[i].begin(), outputs[i].end(), 0.0f); out[i] = outputs[i].data();
        }
        midiIn.clear(); midiOut.clear(); sysexCooldown = std::max(0, sysexCooldown - frames);
        bool queryFirmware = false;
        auto* xtDevice = static_cast<xt::Device*>(device.get());
        const auto oldFirmwareRevision = xtDevice->getFirmwareSingleRevision();
        {
            std::lock_guard<std::mutex> midiLock(midiMutex);
            queryRetryFrames = std::max(0, queryRetryFrames - frames);
            if (patchChange.sent() && !queryRetryFrames) requestPanel = true;
            const auto shortBudget = static_cast<std::size_t>(std::max(1, frames / 40));
            const bool hasControl = !sysexQueue.empty() || (requestPanel && !queryRetryFrames);
            const auto next = dispatch.choose(!shortQueue.empty(), hasControl, sysexCooldown);
            if (next == rixenia::MidiDispatch::Next::Channel) {
                while (!shortQueue.empty() && midiIn.size() < shortBudget) {
                    const auto offset = static_cast<std::uint32_t>(midiIn.size() * 40);
                    midiIn.emplace_back(std::move(shortQueue.front())); shortQueue.pop_front();
                    midiIn.back().offset = offset;
                }
            } else if (next == rixenia::MidiDispatch::Next::Control && !sysexQueue.empty()) {
                const auto count = sysexQueue.front().sysex.size();
                const bool modeChange = count == 7 && sysexQueue.front().sysex[4] == 0x17;
                patchChange.dispatched(sysexQueue.front().sysex);
                midiIn.emplace_back(std::move(sysexQueue.front())); sysexQueue.pop_front(); sysexBytes -= count;
                // Keep the firmware's existing safe pacing. Fix starvation, not
                // the UART clock. Mode setup occurs once, not on every patch.
                sysexCooldown = modeChange ? 4000 : static_cast<int>(count * 8 + 256);
            } else if (next == rixenia::MidiDispatch::Next::Control) {
                queryFirmware = true; queryGeneration = patchChange.generation();
                requestPanel = false; queryRetryFrames = 16000; sysexCooldown = 256;
            }
        }
        if (queryFirmware) xtDevice->requestSingleFromFirmware();
        device->process(in, out, static_cast<std::size_t>(frames), midiIn, midiOut);
        if (xtDevice->getFirmwareSingleRevision() != oldFirmwareRevision) {
            const auto& actual = xtDevice->getFirmwareSingle();
            if (rixenia::validDump(actual)) {
                std::lock_guard<std::mutex> midiLock(midiMutex);
                const bool confirmed = patchChange.confirm(actual, queryGeneration, nowMs());
                if (confirmed || !patchChange.pending()) {
                    for (std::size_t i = 0; i < 256; ++i) parameters[i] = actual[7 + i];
                    ++revision;
                }
            }
        }
        const float goal = targetGain.load(std::memory_order_relaxed);
        const float step = (goal - appliedGain) / static_cast<float>(frames);
        std::uint64_t clipped = 0, bad = 0; float peak = 0.0f;
        for (int frame = 0; frame < frames; ++frame) {
            appliedGain += step;
            for (int channel = 0; channel < 2; ++channel) {
                float sample = outputs[channel][static_cast<std::size_t>(frame)];
                if (!std::isfinite(sample)) { sample = 0.0f; ++bad; }
                peak = std::max(peak, std::abs(sample));
                if (std::abs(sample) >= 1.0f) ++clipped;
                interleaved[static_cast<std::size_t>(frame * 2 + channel)] = std::clamp(sample * appliedGain, -1.0f, 1.0f);
            }
        }
        appliedGain = goal;
        clips.fetch_add(clipped, std::memory_order_relaxed); badSamples.fetch_add(bad, std::memory_order_relaxed);
        std::uint32_t bits = 0; std::memcpy(&bits, &peak, sizeof(bits)); peakBits.store(bits, std::memory_order_relaxed);
        const float* result = interleaved.data(); std::size_t count = static_cast<std::size_t>(frames);
        if (convert48) {
            const auto before = std::chrono::steady_clock::now();
            count = src.process(interleaved.data(), frames, converted.data(), converted.size() / 2);
            for (std::size_t i = 0; i < count * 2; ++i) converted[i] = std::clamp(converted[i], -1.0f, 1.0f);
            const auto ns = static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - before).count());
            srcNanos.store(ns); srcMaxNanos.store(std::max(srcMaxNanos.load(), ns));
            srcInputs.store(src.inputFrames()); srcOutputs.store(src.outputFrames()); result = converted.data();
        }
        env->SetFloatArrayRegion(buffer, 0, static_cast<jsize>(count * 2), result);
        return env->ExceptionCheck() ? 0 : static_cast<jint>(count);
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_panic(JNIEnv* env, jclass) {
    Java_com_rinoize_rixenia_NativeBridge_nativePanic(env, nullptr);
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_setGainDb(JNIEnv* env, jclass, jint db) {
    guardedVoid(env, [&] { targetGain.store(std::pow(10.0f, std::clamp(static_cast<float>(db), -24.0f, 0.0f) / 20.0f)); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_setParameter(JNIEnv* env, jclass, jint index, jint value) {
    guardedVoid(env, [&] {
        if (index < 0 || index > 255 || value < 0 || value > 127)
            throw std::invalid_argument("Parametro Xenia fuera de rango.");
        std::lock_guard<std::mutex> lock(midiMutex);
        if (patchChange.pending()) throw std::runtime_error("Espere la confirmacion del patch antes de editar.");
        Bytes message{0xf0, 0x3e, 0x0e, 0x7f, 0x20, 0,
            static_cast<std::uint8_t>(index >> 7), static_cast<std::uint8_t>(index & 127),
            static_cast<std::uint8_t>(value), 0xf7};
        // Coalesce a knob's pending updates while retaining ordering with dumps.
        if (!sysexQueue.empty()) {
            auto& last = sysexQueue.back().sysex;
            if (last.size() == 10 && last[4] == 0x20 && last[6] == message[6] && last[7] == message[7]) {
                last[8] = static_cast<std::uint8_t>(value); parameters[index] = value; ++revision; return;
            }
        }
        if (!enqueueSysexLocked(message)) throw std::runtime_error("Cola MIDI llena.");
        parameters[index] = value; ++revision;
    });
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_panel(JNIEnv* env, jclass) {
    return guarded<jintArray>(env, nullptr, [&] {
        std::array<jint, 264> values{};
        {
            std::lock_guard<std::mutex> lock(midiMutex);
            values[0] = static_cast<jint>(revision & 0x7fffffffu); values[1] = selectedBank; values[2] = selectedProgram;
            for (std::size_t i = 0; i < 256; ++i) values[3 + i] = parameters[i];
            values[259] = patchChange.phase(nowMs()); values[260] = patchChange.lastMs();
            values[261] = patchChange.elapsedMs(nowMs());
            values[262] = static_cast<jint>(shortQueue.size()); values[263] = static_cast<jint>(sysexQueue.size());
        }
        auto result = env->NewIntArray(static_cast<jsize>(values.size()));
        if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
        return result;
    });
}
extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_requestPanel(JNIEnv* env, jclass) {
    guardedVoid(env, [] { std::lock_guard<std::mutex> lock(midiMutex); requestPanel = true; });
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_signalStats(JNIEnv* env, jclass, jboolean reset) {
    return guarded<jlongArray>(env, nullptr, [&] {
        const jlong values[] = {static_cast<jlong>(clips.load()), static_cast<jlong>(badSamples.load()), static_cast<jlong>(peakBits.load())};
        if (reset) { clips.store(0); badSamples.store(0); srcMaxNanos.store(0); }
        auto result = env->NewLongArray(3); if (result) env->SetLongArrayRegion(result, 0, 3, values); return result;
    });
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_rinoize_rixenia_RuntimeBridge_conversionStats(JNIEnv* env, jclass) {
    return guarded<jlongArray>(env, nullptr, [&] {
        const jlong values[] = {static_cast<jlong>(srcNanos.load()), static_cast<jlong>(srcMaxNanos.load()),
            static_cast<jlong>(srcInputs.load()), static_cast<jlong>(srcOutputs.load())};
        auto result = env->NewLongArray(4); if (result) env->SetLongArrayRegion(result, 0, 4, values); return result;
    });
}
