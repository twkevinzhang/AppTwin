#include "../../main/jni/A64Inlinehook/A64NearBranch.h"
#include <array>
#include <cassert>
#include <cstring>
#include <limits>
#include <atomic>
#include <thread>
int main() {
    std::uint32_t branch = 0;
    constexpr std::uintptr_t origin = 0x40000000u;
    assert(A64NearBranch::encode(origin, origin + 4, &branch) && branch == 0x14000001u);
    assert(A64NearBranch::encode(origin, origin - 4, &branch) && branch == 0x17ffffffu);
    assert(A64NearBranch::encode(origin, origin + 0x07fffffc, &branch) && branch == 0x15ffffffu);
    assert(A64NearBranch::encode(origin, origin - 0x08000000, &branch) && branch == 0x16000000u);
    assert(!A64NearBranch::encode(origin, origin + 0x08000000, &branch));
    assert(!A64NearBranch::encode(origin, origin - 0x08000004, &branch));
    assert(!A64NearBranch::encode(origin + 1, origin + 4, &branch));
    assert(!A64NearBranch::encode(origin, origin + 2, &branch));
    assert(!A64NearBranch::encode(std::numeric_limits<std::uintptr_t>::max() & ~std::uintptr_t(3), 0, &branch));
    for (std::size_t pageSize : {std::size_t(4096), std::size_t(16384)}) {
        for (unsigned attempt = 0; attempt < A64NearBranch::kMaximumAttempts; ++attempt) {
            std::uintptr_t address;
            assert(A64NearBranch::candidate(origin + 4, pageSize, attempt, &address));
            assert((address & (pageSize - 1)) == 0);
            assert(address != origin);
            assert(A64NearBranch::encode(origin + 4, address, &branch));
        }
        std::uintptr_t address;
        assert(!A64NearBranch::candidate(origin, pageSize, A64NearBranch::kMaximumAttempts, &address));
    }
    std::uintptr_t address;
    assert(!A64NearBranch::candidate(0, 4096, 1, &address));
    assert(!A64NearBranch::candidate(std::numeric_limits<std::uintptr_t>::max(), 4096, 0, &address));
    assert(!A64NearBranch::candidate(origin, 3, 0, &address));
    // Actual publication helper changes exactly one instruction at either entry alignment.
    for (std::size_t offset : {std::size_t(0), std::size_t(4)}) {
        alignas(8) std::array<std::uint32_t, 8> instructions;
        instructions.fill(0xd503201fu);
        std::uint32_t *entry = instructions.data() + offset / 4;
        const auto before = instructions;
        assert(!A64NearBranch::publish(entry, 0x12345678u, 0x14000000u));
        assert(instructions == before); // CAS failure leaves the entire original intact.
        assert(A64NearBranch::encode(reinterpret_cast<std::uintptr_t>(entry),
                                      reinterpret_cast<std::uintptr_t>(entry) + 4096, &branch));
        assert(A64NearBranch::publish(entry, 0xd503201fu, branch));
        for (std::size_t i = 0; i < instructions.size(); ++i) {
            if (&instructions[i] != entry) assert(instructions[i] == before[i]);
        }
        // An unreachable replacement is rejected before any entry publication.
        const auto after = instructions;
        assert(!A64NearBranch::encode(reinterpret_cast<std::uintptr_t>(entry),
                                       reinterpret_cast<std::uintptr_t>(entry) + 0x10000000u, &branch));
        assert(instructions == after);
    }
    alignas(8) std::uint32_t entry[4] = {0x14000001u, 0xd503201fu, 0xd503233fu, 0xd65f03c0u};
    std::atomic<bool> complete(false);
    std::thread reader([&] {
        while (!complete.load(std::memory_order_acquire)) {
            const auto observed = __atomic_load_n(entry, __ATOMIC_SEQ_CST);
            assert(observed == 0x14000001u || observed == 0x17ffffffu);
            assert(entry[1] == 0xd503201fu && entry[2] == 0xd503233fu && entry[3] == 0xd65f03c0u);
        }
    });
    for (unsigned i = 0; i < 100000; ++i) {
        auto expected = __atomic_load_n(entry, __ATOMIC_SEQ_CST);
        assert(A64NearBranch::publish(entry, expected, expected == 0x14000001u ? 0x17ffffffu : 0x14000001u));
    }
    complete.store(true, std::memory_order_release);
    reader.join();
}
