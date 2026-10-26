#pragma once

#include "XeniaProtocol.h"
#include <algorithm>
#include <cstdint>
#include <stdexcept>

namespace rixenia {
// A continuous stream of clock/CC/note messages must not starve a patch dump.
// Preserve complete-dump pacing, and alternate eligible channel/control batches.
class MidiDispatch {
public:
    enum class Next { None, Channel, Control };
    void reset() noexcept { channelLast_ = false; }
    Next choose(bool channel, bool control, int cooldown) noexcept {
        if (cooldown > 0) return Next::None;
        if (control && (!channel || channelLast_)) {
            channelLast_ = false;
            return Next::Control;
        }
        if (channel) {
            channelLast_ = true;
            return Next::Channel;
        }
        if (control) return Next::Control;
        return Next::None;
    }
private:
    bool channelLast_ = false;
};

// UI intent is not an acknowledgement. Only a fresh hardware readback can
// finish a transaction; the emulator's immediate State cache cannot do so.
class PatchChange {
public:
    void reset() { target_.clear(); sent_ = false; started_ = 0; lastMs_ = -1; ++generation_; }
    void request(const Bytes& single, std::int64_t nowMs) {
        if (!validDump(single) || single[4] != 0x10)
            throw std::invalid_argument("Invalid Single patch transaction");
        target_ = singleEdit(single); sent_ = false; started_ = nowMs; ++generation_;
    }
    void dispatched(const Bytes& single) {
        if (pending() && samePayload(single, target_)) sent_ = true;
    }
    bool confirm(const Bytes& actual, std::uint64_t queryGeneration, std::int64_t nowMs) {
        if (!sent_ || queryGeneration != generation_ || !validDump(actual) ||
            actual[5] != 0x20 || actual[6] != 0 || !samePayload(actual, target_)) return false;
        lastMs_ = static_cast<int>(std::clamp<std::int64_t>(nowMs - started_, 0, 2147483647));
        target_.clear(); sent_ = false; return true;
    }
    bool pending() const noexcept { return !target_.empty(); }
    bool sent() const noexcept { return sent_; }
    std::uint64_t generation() const noexcept { return generation_; }
    int elapsedMs(std::int64_t nowMs) const noexcept {
        return pending() ? static_cast<int>(std::clamp<std::int64_t>(nowMs - started_, 0, 2147483647)) : 0;
    }
    int phase(std::int64_t nowMs) const noexcept {
        if (!pending()) return 0;
        if (elapsedMs(nowMs) >= 3000) return 3; // Not confirmed; never report a timed-out patch as active.
        return sent_ ? 2 : 1;
    }
    int lastMs() const noexcept { return lastMs_; }
    static bool samePayload(const Bytes& a, const Bytes& b) noexcept {
        return a.size() == singleSize && b.size() == singleSize && a[4] == 0x10 && b[4] == 0x10 &&
            std::equal(a.begin() + 7, a.begin() + 263, b.begin() + 7);
    }
private:
    Bytes target_;
    std::int64_t started_ = 0;
    std::uint64_t generation_ = 0;
    bool sent_ = false;
    int lastMs_ = -1;
};
} // namespace rixenia
