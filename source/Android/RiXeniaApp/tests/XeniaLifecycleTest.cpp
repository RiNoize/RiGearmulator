// Exercise the actual Waldorf UC/audio wait implementation without a firmware ROM.
#include "wLib/wHardware.h"

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <future>
#include <iostream>
#include <stdexcept>
#include <thread>

using namespace std::chrono_literals;

namespace {
class TestHardware final : public wLib::Hardware {
public:
    TestHardware() : Hardware(40000.0) { m_esaiFrameIndex = m_lastEsaiFrameIndex = 8; }
    hwLib::SciMidi& getMidi() override { throw std::logic_error("Unexpected MIDI access"); }
    mc68k::Mc68k& getUc() override { throw std::logic_error("Terminated UC must not access its CPU"); }
    void audioBegin() { beginProcessAudio(); }
    void audioEnd() { endProcessAudio(); }
    void synchronize() { syncUcToDSP(); }
    std::unique_lock<std::mutex> gateAudio() { return std::unique_lock<std::mutex>(m_processAudioMutex); }
    std::unique_lock<std::mutex> gateSync() { return std::unique_lock<std::mutex>(m_esaiFrameAddedMutex); }
    bool halted() const { return m_haltDSP.load(); }

    // If the production termination path regresses, release every waiter before
    // destroying its fixture. A frame advance also rescues the old sync predicate.
    void rescue() {
        {
            std::scoped_lock lock(m_processAudioMutex, m_esaiFrameAddedMutex, m_haltDSPmutex);
            m_terminateUcThread = true;
            m_processAudio = true;
            ++m_esaiFrameIndex;
            m_haltDSP = false;
        }
        m_processAudioCv.notify_all();
        m_esaiFrameAddedCv.notify_all();
        m_haltDSPcv.notify_all();
    }
};

class Task {
public:
    template<class F> explicit Task(F work) : done(result.get_future()), worker([this, work] {
        try { work(); result.set_value(); }
        catch (...) { result.set_exception(std::current_exception()); }
    }) {}
    ~Task() { if (worker.joinable()) worker.join(); }
    bool await(TestHardware& hardware, const char* label) {
        const bool timely = done.wait_for(1500ms) == std::future_status::ready;
        if (!timely) {
            std::cerr << label << ": did not wake before timeout\n";
            hardware.rescue();
            if (done.wait_for(1500ms) != std::future_status::ready) {
                // Never detach a thread retaining references to a dead fixture.
                std::cerr << "Unable to join test worker safely; terminating test process\n";
                std::_Exit(EXIT_FAILURE);
            }
        }
        worker.join();
        try { done.get(); }
        catch (const std::exception& error) {
            std::cerr << label << ": " << error.what() << '\n';
            return false;
        }
        return timely;
    }
private:
    std::promise<void> result;
    std::future<void> done;
    std::thread worker;
};

void awaitEntry(const std::atomic<bool>& entered) {
    const auto deadline = std::chrono::steady_clock::now() + 1500ms;
    while (!entered.load()) {
        if (std::chrono::steady_clock::now() >= deadline) {
            std::cerr << "Test worker did not enter; terminating bounded test process\n";
            std::_Exit(EXIT_FAILURE);
        }
        std::this_thread::yield();
    }
}

bool resumeIdleAudio() {
    TestHardware hardware;
    std::atomic<bool> pending{true}, entered{false};
    auto gate = hardware.gateAudio();
    Task uc([&] { hardware.ucYieldLoop([&] {
        const bool shouldWait = pending.load();
        entered = true;
        return shouldWait;
    }); });
    awaitEntry(entered);
    // Both workers contend for the actual wait mutex. The condition is resolved
    // before the sleeper can acquire it: waiting must recheck the predicate.
    pending = false;
    Task audio([&] { hardware.audioBegin(); });
    gate.unlock();
    const bool audioDone = audio.await(hardware, "beginProcessAudio");
    const bool ucDone = uc.await(hardware, "idle UC resume");
    hardware.audioEnd();
    return audioDone && ucDone;
}

bool terminateIdleUc() {
    TestHardware hardware;
    std::atomic<bool> entered{false};
    auto gate = hardware.gateAudio();
    Task uc([&] { hardware.ucYieldLoop([&] { entered = true; return true; }); });
    awaitEntry(entered);
    Task termination([&] { hardware.requestUcTermination(); });
    gate.unlock();
    const bool terminated = termination.await(hardware, "idle termination request");
    const bool woke = uc.await(hardware, "idle UC termination");
    return terminated && woke;
}

bool terminateSynchronizedUc() {
    TestHardware hardware;
    std::atomic<bool> entered{false};
    auto gate = hardware.gateSync();
    Task uc([&] { entered = true; hardware.synchronize(); });
    awaitEntry(entered);
    Task termination([&] { hardware.requestUcTermination(); });
    gate.unlock();
    const bool terminated = termination.await(hardware, "synchronization termination request");
    const bool woke = uc.await(hardware, "synchronized UC termination");
    return terminated && woke;
}

bool terminationBeforeWaiting() {
    TestHardware hardware;
    hardware.haltDSP();
    if (!hardware.halted()) return false;
    hardware.requestUcTermination();
    if (hardware.halted()) return false;
    hardware.haltDSP();
    if (hardware.halted()) return false;
    Task uc([&] {
        hardware.ucYieldLoop([] { return true; });
        hardware.synchronize();
    });
    return uc.await(hardware, "termination before wait");
}
}

int main() {
    bool passed = true;
    // Repeat the contested transitions to exercise either mutex acquisition order.
    for (int i = 0; i < 32 && passed; ++i)
        passed = resumeIdleAudio() && terminateIdleUc() && terminateSynchronizedUc();
    passed = terminationBeforeWaiting() && passed;
    if (!passed) return EXIT_FAILURE;
    std::cout << "Waldorf lifecycle: idle resume, idle termination, synchronization termination, "
                 "and halted-DSP shutdown passed\n";
    return EXIT_SUCCESS;
}
