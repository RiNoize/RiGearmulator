#pragma once
// RiNoize: per-thread CPU placement for gearmulator engines on Android.
//
// The gearmulator engines do their heavy work in worker threads they create
// themselves (dsp56k::DSPThread, the MC68331 thread), not in the audio callback.
// This helper finds those workers by name in /proc/self/task and restricts their
// affinity to the fastest CPUs the thread was already allowed to use. It follows
// the RiMU2000 audio_cpu_placement policy:
//   - candidates are limited to each thread's original affinity mask;
//   - prefer cpu_capacity; cpuinfo_max_freq only when capacity is incomplete;
//   - exact maximum, no thresholds; uniform values keep the original mask;
//   - nothing in sysfs is written, frequency and idle stay under system control.
//
// Not realtime-safe: call apply()/forget()/report() from control or monitor
// threads, never from the render loop.

#include <dirent.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace rinoize {

enum class PlacementMode : int { Automatic = 0, Performance = 1, Spread = 2 };

inline const char* placementModeName(PlacementMode mode) {
    switch (mode) {
        case PlacementMode::Performance: return "Rendimiento";
        case PlacementMode::Spread: return "Repartido";
        default: return "Automatico";
    }
}

struct CpuTopology {
    // Indexed by CPU number. 0 means missing or invalid.
    std::vector<bool> present;
    std::vector<long> capacity, maxFreqKHz;
};

namespace placementDetail {

inline bool allDigits(const std::string& text) {
    return !text.empty() && std::all_of(text.begin(), text.end(), [](unsigned char c) { return std::isdigit(c) != 0; });
}

inline bool readPositive(const std::string& path, long& value) {
    std::ifstream file(path);
    std::string text;
    if (!file || !(file >> text) || !allDigits(text) || text.size() > 12) return false;
    value = std::stol(text);
    return value > 0;
}

inline std::string readComm(const std::string& path) {
    std::ifstream file(path);
    std::string name;
    std::getline(file, name);
    return name;
}

inline std::string maskToString(const cpu_set_t& mask) {
    std::ostringstream out;
    int first = -1, last = -2;
    bool any = false;
    auto flush = [&] {
        if (first < 0) return;
        if (any) out << ',';
        out << first;
        if (last > first) out << '-' << last;
        any = true;
    };
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (!CPU_ISSET(cpu, &mask)) continue;
        if (cpu == last + 1) { last = cpu; continue; }
        flush();
        first = last = cpu;
    }
    flush();
    return any ? out.str() : "-";
}

inline cpu_set_t maskOf(const std::vector<int>& cpus) {
    cpu_set_t mask;
    CPU_ZERO(&mask);
    for (const int cpu : cpus) if (cpu >= 0 && cpu < CPU_SETSIZE) CPU_SET(cpu, &mask);
    return mask;
}

struct ThreadStat { std::uint64_t ticks = 0; int processor = -1; };

// /proc/<tid>/stat: the comm field may contain spaces, so parse after the last ')'.
inline bool readThreadStat(const std::string& path, ThreadStat& stat) {
    std::ifstream file(path);
    std::string line;
    if (!std::getline(file, line)) return false;
    const auto close = line.rfind(')');
    if (close == std::string::npos) return false;
    std::istringstream fields(line.substr(close + 1));
    std::vector<std::string> tokens;
    for (std::string token; fields >> token;) tokens.push_back(token);
    // tokens[0] is field 3 (state): utime=14, stime=15, processor=39.
    if (tokens.size() < 37) return false;
    stat.ticks = std::stoull(tokens[11]) + std::stoull(tokens[12]);
    stat.processor = std::stoi(tokens[36]);
    return true;
}

} // namespace placementDetail

inline CpuTopology readCpuTopology(const std::string& root = "/sys/devices/system/cpu") {
    CpuTopology topology;
    DIR* dir = opendir(root.c_str());
    if (!dir) return topology;
    std::vector<int> cpus;
    while (const dirent* entry = readdir(dir)) {
        const std::string name = entry->d_name;
        if (name.size() > 3 && name.compare(0, 3, "cpu") == 0 && placementDetail::allDigits(name.substr(3))) {
            const int cpu = std::stoi(name.substr(3));
            if (cpu < CPU_SETSIZE) cpus.push_back(cpu);
        }
    }
    closedir(dir);
    if (cpus.empty()) return topology;
    const auto count = static_cast<std::size_t>(*std::max_element(cpus.begin(), cpus.end()) + 1);
    topology.present.assign(count, false);
    topology.capacity.assign(count, 0);
    topology.maxFreqKHz.assign(count, 0);
    for (const int cpu : cpus) {
        const auto base = root + "/cpu" + std::to_string(cpu);
        const auto i = static_cast<std::size_t>(cpu);
        topology.present[i] = true;
        long value = 0;
        if (placementDetail::readPositive(base + "/cpu_capacity", value)) topology.capacity[i] = value;
        if (placementDetail::readPositive(base + "/cpufreq/cpuinfo_max_freq", value)) topology.maxFreqKHz[i] = value;
    }
    return topology;
}

