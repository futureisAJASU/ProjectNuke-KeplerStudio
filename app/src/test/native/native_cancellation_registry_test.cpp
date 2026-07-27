#include "native_cancellation.h"

#include <atomic>
#include <cstdint>
#include <iostream>
#include <thread>
#include <unordered_set>
#include <vector>

int main() {
    std::unordered_set<std::int64_t> active;
    for (int index = 0; index < 1000; ++index) {
        const auto token = kepler_native::registerCancellationToken();
        if (token <= 0 || !active.insert(token).second) return 1;
        if (!kepler_native::signalCancellation(token)) return 2;
        kepler_native::CancellationLease lease(token);
        if (!lease.cancelled()) return 3;
        if (!kepler_native::releaseCancellationToken(token)) return 4;
        active.erase(token);
    }
    if (kepler_native::activeCancellationTokenCount() != 0U) return 5;

    const auto oldToken = kepler_native::registerCancellationToken();
    if (!kepler_native::releaseCancellationToken(oldToken)) return 6;
    const auto newToken = kepler_native::registerCancellationToken();
    if (newToken <= oldToken || kepler_native::signalCancellation(oldToken)) return 7;
    if (kepler_native::isCancellationRequested(newToken)) return 8;
    if (!kepler_native::releaseCancellationToken(newToken)) return 9;

    for (int iteration = 0; iteration < 500; ++iteration) {
        const auto token = kepler_native::registerCancellationToken();
        std::atomic_bool start{false};
        std::atomic_int releaseSuccesses{0};
        std::vector<std::thread> workers;
        for (int worker = 0; worker < 4; ++worker) {
            workers.emplace_back([&, worker]() {
                while (!start.load(std::memory_order_acquire)) {
                    std::this_thread::yield();
                }
                if ((worker & 1) == 0) {
                    for (int signal = 0; signal < 16; ++signal) {
                        kepler_native::signalCancellation(token);
                    }
                } else if (kepler_native::releaseCancellationToken(token)) {
                    releaseSuccesses.fetch_add(1, std::memory_order_relaxed);
                }
            });
        }
        start.store(true, std::memory_order_release);
        for (auto& worker : workers) worker.join();
        if (releaseSuccesses.load(std::memory_order_relaxed) > 1) return 10;
        if (releaseSuccesses.load(std::memory_order_relaxed) == 0 &&
            !kepler_native::releaseCancellationToken(token)) {
            return 11;
        }
    }
    if (kepler_native::activeCancellationTokenCount() != 0U) return 12;
    std::cout << "native cancellation registry stress passed\n";
    return 0;
}
