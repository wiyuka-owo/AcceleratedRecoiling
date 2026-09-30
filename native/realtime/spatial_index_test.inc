struct TestSpatialIndex {
    std::vector<std::uint64_t> storage;
    std::vector<std::uint64_t> coincident;

    TestSpatialIndex(const Input& input, const ar::Query& query) {
        constexpr int cells = 32;
        const int words = (input.stride + 63) / 64;
        storage.resize(sizeof(ar::SpatialIndex) / sizeof(std::uint64_t) + (6 * cells + 4) * words);
        coincident.resize(words);
        auto* index = reinterpret_cast<ar::SpatialIndex*>(storage.data());
        index->wordCount = words;
        index->cellCount = cells;
        index->cellShift = 5;
        auto* rows = reinterpret_cast<std::uint64_t*>(index + 1);

        for (int slot = 0; slot < input.count; ++slot) {
            double box[6];
            bool finite = true;
            for (int field = 0; field < 6; ++field) {
                box[field] = input.doubles[field * input.stride + slot];
                finite &= std::isfinite(box[field]);
            }
            std::int32_t bounds[6];
            ar::quantizeBox(box, bounds);
            const int word = slot / 64;
            const auto bit = std::uint64_t(1) << (slot % 64);
            for (int field = 0; field < 6; ++field) {
                const int cell = index->cellIndex(bounds[field], field % 3);
                rows[(field * cells + cell) * words + word] |= bit;
            }
            if (!finite) {
                rows[(6 * cells + 1) * words + word] |= bit;
            }

            const double x = input.doubles[ar::POS_X * input.stride + slot];
            const double z = input.doubles[ar::POS_Z * input.stride + slot];
            const double flag = input.doubles[ar::FLAGS * input.stride + slot];
            if (flag < 0 || (flag != 0 && (!std::isfinite(x) || !std::isfinite(z)))) {
                rows[(6 * cells + 3) * words + word] |= bit;
            } else if (flag != 0) {
                rows[(6 * cells + 2) * words + word] |= bit;
            }
            if (x == query.x && z == query.z) {
                coincident[word] |= bit;
            }
        }

        for (int field = 0; field < 6; ++field) {
            for (int cell = 1; cell < cells; ++cell) {
                for (int word = 0; word < words; ++word) {
                    rows[(field * cells + cell) * words + word] |= rows[(field * cells + cell - 1) * words + word];
                }
            }
        }
    }

    void attach(ar::Section& section) const {
        section.spatial = reinterpret_cast<const ar::SpatialIndex*>(storage.data());
        section.coincident = coincident.data();
    }
};
