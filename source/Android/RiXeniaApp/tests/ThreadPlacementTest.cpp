// Host test for source/Android/common/ThreadPlacement.h (Linux).
// Build: c++ -std=c++17 -pthread -I../../common ThreadPlacementTest.cpp -o /tmp/placement && /tmp/placement
#include "ThreadPlacement.h"

#include <pthread.h>
#include <sys/stat.h>

#include <atomic>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
void require(bool ok, const std::string& message) { if (!ok) throw std::runtime_error(message); }

std::string makeTempDir() {
    char pattern[] = "/tmp/placementXXXXXX";
    const char* dir = mkdtemp(pattern);
    require(dir != nullptr, "mkdtemp failed");
    return dir;
}

void writeFile(const std::string& path, const std::string& text) {
    std::ofstream(path) << text << '\n';
}

// capacity/freq <= 0 means "attribute missing"; "bad" writes an invalid value.
std::string fakeSysfs(const std::vector<long>& capacity, const std::vector<long>& freq, bool badFirst = false) {
    const auto root = makeTempDir();
    for (std::size_t cpu = 0; cpu < std::max(capacity.size(), freq.size()); ++cpu) {
        const auto base = root + "/cpu" + std::to_string(cpu);
        mkdir(base.c_str(), 0755);
        mkdir((base + "/cpufreq").c_str(), 0755);
        if (cpu < capacity.size() && capacity[cpu] > 0) writeFile(base + "/cpu_capacity", std::to_string(capacity[cpu]));
        if (cpu < freq.size() && freq[cpu] > 0) writeFile(base + "/cpufreq/cpuinfo_max_freq", std::to_string(freq[cpu]));
        if (badFirst && cpu == 0) writeFile(base + "/cpu_capacity", "12abc");
    }
    mkdir((root + "/cpufreq").c_str(), 0755); // not a cpuN entry, must be ignored
    return root;
}

cpu_set_t range(int first, int last) {
    cpu_set_t mask; CPU_ZERO(&mask);
    for (int cpu = first; cpu <= last; ++cpu) CPU_SET(cpu, &mask);
    return mask;
}

std::string fastest(const std::string& root, const cpu_set_t& allowed, std::string* criterion = nullptr) {
    std::string why;
    const auto cpus = rinoize::selectFastestCpus(rinoize::readCpuTopology(root), allowed, why);
    if (criterion) *criterion = why;
    return rinoize::placementDetail::maskToString(rinoize::placementDetail::maskOf(cpus));
}

void testSelection() {
    // Galaxy Tab A9 (Helio G99) shape: 6 efficiency + 2 performance cores.
    const auto a9 = fakeSysfs({440, 440, 440, 440, 440, 440, 1024, 1024},
                              {2000000, 2000000, 2000000, 2000000, 2000000, 2000000, 2200000, 2200000});
    std::string why;
    require(fastest(a9, range(0, 7), &why) == "6-7" && why == "capacidad", "A9 must select 6-7 by capacity");
    require(fastest(a9, range(0, 5), &why) == "-" && why == "capacidad uniforme", "never widen beyond the original mask");
    require(fastest(a9, range(5, 7)) == "6-7", "subset of the original mask");
    require(fastest(a9, range(7, 7), &why) == "-" && why == "un solo CPU permitido", "single CPU keeps mask");

    // Uniform capacity: do NOT substitute MHz.
    const auto uniform = fakeSysfs({1024, 1024, 1024, 1024}, {1800000, 1800000, 2400000, 2400000});
    require(fastest(uniform, range(0, 3), &why) == "-" && why == "capacidad uniforme", "uniform capacity keeps mask");

    // Incomplete capacity: fall back to max frequency.
    const auto freqOnly = fakeSysfs({0, 0, 0, 0}, {1800000, 1800000, 2400000, 2400000});
    require(fastest(freqOnly, range(0, 3), &why) == "2-3" && why == "frecuencia maxima", "frequency fallback");
    const auto badCapacity = fakeSysfs({512, 1024, 1024}, {1000000, 1000000, 2000000}, true);
    require(fastest(badCapacity, range(0, 2), &why) == "2" && why == "frecuencia maxima", "invalid capacity is incomplete");

    const auto nothing = fakeSysfs({0, 0}, {0, 0});
    require(fastest(nothing, range(0, 1), &why) == "-" && why == "metadatos insuficientes", "no metadata keeps mask");

    // A single top core is selected alone (one or more cores, exact maximum).
    const auto prime = fakeSysfs({400, 400, 900, 900, 1024}, {});
    require(fastest(prime, range(0, 4)) == "4", "exact maximum, no thresholds");
}

