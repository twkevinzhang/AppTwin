#ifndef APPTWIN_A64_NEAR_BRANCH_H
#define APPTWIN_A64_NEAR_BRANCH_H
#include <cstdint>
#include <cstddef>
#include <limits>
#include <vector>
#include <algorithm>
#include <cerrno>
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
constexpr std::size_t kMaximumMapBytes = 1024u * 1024u;
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
struct MapParseMetadata {
    const char *reason = "none";
    std::size_t line = 0;
    std::size_t offset = 0;
    std::size_t lineBytes = 0;
    std::size_t headerBytes = 0;
    char permissions[5] = "????";
    bool emptyNextSameStart = false;
    bool emptyNextNonempty = false;
    bool emptyNextPadding = false;
    unsigned emptyPairs = 0;
};
// Parse occupied intervals without retaining labels or paths. Permissions only
// identify the exact empty-data/PROT_NONE-padding pair emitted by Android kernels.
inline bool parseMaps(const char *text, std::size_t bytes, std::vector<MapRange> *ranges,
                      MapParseMetadata *metadata = nullptr) {
    MapParseMetadata local;
    if (!metadata) metadata = &local;
    *metadata = MapParseMetadata{};
    auto fail = [metadata](const char *reason, std::size_t offset) {
        metadata->reason = reason; metadata->offset = offset; return false;
    };
    if (!text || !ranges || !bytes || bytes > kMaximumMapBytes) return fail("input", 0);
    ranges->clear();
    std::size_t position = 0;
    while (position < bytes) {
        ++metadata->line;
        const std::size_t lineStart = position;
        metadata->headerBytes = 0;
        for (unsigned i = 0; i < 4; ++i) metadata->permissions[i] = '?';
        metadata->emptyNextSameStart = false;
        metadata->emptyNextNonempty = false;
        metadata->emptyNextPadding = false;
        if (metadata->line > kMaximumMapRanges) return fail("range-limit", position);
        std::size_t end = position;
        while (end < bytes && text[end] != '\n') ++end;
        metadata->lineBytes = end - lineStart;
        if (end == bytes) return fail("partial-line", position);
        std::uintptr_t low, high;
        if (!hexAddress(text, end, &position, &low)) return fail("low-address", position);
        if (position >= end || text[position++] != '-') return fail("separator", position);
        if (!hexAddress(text, end, &position, &high)) return fail("high-address", position);
        if (position >= end || text[position] != ' ') return fail("address-suffix", position);
        metadata->headerBytes = position - lineStart;
        // Emit only a validated four-character permission field, never arbitrary label text.
        if (end - position >= 6 && text[position + 5] == ' ' &&
            (text[position + 1] == 'r' || text[position + 1] == '-') &&
            (text[position + 2] == 'w' || text[position + 2] == '-') &&
            (text[position + 3] == 'x' || text[position + 3] == '-') &&
            (text[position + 4] == 's' || text[position + 4] == 'p')) {
            for (unsigned i = 0; i < 4; ++i) metadata->permissions[i] = text[position + 1 + i];
        }
        if (low == high) {
            // Android 4 KiB page-size migration can print a fully padded data VMA
            // as an empty row followed by its actual PROT_NONE padding interval.
            // Accept only that exact ordered pair; parse the padding normally next,
            // so it remains occupied and can never become an allocation gap.
            std::size_t next = end + 1;
            std::size_t nextEnd = next;
            while (nextEnd < bytes && text[nextEnd] != '\n') ++nextEnd;
            std::uintptr_t nextLow, nextHigh;
            if (nextEnd < bytes && hexAddress(text, nextEnd, &next, &nextLow) &&
                next < nextEnd && text[next++] == '-' &&
                hexAddress(text, nextEnd, &next, &nextHigh) &&
                next < nextEnd && text[next] == ' ') {
                metadata->emptyNextSameStart = nextLow == low;
                metadata->emptyNextNonempty = nextHigh > nextLow;
                metadata->emptyNextPadding = nextEnd - next >= 6 &&
                    text[next + 1] == '-' && text[next + 2] == '-' &&
                    text[next + 3] == '-' && text[next + 4] == 'p' && text[next + 5] == ' ';
            }
            if (metadata->permissions[0] != '?' &&
                metadata->emptyNextSameStart && metadata->emptyNextNonempty && metadata->emptyNextPadding &&
                (ranges->empty() || low >= ranges->back().end)) {
                ++metadata->emptyPairs;
                position = end + 1;
                continue;
            }
            return fail("empty-range", position);
        }
        if (low > high) return fail("reversed-range", position);
        if (!ranges->empty() && low < ranges->back().end) return fail("overlap-order", position);
        ranges->push_back(MapRange(low, high));
        position = end + 1;
    }
    return !ranges->empty();
}
struct MapReadMetadata {
    std::size_t bytes = 0;
    std::size_t ranges = 0;
    unsigned reads = 0;
    MapParseMetadata parse;
};
// Read a complete bounded snapshot. The one-byte probe distinguishes exact-cap EOF
// from an over-cap file; it counts against the same read and time budgets.
template <typename Reader, typename Clock>
inline bool readMaps(Reader reader, Clock clock, std::uint64_t started,
                     std::vector<MapRange> *ranges, const char **reason, int *error,
                     MapReadMetadata *metadata) {
    std::vector<char> text(kMaximumMapBytes);
    *metadata = MapReadMetadata{};
    *error = 0;
    *reason = "maps-read-quota";
    for (; metadata->reads < 128;) {
        const auto now = clock();
        if (!now || now < started || now - started >= kAllocationDeadlineNs) {
            *reason = "deadline"; return false;
        }
        char probe;
        const bool full = metadata->bytes == text.size();
        char *buffer = full ? &probe : text.data() + metadata->bytes;
        const std::size_t capacity = full ? 1 : text.size() - metadata->bytes;
        int readError = 0;
        ++metadata->reads;
        const std::ptrdiff_t count = reader(buffer, capacity, &readError);
        if (count < 0) {
            if (readError == EINTR) continue;
            *error = readError; *reason = "maps-read"; return false;
        }
        if (count == 0) {
            const bool parsed = parseMaps(text.data(), metadata->bytes, ranges, &metadata->parse);
            metadata->ranges = ranges->size();
            if (!parsed) *reason = "maps-format-limit";
            return parsed;
        }
        if (full) { *reason = "maps-byte-limit"; return false; }
        metadata->bytes += static_cast<std::size_t>(count);
    }
    return false;
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
