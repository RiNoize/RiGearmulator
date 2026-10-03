#include "RationalResampler.h"
#include <algorithm>
#include <iostream>
#include <vector>
#include <string>
#include <limits>
#include <stdexcept>
using rigear::RationalResampler;
static void check(bool value, const char* reason) { if (!value) throw std::runtime_error(reason); }
static std::vector<float> convert(const std::vector<float>& input, int chunk) {
    RationalResampler s; s.prepare();
    std::vector<float> result, scratch((RationalResampler::maxOutputFrames(chunk) + 4) * 2);
    for (std::size_t pos = 0; pos < input.size()/2;) {
        const auto n = std::min<std::size_t>(chunk, input.size()/2 - pos);
        std::fill(scratch.begin(), scratch.end(), 1234.0f);
        const auto out = s.process(input.data() + 2*pos, n, scratch.data(), scratch.size()/2-4);
        check(out <= RationalResampler::maxOutputFrames(n), "output bound");
        check(scratch[out*2] == 1234.0f, "buffer overrun");
        result.insert(result.end(), scratch.begin(), scratch.begin()+out*2);
        pos += n;
        check(s.outputFrames() == (pos*128+124)/125, "frame drift");
    }
    return result;
}
static std::vector<float> tone(double frequency, int n = 46875) {
    std::vector<float> v(n*2);
    for(int i=0;i<n;++i) { v[2*i] = float(.5*std::sin(2*3.141592653589793*frequency*i/46875)); v[2*i+1] = 0; }
    return v;
}
int main() {
    try {
        for (int frames : {256,512,1024}) {
            auto in = tone(1000);
            auto out = convert(in, frames);
            check(out.size()==96000, "one second duration/pitch count");
            check(out == convert(in, 1), "block boundary continuity");
            check(out == convert(in, 125), "fraction phase consistency");
            for(std::size_t i=0;i<out.size()/2;++i) {
                check(out[2*i+1]==0, "stereo crosstalk");
                check(std::isfinite(out[2*i]), "non finite output");
            }
        }
        for (double f : {100., 1000., 15000., 20000.}) {
            auto out=convert(tone(f), 256);
            double error=0, reference=0;
            for (std::size_t i=200;i<out.size()/2;++i) {
                double expected=.5*std::sin(2*3.141592653589793*f*(double(i)/48000-RationalResampler::delayInputFrames/46875));
                const double e=out[i*2]-expected; error+=e*e; reference+=expected*expected;
            }
            double db=10*std::log10(error/reference);
            std::cout << "tone " << f << " Hz, residual " << db << " dB\n";
            check(db < -55.0, "passband phase/amplitude accuracy");
        }
        std::vector<float> dc(46875*2, .25f);
        auto out=convert(dc, 512);
        for(size_t i=300;i<out.size();++i) check(std::abs(out[i]-.25f)<1e-6, "DC gain");
        RationalResampler s; s.prepare();
        float one[]={.2f,-.1f}, data[8]{};
        bool rejected=false; try { s.process(one,1,data,1); } catch(const std::invalid_argument&) { rejected=true; }
        check(rejected && s.inputFrames()==0, "capacity rejects before mutating");
        auto first=s.process(one,1,data,4); float saved[8]; std::copy_n(data,8,saved);
        s.reset(); auto second=s.process(one,1,data,4);
        check(first==second && std::equal(data,data+first*2,saved), "deterministic restart");
        check(convert(std::vector<float>(46875*5*2),1024).size()==48000*5*2, "long stream drift");
        std::cout << "PASS: duration, pitch, arbitrary boundaries, channel isolation, reset, DC, bounds\n";
    } catch(const std::exception& e) { std::cerr << "FAIL: " << e.what() << '\n'; return 1; }
}