// Returns the subset of `allowed` with the highest capacity (or max frequency as a
// fallback), ascending. Empty means "keep the original mask"; `criterion` says why.
inline std::vector<int> selectFastestCpus(const CpuTopology& topology, const cpu_set_t& allowed, std::string& criterion) {
    std::vector<int> candidates;
    for (std::size_t cpu = 0; cpu < topology.present.size(); ++cpu)
        if (topology.present[cpu] && CPU_ISSET(static_cast<int>(cpu), &allowed)) candidates.push_back(static_cast<int>(cpu));
    if (candidates.size() < 2) { criterion = "un solo CPU permitido"; return {}; }
    auto pick = [&](const std::vector<long>& values, const char* name, const char* uniform) -> std::vector<int> {
        long low = values[static_cast<std::size_t>(candidates.front())], high = low;
        for (const int cpu : candidates) {
            low = std::min(low, values[static_cast<std::size_t>(cpu)]);
            high = std::max(high, values[static_cast<std::size_t>(cpu)]);
        }
        if (low == high) { criterion = uniform; return {}; }
        std::vector<int> fastest;
        for (const int cpu : candidates) if (values[static_cast<std::size_t>(cpu)] == high) fastest.push_back(cpu);
        criterion = name;
        return fastest;
    };
    auto complete = [&](const std::vector<long>& values) {
        return std::all_of(candidates.begin(), candidates.end(), [&](int cpu) { return values[static_cast<std::size_t>(cpu)] > 0; });
    };
    if (complete(topology.capacity)) return pick(topology.capacity, "capacidad", "capacidad uniforme");
    if (complete(topology.maxFreqKHz)) return pick(topology.maxFreqKHz, "frecuencia maxima", "frecuencia uniforme");
    criterion = "metadatos insuficientes";
    return {};
}

inline std::vector<pid_t> findThreadsByName(const std::string& name, const std::string& taskDir = "/proc/self/task") {
    std::vector<pid_t> result;
    DIR* dir = opendir(taskDir.c_str());
    if (!dir) return result;
    while (const dirent* entry = readdir(dir)) {
        const std::string tid = entry->d_name;
        if (!placementDetail::allDigits(tid)) continue;
        if (placementDetail::readComm(taskDir + "/" + tid + "/comm") == name)
            result.push_back(static_cast<pid_t>(std::stol(tid)));
    }
    closedir(dir);
    std::sort(result.begin(), result.end());
    return result;
}

class ThreadPlacer {
public:
    explicit ThreadPlacer(std::string cpuRoot = "/sys/devices/system/cpu", std::string taskDir = "/proc/self/task")
        : m_cpuRoot(std::move(cpuRoot)), m_taskDir(std::move(taskDir)) {}

    // `placed` threads get the policy; `watched` threads are only reported (for
    // example the Java render thread, which may start after this call).
    // The engine threads must already exist. Returns a one-line summary.
    std::string apply(PlacementMode mode, const std::vector<std::string>& placed,
                      const std::vector<std::string>& watched = {}) {
        std::lock_guard<std::mutex> lock(m_mutex);
        restoreLocked();
        m_entries.clear();
        m_mode = mode;
        m_watched = watched;
        m_fastest.clear();
        m_criterion = "sin cambios";
        const auto topology = readCpuTopology(m_cpuRoot);
        std::size_t hotIndex = 0;
        for (const auto& name : placed) {
            for (const pid_t tid : findThreadsByName(name, m_taskDir)) {
                Entry entry;
                entry.name = name; entry.tid = tid; entry.placed = true;
                if (sched_getaffinity(tid, sizeof(entry.original), &entry.original) != 0) {
                    entry.error = errno;
                    m_entries.push_back(entry);
                    continue;
                }
                entry.effective = entry.original;
                if (mode != PlacementMode::Automatic) placeLocked(entry, topology, mode, hotIndex++);
                m_entries.push_back(entry);
            }
        }
        return summaryLocked(placed);
    }

    // The engine was destroyed: its threads are gone. Never touch old TIDs.
    void forget() {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_entries.clear();
        m_watched.clear();
    }