void testMaskFormat() {
    using rinoize::placementDetail::maskToString;
    using rinoize::placementDetail::maskOf;
    require(maskToString(maskOf({0, 1, 2, 3, 4, 5, 7})) == "0-5,7", "mask format ranges");
    require(maskToString(maskOf({})) == "-", "empty mask");
    require(maskToString(maskOf({3})) == "3", "single cpu");
}

struct NamedWorker {
    std::atomic<bool> run{true};
    std::thread thread;
    explicit NamedWorker(const char* name) : thread([this, name] {
        pthread_setname_np(pthread_self(), name);
        while (run.load()) { volatile unsigned spin = 0; for (unsigned i = 0; i < 100000; ++i) spin = spin + i; }
    }) {}
    ~NamedWorker() { run = false; thread.join(); }
};

void testLiveThreads() {
    cpu_set_t process;
    require(sched_getaffinity(0, sizeof(process), &process) == 0, "sched_getaffinity");
    if (CPU_COUNT(&process) < 2 || !CPU_ISSET(0, &process) || !CPU_ISSET(1, &process)) {
        std::cout << "SKIP live placement: needs CPUs 0 and 1\n";
        return;
    }
    // Fake topology: CPU 1 is the fast one.
    const auto sysfs = fakeSysfs({512, 1024}, {});
    NamedWorker dsp("DSP A"), uc("MC68331");
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    require(rinoize::findThreadsByName("DSP A").size() == 1, "find DSP A by comm");
    require(rinoize::findThreadsByName("nope").empty(), "unknown name");

    rinoize::ThreadPlacer placer(sysfs);
    const auto original = rinoize::placementDetail::maskToString(process); // runner dependent, e.g. 0-1 or 0-3
    auto summary = placer.apply(rinoize::PlacementMode::Performance, {"DSP A", "MC68331", "DSP B"});
    require(summary.find("criterio capacidad") != std::string::npos, "summary criterion: " + summary);
    require(summary.find("DSP A " + original + "->1") != std::string::npos, "DSP A placed on 1: " + summary);
    require(summary.find("MC68331 " + original + "->1") != std::string::npos, "MC68331 placed on 1: " + summary);
    require(summary.find("DSP B no encontrado") != std::string::npos, "missing thread reported: " + summary);

    const pid_t tid = rinoize::findThreadsByName("DSP A").front();
    cpu_set_t mask;
    require(sched_getaffinity(tid, sizeof(mask), &mask) == 0 && CPU_COUNT(&mask) == 1 && CPU_ISSET(1, &mask),
            "kernel mask is 1");

    placer.report();
    std::this_thread::sleep_for(std::chrono::milliseconds(300));
    const auto report = placer.report();
    require(report.find("DSP A (tid") != std::string::npos && report.find("mascara 1") != std::string::npos, report);
    require(report.find("uso -") == std::string::npos, "usage sampled on second report: " + report);

    // Automatic restores what we changed.
    summary = placer.apply(rinoize::PlacementMode::Automatic, {"DSP A", "MC68331"});
    require(sched_getaffinity(tid, sizeof(mask), &mask) == 0 && CPU_ISSET(0, &mask) && CPU_ISSET(1, &mask),
            "Automatic restores the original mask: " + summary);

    // Spread with two threads and one fast core: both share the fast group.
    placer.apply(rinoize::PlacementMode::Spread, {"DSP A", "MC68331"});
    require(sched_getaffinity(tid, sizeof(mask), &mask) == 0 && CPU_COUNT(&mask) == 1 && CPU_ISSET(1, &mask), "spread");

    // Engine destroyed: forget must not touch old TIDs on the next apply.
    placer.forget();
    summary = placer.apply(rinoize::PlacementMode::Automatic, {});
    require(sched_getaffinity(tid, sizeof(mask), &mask) == 0 && CPU_COUNT(&mask) == 1, "forget leaves threads alone");
    sched_setaffinity(tid, sizeof(process), &process);

    // Watched-only thread appears in the report without being placed.
    rinoize::ThreadPlacer watcher(sysfs);
    watcher.apply(rinoize::PlacementMode::Performance, {}, {"MC68331"});
    require(watcher.report().find("MC68331 (tid") != std::string::npos, "watched thread reported");
}
} // namespace

int main() {
    try {
        testMaskFormat();
        testSelection();
        testLiveThreads();
    } catch (const std::exception& e) {
        std::cerr << "FAIL: " << e.what() << '\n';
        return 1;
    }
    std::cout << "ThreadPlacementTest OK\n";
    return 0;
}
