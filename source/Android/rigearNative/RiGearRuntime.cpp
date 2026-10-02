// Single translation unit: retain the tested 0.6 ROM/MIDI implementation.
// CMake compiles THIS file instead of also compiling RiGearNative.cpp.
#include "RiGearNative.cpp"

#include <atomic>
#include <chrono>
#include <cstring>
#include <stdexcept>

namespace
{
std::atomic<float> rgGain{1.0f};
float rgAppliedGain = 1.0f; // Audio thread only; prepare is called while stopped.
std::atomic<uint64_t> rgClips{0}, rgBad{0}, rgPeakBits{0};
std::atomic<uint64_t> rgRevision{1};
std::array<int, 128> rgPageB{};
std::atomic<bool> rgRequestPatch{true};

synthLib::SMidiEvent rgSysex(std::initializer_list<uint8_t> bytes)
{
    synthLib::SMidiEvent event(synthLib::MidiEventSource::Editor);
    event.sysex.assign(bytes.begin(), bytes.end());
    return event;
}

// Queue safety commands without calling the device from the UI/MIDI thread.
void rgPanicLocked(bool discardPending)
{
    if (discardPending) g_pendingMidi.clear();
    std::deque<synthLib::SMidiEvent> commands;
    for (int channel = 0; channel < 16; ++channel)
    {
        for (const int cc : {64, 66, 120, 123})
            commands.emplace_back(synthLib::MidiEventSource::Host,
                static_cast<uint8_t>(0xb0 | channel), static_cast<uint8_t>(cc), 0, 0);
    }
    for (int note = 0; note < 128; ++note)
        commands.emplace_back(synthLib::MidiEventSource::Host, 0x80,
            static_cast<uint8_t>(note), 0, 0);
    // Prepend: patch/parameter edits made while stopped must survive Start.
    g_pendingMidi.insert(g_pendingMidi.begin(), commands.begin(), commands.end());
    g_externalActiveNotes.fill(false);
    g_externalActiveNoteCount = 0;
}

void rgThrow(JNIEnv* env, const char* text)
{
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) { env->ThrowNew(cls, text); env->DeleteLocalRef(cls); }
}

