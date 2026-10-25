#pragma once
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>

namespace rixenia {
// Xenia's fixed 40 kHz engine -> Android 48 kHz. The 6/5 phase accumulator
// survives every host block; processing allocates no memory and evaluates no trig.
class XeniaResampler {
public:
    static constexpr int inputRate = 40000, outputRate = 48000;
    static constexpr int taps = 96, phases = 6, step = 5;
    static constexpr double delayInputFrames = (taps - 1) * 0.5;
    static constexpr double delayMs = 1000.0 * delayInputFrames / inputRate;
    static constexpr std::size_t maxOutputFrames(std::size_t n) { return (n * phases + step - 1) / step; }
    void prepare() {
        constexpr double pi = 3.14159265358979323846, cutoff = 0.94;
        for (int p = 0; p < phases; ++p) {
            double sum = 0;
            for (int k = 0; k < taps; ++k) {
                const double x = k + static_cast<double>(p) / phases - delayInputFrames;
                const double sinc = std::abs(x) < 1e-14 ? cutoff : std::sin(pi * cutoff * x) / (pi * x);
                const double window = 0.42 - 0.5 * std::cos(2 * pi * k / (taps - 1))
                                              + 0.08 * std::cos(4 * pi * k / (taps - 1));
                coeffs_[p][k] = static_cast<float>(sinc * window); sum += sinc * window;
            }
            for (float& value : coeffs_[p]) value = static_cast<float>(value / sum);
        }
        prepared_ = true; reset();
    }
    void reset() noexcept {
        history_.fill({0.0f, 0.0f}); cursor_ = 0; phase_ = 0;
        nextIndex_ = inputs_ = outputs_ = 0;
    }
    std::size_t process(const float* input, std::size_t frames, float* output, std::size_t capacity) {
        if (!prepared_) throw std::logic_error("SRC no preparado");
        if (!frames) return 0;
        if (!input || !output || capacity < maxOutputFrames(frames))
            throw std::invalid_argument("Buffer SRC insuficiente");
        std::size_t produced = 0;
        for (std::size_t i = 0; i < frames; ++i) {
            cursor_ = (cursor_ + taps - 1) % taps;
            history_[cursor_] = {input[i * 2], input[i * 2 + 1]};
            while (nextIndex_ == inputs_) {
                const auto& coeff = coeffs_[phase_];
                float left = 0, right = 0;
                const int first = taps - cursor_;
                for (int k = 0; k < first; ++k) {
                    left += coeff[k] * history_[cursor_ + k][0];
                    right += coeff[k] * history_[cursor_ + k][1];
                }
                for (int k = first; k < taps; ++k) {
                    left += coeff[k] * history_[k - first][0];
                    right += coeff[k] * history_[k - first][1];
                }
                output[produced * 2] = left; output[produced * 2 + 1] = right;
                ++produced; ++outputs_;
                phase_ += step; nextIndex_ += phase_ / phases; phase_ %= phases;
            }
            ++inputs_;
        }
        return produced;
    }
    std::uint64_t inputFrames() const noexcept { return inputs_; }
    std::uint64_t outputFrames() const noexcept { return outputs_; }
private:
    std::array<std::array<float, taps>, phases> coeffs_{};
    std::array<std::array<float, 2>, taps> history_{};
    int cursor_ = 0, phase_ = 0;
    std::uint64_t nextIndex_ = 0, inputs_ = 0, outputs_ = 0;
    bool prepared_ = false;
};
} // namespace rixenia
