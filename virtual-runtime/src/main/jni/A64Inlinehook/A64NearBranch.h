#ifndef APPTWIN_A64_NEAR_BRANCH_H
#define APPTWIN_A64_NEAR_BRANCH_H
#include <cstdint>
#include <cstddef>
#include <limits>
#include <vector>
#include <algorithm>
namespace A64NearBranch {
constexpr unsigned kMaximumAttempts = 64;
constexpr unsigned kMaximumPages = 64;
constexpr std::uint64_t kAllocationDeadlineNs = 50000000ULL;
inline bool encode(std::uintptr_t origin, std::uintptr_t target, std::uint32_t *instruction) {
    if (!instruction || ((origin | target) & 3u)) return false;
    std::int64_t displacement;
    if (target >= origin) {
        std::uintptr_t distance = target - origin;
        if (distance > 0x07fffffcu) return false;
        displacement = static_cast<std::int64_t>(distance);
    } else {
        std::uintptr_t distance = origin - target;
        if (distance > 0x08000000u) return false;
        displacement = -static_cast<std::int64_t>(distance);
    }
    *instruction = 0x14000000u | (static_cast<std::uint32_t>(displacement / 4) & 0x03ffffffu);
    return true;
}
constexpr std::size_t kMaximumMapBytes = 256u * 1024u;
constexpr std::size_t kMaximumMapRanges = 4096u;
struct MapRange {
    std::uintptr_t start, end;
    MapRange(std::uintptr_t low, std::uintptr_t high) : start(low), end(high) {}
};
inline bool hexAddress(const char *text, std::size_t end, std::size_t *position, std::uintptr_t *value) {
    if (!position || !value) return false;
    std::uintptr_t parsed = 0;
    unsigned digits = 0;
    while (*position < end) {
        char ch = text[*position];
        unsigned nibble;
        if (ch >= '0' && ch <= '9') nibble = ch - '0';
        else if (ch >= 'a' && ch <= 'f') nibble = ch - 'a' + 10;
        else if (ch >= 'A' && ch <= 'F') nibble = ch - 'A' + 10;
        else break;
        if (++digits > sizeof(std::uintptr_t) * 2) return false;
        parsed = (parsed << 4) | nibble;
        ++*position;
    }
    if (!digits) return false;
    *value = parsed; return true;
}
// Only parse address intervals; permissions, labels and filesystem paths are ignored.
inline bool parseMaps(const char *text, std::size_t bytes, std::vector<MapRange> *ranges) {
    if (!text || !ranges || !bytes || bytes > kMaximumMapBytes) return false;
    ranges->clear();
    std::size_t position = 0;
    while (position < bytes) {
        if (ranges->size() >= kMaximumMapRanges) return false;
        std::size_t end = position;
        while (end < bytes && text[end] != '\n') ++end;
        if (end == bytes) return false; // Never use a partial proc maps snapshot.
        std::uintptr_t low, high;
        if (!hexAddress(text, end, &position, &low) || position >= end || text[position++] != '-' ||
            !hexAddress(text, end, &position, &high) || position >= end || text[position] != ' ' ||
            low >= high || (!ranges->empty() && low < ranges->back().end)) return false;
        ranges->push_back(MapRange(low, high));
        position = end + 1;
    }
    return !ranges->empty();
}
inline bool pageInGap(std::uintptr_t origin, std::size_t pageSize,
                      std::uintptr_t start, std::uintptr_t end, std::uintptr_t *address) {
    if (!address || !pageSize || (pageSize & (pageSize - 1)) || end < pageSize || start >= end) return false;
    const std::uintptr_t maximum = std::numeric_limits<std::uintptr_t>::max();
    std::uintptr_t windowLow = origin >= 0x08000000u ? origin - 0x08000000u : 0;
    std::uintptr_t windowHigh = origin <= maximum - 0x07fffffcu ? origin + 0x07fffffcu : maximum;
    std::uintptr_t low = std::max(start, windowLow);
    std::uintptr_t high = std::min(end - pageSize, windowHigh);
    if (low > maximum - (pageSize - 1)) return false;
    low = (low + pageSize - 1) & ~(static_cast<std::uintptr_t>(pageSize) - 1);
    high &= ~(static_cast<std::uintptr_t>(pageSize) - 1);
    if (!low || low > high) return false;
    std::uintptr_t nearest = origin & ~(static_cast<std::uintptr_t>(pageSize) - 1);
    *address = std::max(low, std::min(high, nearest));
    std::uint32_t ignored;
    return encode(origin, *address, &ignored);
}
inline bool gapCandidates(const std::vector<MapRange> &ranges, std::uintptr_t origin,
                          std::size_t pageSize, std::vector<std::uintptr_t> *addresses) {
    if (!addresses || ranges.empty() || ranges.size() > kMaximumMapRanges) return false;
    addresses->clear();
    std::uintptr_t previous = 0;
    for (const auto &range : ranges) {
        std::uintptr_t address;
        if (pageInGap(origin, pageSize, previous, range.start, &address)) addresses->push_back(address);
        previous = range.end;
    }
    std::uintptr_t address;
    if (pageInGap(origin, pageSize, previous, std::numeric_limits<std::uintptr_t>::max(), &address)) addresses->push_back(address);
    std::sort(addresses->begin(), addresses->end(), [origin](std::uintptr_t a, std::uintptr_t b) {
        const auto aDistance = a >= origin ? a - origin : origin - a;
        const auto bDistance = b >= origin ? b - origin : origin - b;
        return aDistance < bDistance;
    });
    return !addresses->empty();
}
// No allocation candidate ever replaces the origin page. Probe close gaps first,
// then spread across the branch window. The caller must verify mmap's result.
inline bool candidate(std::uintptr_t origin, std::size_t pageSize, unsigned attempt,
                      std::uintptr_t *address) {
    if (!address || !pageSize || (pageSize & (pageSize - 1)) || attempt >= kMaximumAttempts) return false;
    std::uintptr_t base = origin & ~(static_cast<std::uintptr_t>(pageSize) - 1);
    unsigned pair = attempt / 2;
    std::uintptr_t distance = pair < 8 ? static_cast<std::uintptr_t>(pair + 1) * 65536u
                                    : static_cast<std::uintptr_t>(pair - 7) * 4194304u;
    if (attempt & 1u) {
        if (base < distance) return false;
        *address = base - distance;
    } else {
        if (base > std::numeric_limits<std::uintptr_t>::max() - distance) return false;
        *address = base + distance;
    }
    std::uint32_t ignored;
    return encode(origin, *address, &ignored);
}
// Publish exactly one aligned instruction; an unexpected entry is never overwritten.
inline bool publish(std::uint32_t *entry, std::uint32_t expected, std::uint32_t branch) {
    if (!entry || (reinterpret_cast<std::uintptr_t>(entry) & 3u)) return false;
    return __atomic_compare_exchange_n(entry, &expected, branch, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}
}
#endif
