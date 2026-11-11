#pragma once
// RiOsTIrus: minimal MIDI 1.0 byte-stream parser (running status, SysEx,
// realtime bytes interleaved). Calls back with complete channel messages or
// complete F0..F7 SysEx. Host-testable; no Android or engine dependencies.

#include <cstddef>
#include <cstdint>
#include <vector>

namespace riostirus {

class MidiBytes {
public:
    static constexpr std::size_t maxSysex = 64 * 1024;

    // onShort(status, data1, data2), onSysex(bytes incl. F0/F7). Returns false if
    // a callback rejected a message (queue full); parsing continues regardless.
    template<class Short, class Sysex>
    bool feed(const std::uint8_t* data, std::size_t count, Short onShort, Sysex onSysex) {
        bool ok = true;
        for (std::size_t i = 0; i < count; ++i) {
            const std::uint8_t b = data[i];
            if (b >= 0xf8) continue;                       // realtime: ignored, never breaks a message
            if (b == 0xf0) { m_sysex.assign(1, b); m_inSysex = true; m_overflow = false; m_status = 0; continue; }
            if (b == 0xf7) {
                if (m_inSysex && !m_overflow) { m_sysex.push_back(b); ok &= onSysex(m_sysex); }
                m_inSysex = false; m_sysex.clear(); m_status = 0; continue; // EOX cancels running status
            }
            if (b & 0x80) {
                m_inSysex = false; m_sysex.clear();
                if (b >= 0xf1) { m_status = 0; continue; }      // system common: not forwarded
                m_status = b; m_count = 0; continue;
            }
            if (m_inSysex) {
                if (m_sysex.size() >= maxSysex) m_overflow = true; else m_sysex.push_back(b);
                continue;
            }
            if (!m_status) continue;                         // data without status
            m_data[m_count++] = b;
            const std::size_t needed = (m_status & 0xe0) == 0xc0 ? 1 : 2; // Cx, Dx take one byte
            if (m_count < needed) continue;
            ok &= onShort(m_status, m_data[0], needed == 2 ? m_data[1] : 0);
            m_count = 0;                                     // keep status for running status
        }
        return ok;
    }

    void reset() { m_status = 0; m_count = 0; m_inSysex = false; m_overflow = false; m_sysex.clear(); }

private:
    std::uint8_t m_status = 0;
    std::uint8_t m_data[2] = {0, 0};
    std::size_t m_count = 0;
    bool m_inSysex = false, m_overflow = false;
    std::vector<std::uint8_t> m_sysex;
};

} // namespace riostirus
