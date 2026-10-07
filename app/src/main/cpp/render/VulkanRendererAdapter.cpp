#include "VulkanRendererAdapter.h"

#include <vulkan/vulkan.h>
#include <algorithm>

namespace noir::render {

bool VulkanRendererAdapter::Initialize(ANativeWindow* window) noexcept {
    // The full Vulkan presentation surface is owned by NoirVulkanSurface.
    // This control-plane adapter deliberately does not destroy or retain an
    // ownership reference to ANativeWindow.
    window_ = window;
    ready_ = (window != nullptr);
    return ready_;
}

bool VulkanRendererAdapter::BeginFrame() noexcept {
    return ready_;
}

void VulkanRendererAdapter::EndFrame() noexcept {}

void VulkanRendererAdapter::ClearColor(float, float, float, float) noexcept {
    // Clear is performed by the concrete Vulkan command buffer path.
}

bool VulkanRendererAdapter::DrawMesh(const Mesh& mesh) noexcept {
    return ready_ && mesh.valid();
}

bool VulkanRendererAdapter::RecreateSurface(ANativeWindow* window) noexcept {
    window_ = window;
    return ready_ = (window != nullptr);
}

void VulkanRendererAdapter::Shutdown() noexcept {
    ready_ = false;
    window_ = nullptr;
}

} // namespace noir::render
