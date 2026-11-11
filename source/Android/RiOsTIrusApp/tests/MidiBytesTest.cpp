// Host test for riostirusNative/MidiBytes.h.
#include "MidiBytes.h"

#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }

struct Capture {
    std::vector<std::vector<std::uint8_t>> shorts, sysex;
    bool accept = true;
    bool feed(riostirus::MidiBytes& parser, const std::vector<std::uint8_t>& bytes) {
        return parser.feed(bytes.data(), bytes.size(),
            [this](std::uint8_t a, std::uint8_t b, std::uint8_t c) { shorts.push_back({a, b, c}); return accept; },
            [this](const std::vector<std::uint8_t>& s) { sysex.push_back(s); return accept; });
    }
};
} // namespace

int main() {
    try {
        {
            riostirus::MidiBytes parser; Capture c;
            c.feed(parser, {0x90, 60, 100, 64, 90, 0x80, 60, 0});           // running status
            require(c.shorts.size() == 3, "running status yields three messages");
            require(c.shorts[1] == std::vector<std::uint8_t>({0x90, 64, 90}), "running status keeps 0x90");
        }
        {
            riostirus::MidiBytes parser; Capture c;
            c.feed(parser, {0x90, 60}); c.feed(parser, {100});              // split across packets
            require(c.shorts.size() == 1 && c.shorts[0][2] == 100, "message split across packets");
        }
        {
            riostirus::MidiBytes parser; Capture c;
            c.feed(parser, {0xc0, 5, 6, 0xd1, 70});                        // one data byte commands
            require(c.shorts.size() == 3 && c.shorts[0][1] == 5 && c.shorts[1][1] == 6 && c.shorts[2][0] == 0xd1,
                    "program change and channel pressure take one byte");
        }
        {
            riostirus::MidiBytes parser; Capture c;
            c.feed(parser, {0x90, 0xf8, 60, 0xfe, 100});                   // realtime interleaved
            require(c.shorts.size() == 1 && c.shorts[0][1] == 60, "realtime bytes do not break a message");
        }
        {
            riostirus::MidiBytes parser; Capture c;
            c.feed(parser, {0xf0, 0x00, 0x20, 0x33, 0xf8, 0x01, 0xf7, 0x90, 1, 2});
            require(c.sysex.size() == 1 && c.sysex[0].size() == 6 && c.sysex[0].front() == 0xf0 && c.sysex[0].back() == 0xf7,
                    "complete sysex, realtime removed");
            require(c.shorts.size() == 1, "message after sysex");
        }
        {
            riostirus::MidiBytes parser; Capture c;
            c.feed(parser, {0xf0, 1, 2, 0x90, 60, 100});                   // aborted sysex
            require(c.sysex.empty() && c.shorts.size() == 1, "status byte aborts sysex");
            c.feed(parser, {0xf7, 61, 1});                                 // stray F7, no status after it
            require(c.shorts.size() == 1, "data after stray F7 is not treated as running status");
        }
        {
            riostirus::MidiBytes parser; Capture c;
            std::vector<std::uint8_t> huge(riostirus::MidiBytes::maxSysex + 10, 0x11);
            huge.front() = 0xf0; huge.back() = 0xf7;
            c.feed(parser, huge);
            require(c.sysex.empty(), "oversized sysex dropped");
        }
        {
            riostirus::MidiBytes parser; Capture c; c.accept = false;
            require(!c.feed(parser, {0x90, 60, 100}), "rejection reported");
        }
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
    std::cout << "MidiBytesTest OK\n";
    return 0;
}
