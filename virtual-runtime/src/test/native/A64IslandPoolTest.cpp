#include "../../main/jni/A64Inlinehook/A64NearBranch.h"
#include "../../main/jni/A64Inlinehook/A64IndirectIsland.h"
#include <array>
#include <cassert>
#include <sstream>
#include <string>
#include <cstring>
int main() {
    const std::uintptr_t base = 0x7000000000ULL;
    const std::uintptr_t origin = base + 0x10000;
    const std::uintptr_t offsets[] = {113627136, 128303104, 129441792, 129732608};
    const std::uintptr_t sizes[] = {20480, 73728, 36864, 36864};
    std::ostringstream maps;
    auto line = [&](std::uintptr_t low, std::uintptr_t high) {
        maps << std::hex << low << '-' << high << " r-xp ignored-label\n";
    };
    std::uintptr_t previous = base - 0x10000000;
    for (unsigned i = 0; i < 4; ++i) {
        line(previous, base + offsets[i]);
        previous = base + offsets[i] + sizes[i];
    }
    line(previous, base + 0x10000000);
    const std::string text = maps.str();
    std::vector<A64NearBranch::MapRange> ranges;
    assert(A64NearBranch::parseMaps(text.data(), text.size(), &ranges));
    std::vector<std::uintptr_t> candidates;
    assert(A64NearBranch::gapCandidates(ranges, origin, 4096, &candidates));
    assert(candidates.size() == 4);
    assert(candidates[0] == base + offsets[0]); // Actual 113 MB gap missed by old sparse plan.
    for (unsigned i = 0; i < 4; ++i) {
        assert(candidates[i] >= base + offsets[i]);
        assert(candidates[i] + 4096 <= base + offsets[i] + sizes[i]);
        std::uint32_t branch;
        assert(A64NearBranch::encode(origin, candidates[i], &branch));
    }
    std::uintptr_t candidate;
    assert(!A64NearBranch::pageInGap(origin, 4096, origin + 0x08000000,
                                    origin + 0x08000000 + 8192, &candidate));
    assert(!A64NearBranch::pageInGap(origin, 4096, origin + 0x1000, origin + 0x1800, &candidate));
    for (const std::string invalid : {"x-y r\n", "2000-1000 r\n", "1000-2000 r\n1800-3000 r\n",
                                     "1000-2000 r", "10000000000000000-10000000000001000 r\n"}) {
        assert(!A64NearBranch::parseMaps(invalid.data(), invalid.size(), &ranges));
    }
    const std::string tooLarge(A64NearBranch::kMaximumMapBytes + 1, 'x');
    assert(!A64NearBranch::parseMaps(tooLarge.data(), tooLarge.size(), &ranges));
    std::ostringstream tooMany;
    for (unsigned i = 0; i <= A64NearBranch::kMaximumMapRanges; ++i)
        tooMany << std::hex << (0x100000u + i * 8192u) << '-' << (0x101000u + i * 8192u) << " r\n";
    const std::string excess = tooMany.str();
    assert(excess.size() < A64NearBranch::kMaximumMapBytes);
    assert(!A64NearBranch::parseMaps(excess.data(), excess.size(), &ranges));

    alignas(8) std::array<std::uint64_t, A64IndirectIsland::kSlotsPerPage> targets = {};
    std::array<unsigned char, A64IndirectIsland::kSlotsPerPage * A64IndirectIsland::kStubSize> code = {};
    for (unsigned i = 0; i < A64IndirectIsland::kSlotsPerPage; ++i)
        A64IndirectIsland::encode(code.data() + i * A64IndirectIsland::kStubSize, &targets[i]);
    const auto immutable = code; // Production performs RW->RX here, then never re-encodes.
    std::uint32_t load, acquire, branch;
    std::uint64_t slotPointer;
    std::memcpy(&load, code.data(), 4); std::memcpy(&acquire, code.data() + 4, 4);
    std::memcpy(&branch, code.data() + 8, 4); std::memcpy(&slotPointer, code.data() + 16, 8);
    assert(load == 0x58000091u && acquire == 0xc8dffe31u && branch == 0xd61f0220u);
    assert(slotPointer == reinterpret_cast<std::uintptr_t>(&targets[0]));
    A64IndirectIsland::Slots slots;
    assert(slots.prepare(targets.data()));
    assert(!slots.prepare(targets.data()));
    assert(slots.reserve(0, 0x123456789abcdef0ULL));
    alignas(4) std::uint32_t entry[4] = {0xd503233fu, 0xd503201fu, 0xd503201fu, 0xd65f03c0u};
    assert(A64NearBranch::publish(entry, 0xd503233fu, 0x14000001u));
    assert(slots.publish(0));
    assert(!slots.release(0));
    assert(!slots.reserve(0, 0x11111111));
    assert(slots.load(0) == 0x123456789abcdef0ULL);
    assert(code == immutable);
    assert(slots.reserve(1, 0x22222222));
    const std::uint32_t prior = entry[0];
    assert(!A64NearBranch::publish(entry, 0xdeadbeefu, 0x14000002u));
    assert(slots.release(1));
    assert(slots.load(1) == 0 && slots.load(0) == 0x123456789abcdef0ULL);
    assert(entry[0] == prior && code == immutable);
    for (unsigned i = 1; i < A64IndirectIsland::kSlotsPerPage; ++i) {
        assert(slots.reserve(i, 0x10000 + i)); assert(slots.publish(i));
    }
    assert(!slots.reserve(A64IndirectIsland::kSlotsPerPage, 0x7777));
    assert(!slots.release(A64IndirectIsland::kSlotsPerPage));
    assert(code == immutable);
}
