#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace rixenia {
using Bytes = std::vector<std::uint8_t>;
constexpr std::size_t romSize = 262144;
constexpr std::size_t singleSize = 265;
constexpr std::size_t maxImportBytes = 1024 * 1024;
constexpr std::size_t maxMidiSysexBytes = 4096;

inline int validateRom(const Bytes& data) {
    if (data.size() != romSize)
        throw std::invalid_argument("Xenia necesita una ROM .bin completa de 262144 bytes. El MIDI de actualizacion no incluye las ondas.");
    if (data[0] != 0xc0 || data[1] != 0xde || data[0x30] != '.')
        throw std::invalid_argument("La cabecera no corresponde a una ROM Microwave II/XT.");
    for (const auto i : {0x2f, 0x31, 0x32})
        if (data[i] < '0' || data[i] > '9')
            throw std::invalid_argument("Version de ROM Microwave II/XT invalida.");
    const int version = (data[0x2f] - '0') * 100 + (data[0x31] - '0') * 10 + data[0x32] - '0';
    if (version <= 200) throw std::invalid_argument("Xenia requiere firmware Microwave II/XT posterior a 2.00.");
    return version;
}

inline std::uint8_t checksum(const Bytes& data, std::size_t first) {
    unsigned sum = 0;
    for (std::size_t i = first; i + 2 < data.size(); ++i) sum += data[i];
    return static_cast<std::uint8_t>(sum & 127);
}

inline bool validEnvelope(const Bytes& data) {
    if (data.size() < 6 || data.front() != 0xf0 || data.back() != 0xf7 ||
        data[1] != 0x3e || data[2] != 0x0e) return false;
    return std::all_of(data.begin() + 1, data.end() - 1,
                       [](std::uint8_t b) { return b < 128; });
}

// Only complete, bounded MW II/XT dumps are accepted. In particular a firmware
// update (0x71) and arbitrary manufacturer commands never reach the emulator.
inline bool validDump(const Bytes& data) {
    if (!validEnvelope(data)) return false;
    const auto command = data[4];
    if (command == 0x17) return data.size() == 7 && data[5] <= 1;
    const std::size_t expected = command == 0x14 ? 39 : command == 0x12 ? 137 :
        (command == 0x10 || command == 0x11 || command == 0x13) ? 265 : 0;
    if (data.size() != expected) return false;
    const auto check = data[data.size() - 2];
    if (command == 0x14) {
        // Official packet definition uses byte 5; older Gearmulator state
        // exports include the command byte. Normalize that legacy form below.
        return check == checksum(data, 5) || check == checksum(data, 4);
    }
    if (check != checksum(data, 7)) return false;
    if (command == 0x10) {
        return ((data[5] == 0 || data[5] == 1) && data[6] < 128) ||
            (data[5] == 0x20 && data[6] == 0) ||
            (data[5] == 0x30 && data[6] < 8);
    }
    if (command == 0x11)
        return data[5] == 0 || (data[5] == 0x20 && data[6] == 0);
    const unsigned index = (data[5] << 7) | data[6];
    if (command == 0x12 && !(index < 506 || (index >= 1000 && index < 1250))) return false;
    if (command == 0x13 && index >= 128) return false;
    // Waves and wavetable words use nibbles, not arbitrary MIDI bytes.
    return std::all_of(data.begin() + 7, data.end() - 2,
                       [](std::uint8_t b) { return b < 16; });
}

inline void normalizeDump(Bytes& data) {
    data[3] = 0x7f;
    if (data[4] == 0x14) data[data.size() - 2] = checksum(data, 5);
}

inline std::vector<Bytes> splitDumps(const Bytes& stream) {
    if (stream.empty() || stream.size() > maxImportBytes)
        throw std::invalid_argument("El banco SysEx esta vacio o supera 1 MiB.");
    std::vector<Bytes> messages;
    for (std::size_t first = 0; first < stream.size();) {
        if (stream[first] != 0xf0)
            throw std::invalid_argument("Datos fuera de un mensaje SysEx; importe .syx o un MIDI convertido.");
        auto end = std::find(stream.begin() + static_cast<std::ptrdiff_t>(first + 1), stream.end(), 0xf7);
        if (end == stream.end()) throw std::invalid_argument("Mensaje SysEx incompleto.");
        const std::size_t last = static_cast<std::size_t>(end - stream.begin());
        Bytes message(stream.begin() + static_cast<std::ptrdiff_t>(first), end + 1);
        if (!validDump(message))
            throw std::invalid_argument("Banco invalido: solo dumps Microwave II/XT completos con checksum correcto.");
        normalizeDump(message);
        messages.push_back(std::move(message));
        if (messages.size() > 4096) throw std::invalid_argument("El banco contiene demasiados mensajes.");
        first = last + 1;
    }
    return messages;
}

