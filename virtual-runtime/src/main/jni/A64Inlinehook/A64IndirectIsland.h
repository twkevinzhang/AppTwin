#ifndef APPTWIN_A64_INDIRECT_ISLAND_H
#define APPTWIN_A64_INDIRECT_ISLAND_H
#include <cstdint>
#include <cstddef>
#include <cstring>
namespace A64IndirectIsland {
constexpr unsigned kSlotsPerPage = 128;
constexpr std::size_t kStubSize = 24;
inline void encode(void *stub, const std::uint64_t *targetSlot) {
    // LDR X17, literal(+16); LDAR X17,[X17]; BR X17; NOP; stable slot address.
    const std::uint32_t instructions[] = {0x58000091u, 0xc8dffe31u, 0xd61f0220u, 0xd503201fu};
    const std::uint64_t slot = reinterpret_cast<std::uintptr_t>(targetSlot);
    std::memcpy(stub, instructions, sizeof(instructions));
    std::memcpy(static_cast<unsigned char *>(stub) + sizeof(instructions), &slot, sizeof(slot));
}
// Callers serialize state transitions. Published slots are never rewritten or freed.
class Slots {
    enum State : unsigned char { Free, Reserved, Published };
    State state[kSlotsPerPage];
    std::uint64_t *targets;
public:
    Slots() : state{}, targets(nullptr) {}
    bool prepare(std::uint64_t *stableTargets) {
        if (!stableTargets || targets) return false;
        targets = stableTargets;
        for (unsigned i = 0; i < kSlotsPerPage; ++i) __atomic_store_n(targets + i, 0, __ATOMIC_RELEASE);
        return true;
    }
    bool reserve(unsigned index, std::uint64_t target) {
        if (!targets || index >= kSlotsPerPage || state[index] != Free || !target) return false;
        __atomic_store_n(targets + index, target, __ATOMIC_RELEASE);
        state[index] = Reserved; return true;
    }
    bool publish(unsigned index) {
        if (!targets || index >= kSlotsPerPage || state[index] != Reserved) return false;
        state[index] = Published; return true;
    }
    bool release(unsigned index) {
        if (!targets || index >= kSlotsPerPage || state[index] != Reserved) return false;
        __atomic_store_n(targets + index, 0, __ATOMIC_RELEASE);
        state[index] = Free; return true;
    }
    std::uint64_t load(unsigned index) const {
        return targets && index < kSlotsPerPage ? __atomic_load_n(targets + index, __ATOMIC_ACQUIRE) : 0;
    }
};
}
#endif