void rgCacheRomPageB()
{
    // Caller holds g_deviceMutex. Cache lock is always acquired second.
    virusLib::ROMFile::TPreset preset{};
    const bool ok = readBrowserPreset(g_selectedBank, g_selectedProgram, preset);
    std::lock_guard<std::mutex> lock(g_midiMutex);
    for (size_t i = 0; i < 128; ++i) rgPageB[i] = ok ? preset[128 + i] : -1;
    rgRevision.fetch_add(1);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rinoize_rigear_RuntimeBridge_prepare(JNIEnv* env, jclass, jint frames,
                                            jint clockPercent, jint extraBlocks)
{
    try
    {
        if (frames != 256 && frames != 512 && frames != 1024)
            throw std::invalid_argument("Buffer must be 256, 512 or 1024");
        if (clockPercent != 50 && clockPercent != 75 && clockPercent != 100 &&
            clockPercent != 125 && clockPercent != 150 && clockPercent != 200)
            throw std::invalid_argument("Unsupported DSP clock setting");
        if (extraBlocks != 0 && extraBlocks != 1 && extraBlocks != 2 &&
            extraBlocks != 4 && extraBlocks != 8)
            throw std::invalid_argument("Unsupported extra latency");
        std::lock_guard<std::mutex> lock(g_deviceMutex);
        if (!g_device) throw std::runtime_error("Load a Virus ROM first");
        if (!g_device->setDspClockPercent(static_cast<uint32_t>(clockPercent)))
            throw std::runtime_error("Device rejected DSP clock");
        g_device->setExtraLatencySamples(static_cast<uint32_t>(frames * extraBlocks));
        ensureAudioScratch(static_cast<size_t>(frames));
        rgAppliedGain = rgGain.load();
        rgClips.store(0); rgBad.store(0); rgPeakBits.store(0);
        rgCacheRomPageB();
        {
            std::lock_guard<std::mutex> midiLock(g_midiMutex);
            resetMidiParserState();
            rgPanicLocked(false);
        }
        rgRequestPatch.store(true);
        std::ostringstream out;
#ifdef NDEBUG
        out << "NATIVE: RELEASE";
#else
        out << "NATIVE: DEBUG (not a performance build)";
#endif
        out << " | DSP " << g_device->getDspClockPercent() << "% / "
            << formatHz(g_device->getDspClockHz()) << " | Extra DSP "
            << g_device->getExtraLatencySamples() << " frames";
        return env->NewStringUTF(out.str().c_str());
    }
    catch (const std::exception& error) { rgThrow(env, error.what()); }
    catch (...) { rgThrow(env, "Native audio preparation failed"); }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rinoize_rigear_RuntimeBridge_render(JNIEnv* env, jclass, jfloatArray buffer,
                                           jint frames)
{
    try
    {
        if (!buffer || (frames != 256 && frames != 512 && frames != 1024) ||
            env->GetArrayLength(buffer) < frames * 2)
            throw std::invalid_argument("Invalid stereo render buffer");
        std::lock_guard<std::mutex> lock(g_deviceMutex);
        if (!g_device) throw std::runtime_error("Device is not loaded");
        if (g_interleaved.size() != static_cast<size_t>(frames * 2))
            throw std::runtime_error("Call prepare before changing buffer size");
        synthLib::TAudioInputs inputs{};
        synthLib::TAudioOutputs outputs{};
        for (size_t i = 0; i < g_inputBuffers.size(); ++i)
            inputs[i] = g_inputBuffers[i].data();
        for (size_t i = 0; i < g_outputBuffers.size(); ++i)
        {
            std::fill(g_outputBuffers[i].begin(), g_outputBuffers[i].end(), 0.0f);
            outputs[i] = g_outputBuffers[i].data();
        }
        thread_local std::vector<synthLib::SMidiEvent> midiIn, midiOut;
        if (midiIn.capacity() < 1024) midiIn.reserve(1024);
        if (midiOut.capacity() < 1024) midiOut.reserve(1024);
        midiIn.clear(); midiOut.clear();
        bool request = rgRequestPatch.exchange(false);
        {
            std::lock_guard<std::mutex> midiLock(g_midiMutex);
            while (!g_pendingMidi.empty() && midiIn.size() < 512)
            {
                auto& event = g_pendingMidi.front();
                if ((event.a & 0xff) == 0xc0) request = true;
                midiIn.emplace_back(std::move(event));
                g_pendingMidi.pop_front();
            }
        }
        if (request)
            midiIn.push_back(rgSysex({0xf0, 0x00, 0x20, 0x33, 0x01, 0x10,
                                     0x30, 0x00, 0x40, 0xf7}));
        g_device->process(inputs, outputs, static_cast<size_t>(frames), midiIn, midiOut);
        for (const auto& event : midiOut)
        {
            const auto& data = event.sysex;
            if (data.size() >= 267 && data[0] == 0xf0 && data[1] == 0 &&
                data[2] == 0x20 && data[3] == 0x33 && data[4] == 1 &&
                data[6] == 0x10 && data[7] == 0 && data[8] == 0x40)
            {
                std::lock_guard<std::mutex> midiLock(g_midiMutex);
                for (size_t i = 0; i < 128; ++i)
                {
                    g_currentPageA[i] = data[9 + i];
                    rgPageB[i] = data[137 + i];
                }
                rgRevision.fetch_add(1);
            }
        }
        const float target = rgGain.load(std::memory_order_relaxed);
        const float gainStep = (target - rgAppliedGain) / static_cast<float>(frames);
        uint64_t clips = 0, bad = 0;
        float peak = 0.0f;
        for (int i = 0; i < frames; ++i)
        {
            rgAppliedGain += gainStep;
            for (int channel = 0; channel < 2; ++channel)
            {
                float value = g_outputBuffers[channel][static_cast<size_t>(i)];
                // Integer inspection also remains valid if an upstream build uses fast-math.
                uint32_t bits;
                std::memcpy(&bits, &value, sizeof(bits));
                if ((bits & 0x7f800000u) == 0x7f800000u) { value = 0; ++bad; }
                peak = std::max(peak, std::fabs(value));
                if (std::fabs(value) >= 1.0f) ++clips;
                g_interleaved[static_cast<size_t>(i) * 2 + channel] =
                    std::clamp(value * rgAppliedGain, -1.0f, 1.0f);
            }
        }
        rgAppliedGain = target;
        rgClips.fetch_add(clips, std::memory_order_relaxed);
        rgBad.fetch_add(bad, std::memory_order_relaxed);
        uint32_t peakBits; std::memcpy(&peakBits, &peak, sizeof(peakBits));
        rgPeakBits.store(peakBits, std::memory_order_relaxed);
        env->SetFloatArrayRegion(buffer, 0, frames * 2, g_interleaved.data());
        return env->ExceptionCheck() ? 0 : frames;
    }
    catch (const std::exception& error) { rgThrow(env, error.what()); }
    catch (const std::string& error) { rgThrow(env, error.c_str()); }
    catch (...) { rgThrow(env, "Native render failed"); }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_RuntimeBridge_panic(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_midiMutex);
    rgPanicLocked(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_RuntimeBridge_setGainDb(JNIEnv*, jclass, jint db)
{
    rgGain.store(std::pow(10.0f, std::clamp(static_cast<float>(db), -24.0f, 0.0f) / 20.0f));
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_RuntimeBridge_setParameter(JNIEnv*, jclass, jint page,
                                                jint index, jint value)
{
    if ((page != 0x70 && page != 0x71) || index < 0 || index > 127 || value < 0 || value > 127)
        return;
    std::lock_guard<std::mutex> lock(g_midiMutex);
    if (page == 0x70) g_currentPageA[static_cast<size_t>(index)] = static_cast<uint8_t>(value);
    else rgPageB[static_cast<size_t>(index)] = value;
    // Use genuine Virus parameter SysEx, including high-page/FX parameters.
    auto event = rgSysex({0xf0, 0, 0x20, 0x33, 1, 0x10,
        static_cast<uint8_t>(page), 0x40, static_cast<uint8_t>(index),
        static_cast<uint8_t>(value), 0xf7});
    if (!g_pendingMidi.empty())
    {
        auto& last = g_pendingMidi.back().sysex;
        if (last.size() == 11 && last[6] == page && last[7] == 0x40 && last[8] == index)
        {
            last[9] = static_cast<uint8_t>(value);
            rgRevision.fetch_add(1);
            return;
        }
    }
    g_pendingMidi.emplace_back(std::move(event));
    rgRevision.fetch_add(1);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_rinoize_rigear_RuntimeBridge_panel(JNIEnv* env, jclass)
{
    std::array<jint, 257> values{};
    {
        std::lock_guard<std::mutex> lock(g_midiMutex);
        values[0] = static_cast<jint>(rgRevision.load() & 0x7fffffff);
        for (size_t i = 0; i < 128; ++i) { values[1 + i] = g_currentPageA[i]; values[129 + i] = rgPageB[i]; }
    }
    auto result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rinoize_rigear_RuntimeBridge_requestPanel(JNIEnv*, jclass)
{
    rgRequestPatch.store(true);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_rinoize_rigear_RuntimeBridge_signalStats(JNIEnv* env, jclass, jboolean reset)
{
    const jlong values[] = {static_cast<jlong>(rgClips.load()), static_cast<jlong>(rgBad.load()),
                           static_cast<jlong>(rgPeakBits.load())};
    if (reset) { rgClips.store(0); rgBad.store(0); }
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}
