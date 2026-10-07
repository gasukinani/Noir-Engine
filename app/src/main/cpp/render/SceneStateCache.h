#pragma once

#include <array>
#include <cstdint>
#include <cstring>

namespace noir::render {

constexpr std::uint32_t kMaxCachedSceneNodes = 4096;

struct alignas(16) NodeState {
    float model[16]{};
    std::uint32_t kind = 0;
    std::uint32_t visible = 1;
    std::uint32_t materialId = 0;
    std::uint32_t reserved = 0;
};

struct SceneStateCache {
    std::uint64_t revision = 0;
    float cameraViewProj[16]{};
    float cameraPosition[3]{};
    float exposure = 1.0f;
    std::uint32_t nodeCount = 0;
    std::array<NodeState, kMaxCachedSceneNodes> nodes{};

    void clear() noexcept {
        revision = 0;
        nodeCount = 0;
        exposure = 1.0f;
        std::memset(cameraViewProj, 0, sizeof(cameraViewProj));
        std::memset(cameraPosition, 0, sizeof(cameraPosition));
    }
};

} // namespace noir::render
