#include "XeniaProtocol.h"
#include <iostream>
#include <stdexcept>
#include <tuple>

namespace {
using rixenia::Bytes;
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
template<class F> void rejects(F operation) {
    bool threw = false;
    try { operation(); } catch (const std::exception&) { threw = true; }
    require(threw, "malformed import accepted");
}
Bytes dump(std::uint8_t command, std::size_t size) {
    Bytes result(size);
    result[0] = 0xf0; result[1] = 0x3e; result[2] = 0x0e; result[3] = 0;
    result[4] = command; result.back() = 0xf7;
    if (command != 0x17) result[size - 2] = rixenia::checksum(result, command == 0x14 ? 5 : 7);
    return result;
}
}

int main() {
    try {
        Bytes rom(rixenia::romSize, 0xff);
        rom[0] = 0xc0; rom[1] = 0xde; rom[0x2f] = '2'; rom[0x30] = '.'; rom[0x31] = '3'; rom[0x32] = '3';
        require(rixenia::validateRom(rom) == 233, "valid ROM header rejected");
        auto invalid = rom; invalid.resize(131072); rejects([&] { rixenia::validateRom(invalid); });
        invalid = rom; invalid[0] = 0; rejects([&] { rixenia::validateRom(invalid); });
        invalid = rom; invalid[0x31] = '/'; rejects([&] { rixenia::validateRom(invalid); });
        invalid = rom; invalid[0x31] = '0'; invalid[0x32] = '0'; rejects([&] { rixenia::validateRom(invalid); });

        auto single = dump(0x10, 265); single[5] = 1; single[6] = 127;
        const std::string name = "XT TEST 128";
        std::fill(single.begin() + 247, single.begin() + 263, ' ');
        std::copy(name.begin(), name.end(), single.begin() + 247);
        single[263] = rixenia::checksum(single, 7);
        require(rixenia::validDump(single), "valid bank B last patch rejected");
        require(rixenia::patchName(single) == name, "wrong name position");
        const auto edit = rixenia::singleEdit(single);
        require(edit[3] == 127 && edit[5] == 32 && edit[6] == 0 && rixenia::validDump(edit), "invalid edit-buffer conversion");
        require(std::equal(edit.begin() + 7, edit.end(), single.begin() + 7), "patch bytes changed on selection");
        invalid = single; invalid[9] ^= 1; require(!rixenia::validDump(invalid), "bad checksum accepted");
        invalid = single; invalid[5] = 2; require(!rixenia::validDump(invalid), "unknown bank accepted");
        invalid = single; invalid[2] = 0; require(!rixenia::validDump(invalid), "MW1 accepted as MWII");
        invalid = single; invalid[5] = 0x30; invalid[6] = 8; require(!rixenia::validDump(invalid), "ninth multi part accepted");

        const auto wave = dump(0x12, 137), table = dump(0x13, 265), multi = dump(0x11, 265), mode = dump(0x17, 7);
        auto global = dump(0x14, 39);
        for (const auto& packet : {wave, table, multi, mode, global}) require(rixenia::validDump(packet), "valid dump rejected");
        global[37] = rixenia::checksum(global, 4);
        require(rixenia::validDump(global), "legacy Gearmulator global checksum rejected");
        rixenia::normalizeDump(global);
        require(global[37] == rixenia::checksum(global, 5), "global checksum not normalized");
        invalid = wave; invalid[7] = 16; invalid[135] = rixenia::checksum(invalid, 7);
        require(!rixenia::validDump(invalid), "invalid wave nibble accepted");
        invalid = wave; invalid[5] = 7; invalid[6] = 0; require(!rixenia::validDump(invalid), "invalid wave address accepted");
        invalid = table; invalid[5] = 1; require(!rixenia::validDump(invalid), "out-of-range table accepted");
        invalid = mode; invalid[5] = 2; require(!rixenia::validDump(invalid), "invalid mode accepted");

        Bytes stream = wave; stream.insert(stream.end(), table.begin(), table.end()); stream.insert(stream.end(), single.begin(), single.end());
        const auto messages = rixenia::splitDumps(stream);
        require(messages.size() == 3 && messages[2][3] == 127, "valid stream not normalized/split");
        invalid = stream; invalid.push_back(0); rejects([&] { rixenia::splitDumps(invalid); });
        invalid = stream; invalid.pop_back(); rejects([&] { rixenia::splitDumps(invalid); });
        invalid = stream; invalid[10] = 0xf0; rejects([&] { rixenia::splitDumps(invalid); });
        invalid = stream; invalid.back() = 0x7e; rejects([&] { rixenia::splitDumps(invalid); });
        rejects([] { rixenia::splitDumps({}); });
        rejects([] { rixenia::splitDumps(Bytes(rixenia::maxImportBytes + 1, 0)); });
        require(!rixenia::validLiveSysex({0xf0, 0x3e, 0x0e, 0x7f, 0x71, 0xf7}), "firmware update accepted as live SysEx");
        require(!rixenia::validLiveSysex({0xf0, 0x3e, 0x0e, 0x7f, 0x20, 0xf7}), "truncated parameter accepted");
        require(rixenia::validLiveSysex({0xf0, 0x3e, 0x0e, 0x7f, 0x20, 0, 1, 127, 100, 0xf7}), "parameter255 rejected");
        require(!rixenia::validLiveSysex({0xf0, 0x3e, 0x0e, 0x7f, 0x20, 8, 0, 0, 100, 0xf7}), "ninth parameter part accepted");
        require(rixenia::validLiveSysex({0xf0, 0x3e, 0x0e, 0x7f, 0x24, 0, 31, 64, 0xf7}), "global parameter31 rejected");
        require(!rixenia::validLiveSysex({0xf0, 0x3e, 0x0e, 0x7f, 0x24, 0, 64, 0xf7}), "truncated global parameter accepted");

        rixenia::MidiDecoder decoder;
        std::vector<std::tuple<unsigned, unsigned, unsigned>> shorts;
        std::vector<Bytes> sysex;
        const auto capture = [&](std::uint8_t a, std::uint8_t b, std::uint8_t c, const Bytes* s) {
            if (s) sysex.push_back(*s); else shorts.emplace_back(a, b, c); return true;
        };
        const Bytes first{0x90, 60, 0xf8}, second{100, 61, 101, 0xc1, 3, 4, 0xd2, 99, 0x80, 60, 0};
        require(decoder.feed(first.data(), first.size(), capture), "fragment first part rejected");
        require(decoder.feed(second.data(), second.size(), capture), "running status rejected");
        require(shorts.size() == 7, "wrong running-status message count");
        require(shorts[0] == std::make_tuple(0xf8u, 0u, 0u), "realtime byte lost");
        require(shorts[1] == std::make_tuple(0x90u, 60u, 100u), "realtime damaged pending note");
        require(shorts[4] == std::make_tuple(0xc1u, 4u, 0u), "program-change running status broken");
        decoder.reset();
        for (const auto byte : single) require(decoder.feed(&byte, 1, capture), "fragmented SysEx rejected");
        require(sysex.size() == 1 && sysex.front() == single, "fragmented SysEx changed");
        Bytes oversized(5000, 0); oversized.front() = 0xf0; oversized.back() = 0xf7;
        require(!decoder.feed(oversized.data(), oversized.size(), capture), "oversized SysEx accepted");
        decoder.reset(); const Bytes note{0x90, 64, 127};
        require(decoder.feed(note.data(), note.size(), capture), "decoder did not recover");
        std::cout << "Xenia protocol: ROM boundaries, dumps/checksums/addresses, atomic parsing, running status and SysEx fragments passed\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
