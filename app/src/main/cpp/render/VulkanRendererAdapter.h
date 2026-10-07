#pragma once

#include "IRenderer.h"

namespace noir::render {

class VulkanRendererAdapter final : public IRenderer {
public:
    bool Initialize(ANativeWindow* window) noexcept override;
    bool BeginFrame() noexcept override;
    void EndFrame() noexcept override;
    void ClearColor(float r, float g, float b, float a) noexcept override;
    bool DrawMesh(const Mesh& mesh) noexcept override;
    bool RecreateSurface(ANativeWindow* window) noexcept override;
    void Shutdown() noexcept override;

private:
    ANativeWindow* window_ = nullptr; // borrowed
    bool ready_ = false;
};

} // namespace noir::render
