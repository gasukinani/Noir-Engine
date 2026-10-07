#pragma once

#include <cstdint>
#include <cstddef>

namespace noir::render {

struct alignas(16) Vertex {
    float px, py, pz;
    float nx, ny, nz;
    float u, v;
};

struct Mesh {
    const Vertex* vertices = nullptr;
    std::uint32_t vertexCount = 0;
    const std::uint32_t* indices = nullptr;
    std::uint32_t indexCount = 0;
    std::uint32_t materialId = 0;

    constexpr bool valid() const noexcept {
        return vertices && vertexCount > 0;
    }
};

} // namespace noir::render
