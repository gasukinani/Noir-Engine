#pragma once

#include <cstdint>

namespace noir::render {

enum class GraphicsAPI : std::uint8_t {
    OPENGL_ES = 0,
    VULKAN = 1
};

constexpr const char* toString(GraphicsAPI api) noexcept {
    return api == GraphicsAPI::VULKAN ? "VULKAN" : "OPENGL_ES";
}

} // namespace noir::render