    // Per-thread usage since the previous call (100% = one core), last CPU,
    // current mask and nice. One line per thread.
    std::string report() {
        std::lock_guard<std::mutex> lock(m_mutex);
        for (const auto& name : m_watched) {
            const bool known = std::any_of(m_entries.begin(), m_entries.end(), [&](const Entry& e) { return e.name == name; });
            if (known) continue;
            for (const pid_t tid : findThreadsByName(name, m_taskDir)) {
                Entry entry; entry.name = name; entry.tid = tid;
                sched_getaffinity(tid, sizeof(entry.original), &entry.original);
                entry.effective = entry.original;
                m_entries.push_back(entry);
            }
        }
        const double ticksPerSecond = static_cast<double>(sysconf(_SC_CLK_TCK));
        const auto now = std::chrono::steady_clock::now();
        std::ostringstream out;
        for (auto& entry : m_entries) {
            if (out.tellp() > 0) out << '\n';
            out << entry.name << " (tid " << entry.tid << ")";
            if (!alive(entry)) {
                out << ": terminado";
                continue;
            }
            placementDetail::ThreadStat stat;
            if (placementDetail::readThreadStat(m_taskDir + "/" + std::to_string(entry.tid) + "/stat", stat)) {
                if (entry.sampled && ticksPerSecond > 0) {
                    const double seconds = std::chrono::duration<double>(now - entry.lastSample).count();
                    if (seconds > 0)
                        entry.usage = 100.0 * static_cast<double>(stat.ticks - entry.lastTicks) / ticksPerSecond / seconds;
                }
                entry.lastTicks = stat.ticks; entry.lastSample = now; entry.sampled = true;
                entry.lastCpu = stat.processor;
            }
            cpu_set_t current;
            const bool haveMask = sched_getaffinity(entry.tid, sizeof(current), &current) == 0;
            errno = 0;
            const int nice = getpriority(PRIO_PROCESS, static_cast<id_t>(entry.tid));
            const bool haveNice = errno == 0;
            char usage[16];
            std::snprintf(usage, sizeof(usage), "%.0f%%", entry.usage);
            out << " · CPU " << entry.lastCpu << " · uso " << (entry.sampled && entry.usage >= 0 ? usage : "-")
                << " · mascara " << (haveMask ? placementDetail::maskToString(current) : "?")
                << " · nice " << (haveNice ? std::to_string(nice) : "?");
        }
        return out.str();
    }

private:
    struct Entry {
        std::string name;
        pid_t tid = 0;
        bool placed = false, touched = false;
        int error = 0;
        cpu_set_t original{}, effective{};
        std::uint64_t lastTicks = 0;
        std::chrono::steady_clock::time_point lastSample{};
        bool sampled = false;
        double usage = -1.0;
        int lastCpu = -1;
    };

    bool alive(const Entry& entry) const {
        return placementDetail::readComm(m_taskDir + "/" + std::to_string(entry.tid) + "/comm") == entry.name;
    }

    void placeLocked(Entry& entry, const CpuTopology& topology, PlacementMode mode, std::size_t hotIndex) {
        std::string criterion;
        const auto fastest = selectFastestCpus(topology, entry.original, criterion);
        m_criterion = criterion;
        if (fastest.empty()) return;
        if (m_fastest.empty()) m_fastest = fastest;
        // Spread: one fast core per hot thread, in the order given. Threads beyond
        // the number of fast cores share the whole fast group.
        const auto requested = mode == PlacementMode::Spread && hotIndex < fastest.size()
            ? placementDetail::maskOf({fastest[hotIndex]})
            : placementDetail::maskOf(fastest);
        if (sched_setaffinity(entry.tid, sizeof(requested), &requested) != 0) {
            entry.error = errno;
            return;
        }
        entry.touched = true;
        if (sched_getaffinity(entry.tid, sizeof(entry.effective), &entry.effective) != 0) entry.error = errno;
    }

    void restoreLocked() {
        for (const auto& entry : m_entries) {
            if (!entry.touched || !alive(entry)) continue;
            sched_setaffinity(entry.tid, sizeof(entry.original), &entry.original);
        }
    }

    std::string summaryLocked(const std::vector<std::string>& placed) const {
        std::ostringstream out;
        out << "Nucleos " << placementModeName(m_mode);
        if (m_mode != PlacementMode::Automatic) {
            out << " · criterio " << m_criterion;
            if (!m_fastest.empty()) out << " · rapidos " << placementDetail::maskToString(placementDetail::maskOf(m_fastest));
        }
        for (const auto& name : placed) {
            const bool found = std::any_of(m_entries.begin(), m_entries.end(), [&](const Entry& e) { return e.name == name; });
            if (!found) out << " · " << name << " no encontrado";
        }
        for (const auto& entry : m_entries) {
            out << " · " << entry.name << ' ' << placementDetail::maskToString(entry.original)
                << "->" << placementDetail::maskToString(entry.effective);
            if (entry.error) out << " errno " << entry.error << " (" << std::strerror(entry.error) << ')';
        }
        return out.str();
    }

    std::mutex m_mutex;
    const std::string m_cpuRoot, m_taskDir;
    std::vector<Entry> m_entries;
    std::vector<std::string> m_watched;
    std::vector<int> m_fastest;
    std::string m_criterion = "sin cambios";
    PlacementMode m_mode = PlacementMode::Automatic;
};

} // namespace rinoize
