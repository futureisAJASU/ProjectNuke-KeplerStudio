#pragma once

#include <atomic>
#include <cstdint>
#include <memory>

namespace kepler_native {

constexpr int kCancelledBeforeStart = -7;
constexpr int kCancelledMidPass = -8;
constexpr int kCancelledBeforeCommit = -9;

std::int64_t registerCancellationToken() noexcept;
bool signalCancellation(std::int64_t token) noexcept;
bool releaseCancellationToken(std::int64_t token) noexcept;
bool isCancellationRequested(std::int64_t token) noexcept;
std::size_t activeCancellationTokenCount() noexcept;

class CancellationLease final {
public:
    explicit CancellationLease(std::int64_t token) noexcept;
    bool cancelled() const noexcept;
    std::int64_t token() const noexcept { return token_; }
private:
    std::int64_t token_;
    std::shared_ptr<std::atomic_bool> flag_;
    bool missing_;
};

}  // namespace kepler_native
