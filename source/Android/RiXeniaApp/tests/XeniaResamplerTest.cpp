#include "XeniaResampler.h"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {
using rixenia::XeniaResampler;
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
template<class F> void rejects(F operation) {
    bool threw = false;
    try { operation(); } catch (const std::exception&) { threw = true; }
    require(threw, "invalid SRC call was accepted");
}
std::vector<float> convert(const std::vector<float>& input, const std::vector<std::size_t>& blocks) {
    XeniaResampler src; src.prepare();
    std::vector<float> result;
    const auto frames = input.size() / 2;
    std::size_t offset = 0, block = 0;
    while (offset < frames) {
        const auto count = std::min(blocks[block++ % blocks.size()], frames - offset);
        std::vector<float> out(XeniaResampler::maxOutputFrames(count) * 2);
        const auto wrote = src.process(input.data() + offset * 2, count, out.data(), out.size() / 2);
        result.insert(result.end(), out.begin(), out.begin() + static_cast<std::ptrdiff_t>(wrote * 2));
        offset += count;
    }
    require(src.inputFrames() == frames, "source frame count drifted");
    require(src.outputFrames() == XeniaResampler::maxOutputFrames(frames), "6/5 sample clock drifted");
    return result;
}
double toneAmplitude(const std::vector<float>& data, double frequency) {
    constexpr double pi = 3.14159265358979323846;
    const std::size_t first = 4800, length = data.size() / 2 - first;
    double real = 0, imag = 0;
    for (std::size_t i = 0; i < length; ++i) {
        const double phase = 2 * pi * frequency * static_cast<double>(i) / 48000;
        real += data[(i + first) * 2] * std::cos(phase);
        imag += data[(i + first) * 2] * std::sin(phase);
    }
    return 2 * std::hypot(real, imag) / static_cast<double>(length);
}
}

int main() {
    try {
        require(XeniaResampler::maxOutputFrames(256) == 308, "256 frame capacity");
        require(XeniaResampler::maxOutputFrames(512) == 615, "512 frame capacity");
        require(XeniaResampler::maxOutputFrames(1024) == 1229, "1024 frame capacity");
        XeniaResampler src;
        float in[20]{}, out[24]{};
        rejects([&] { src.process(in, 10, out, 12); });
        src.prepare();
        rejects([&] { src.process(in, 10, out, 11); });
        require(src.inputFrames() == 0 && src.outputFrames() == 0, "capacity failure changed stream");
        rejects([&] { src.process(nullptr, 10, out, 12); });
        require(src.process(nullptr, 0, nullptr, 0) == 0, "empty block not empty");

        constexpr double pi = 3.14159265358979323846;
        std::vector<float> input(80000);
        for (std::size_t i = 0; i < 40000; ++i) {
            input[i * 2] = static_cast<float>(0.4 * std::sin(2 * pi * 1000 * static_cast<double>(i) / 40000));
            input[i * 2 + 1] = input[i * 2] * -0.375f;
        }
        const auto one = convert(input, {40000});
        const auto split = convert(input, {1, 3, 7, 64, 256, 511, 1024});
        require(one.size() == 96000 && split.size() == one.size(), "wrong 48 kHz duration");
        double error = 0;
        for (std::size_t i = 0; i < one.size(); ++i) error = std::max(error, std::abs(static_cast<double>(one[i] - split[i])));
        require(error == 0, "phase/history reset at block boundary");
        for (std::size_t i = 0; i < one.size(); i += 2)
            require(std::abs(one[i + 1] + 0.375f * one[i]) < 1e-6f, "stereo cross-talk or channel skew");
        require(std::abs(toneAmplitude(one, 1000) - 0.4) < 0.001, "1 kHz amplitude changed");
        require(toneAmplitude(one, 9000) < 0.0004, "40 kHz image leaked at 9 kHz");

        for (std::size_t i = 0; i < 40000; ++i) {
            input[i * 2] = static_cast<float>(0.4 * std::sin(2 * pi * 18000 * static_cast<double>(i) / 40000));
            input[i * 2 + 1] = input[i * 2];
        }
        const auto high = convert(input, {512});
        require(toneAmplitude(high, 18000) > 0.36, "upper passband attenuated too much");
        require(toneAmplitude(high, 22000) < 0.001, "18 kHz image not rejected at 22 kHz");

        std::fill(input.begin(), input.end(), 0.25f);
        const auto dc = convert(input, {256});
        for (std::size_t i = 512; i < dc.size(); ++i)
            require(std::abs(dc[i] - 0.25f) < 1e-6f, "DC gain or phase ripple");
        src.process(in, 10, out, 12); src.reset();
        require(src.inputFrames() == 0 && src.outputFrames() == 0, "reset did not reset counters");
        std::cout << "Xenia SRC: 40k->48k frame counts, fragmentation, stereo, DC, passband and image rejection passed\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