inline bool validLiveSysex(const Bytes& data) {
    if (validDump(data)) return true;
    if (!validEnvelope(data)) return false;
    switch (data[4]) {
    case 0x00: return data.size() == 8 &&
        ((data[5] <= 1) || (data[5] == 0x20 && data[6] == 0) || (data[5] == 0x30 && data[6] < 8));
    case 0x04: case 0x07: return data.size() == 6;
    case 0x20: return data.size() == 10 && data[5] < 8 && data[6] <= 1;
    case 0x24: return data.size() == 9 && data[5] == 0 && data[6] < 32;
    case 0x27: return data.size() == 7 && data[5] <= 1;
    default: return false;
    }
}

inline Bytes singleEdit(const Bytes& source) {
    if (!validDump(source) || source[4] != 0x10)
        throw std::invalid_argument("El sonido no es un Single Microwave II/XT.");
    Bytes result = source;
    result[3] = 0x7f; result[5] = 0x20; result[6] = 0;
    return result;
}

inline std::string patchName(const Bytes& data) {
    if (data.size() != singleSize || data[4] != 0x10) return {};
    std::string name;
    for (std::size_t i = 247; i < 263; ++i)
        name.push_back(data[i] >= 32 && data[i] < 127 ? static_cast<char>(data[i]) : ' ');
    while (!name.empty() && name.back() == ' ') name.pop_back();
    return name;
}

// Persistent MIDI byte decoder: handles fragmented USB packets, running status,
// interleaved realtime bytes and bounded fragmented SysEx without allocations
// per short message. Callback receives a complete short message or complete SysEx.
class MidiDecoder {
public:
    void reset() { status_ = 0; count_ = 0; inSysex_ = false; sysex_.clear(); }
    template<class Callback> bool feed(const std::uint8_t* bytes, std::size_t size, Callback callback) {
        bool ok = true;
        for (std::size_t i = 0; i < size; ++i) {
            const auto b = bytes[i];
            if (b >= 0xf8) { if (!callback(b, 0, 0, nullptr)) ok = false; continue; }
            if (inSysex_) {
                if (b == 0xf7) {
                    sysex_.push_back(b);
                    if (!callback(0, 0, 0, &sysex_)) ok = false;
                    sysex_.clear(); inSysex_ = false; status_ = 0; count_ = 0;
                    continue;
                }
                if (b & 0x80) { inSysex_ = false; sysex_.clear(); ok = false; }
                else {
                    if (sysex_.size() >= maxMidiSysexBytes - 1) {
                        reset(); ok = false;
                    } else sysex_.push_back(b);
                    continue;
                }
            }
            if (b == 0xf0) {
                status_ = 0; count_ = 0; inSysex_ = true;
                sysex_.clear(); sysex_.push_back(b); continue;
            }
            if (b & 0x80) {
                status_ = b; count_ = 0;
                if (length(b) == 1) {
                    if (b == 0xf6 && !callback(b, 0, 0, nullptr)) ok = false;
                    else if (b != 0xf6) ok = false;
                    status_ = 0;
                }
                continue;
            }
            if (!status_) { ok = false; continue; }
            data_[count_++] = b;
            if (count_ == length(status_) - 1) {
                if (!callback(status_, data_[0], count_ == 2 ? data_[1] : 0, nullptr)) ok = false;
                count_ = 0;
                if (status_ >= 0xf0) status_ = 0;
            }
        }
        return ok;
    }
private:
    static unsigned length(std::uint8_t s) {
        if (s < 0xf0) return (s & 0xe0) == 0xc0 ? 2 : 3;
        if (s == 0xf1 || s == 0xf3) return 2;
        return s == 0xf2 ? 3 : 1;
    }
    std::uint8_t status_ = 0;
    std::array<std::uint8_t, 2> data_{};
    unsigned count_ = 0;
    bool inSysex_ = false;
    Bytes sysex_;
};
} // namespace rixenia
