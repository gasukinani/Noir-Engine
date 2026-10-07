#pragma once

#include <android/native_window.h>
#include "Mesh.h"

namespace noir::render {

class IRenderer {
public:
    virtual ~IRenderer() = default;

    virtual bool Initialize(ANativeWindow* window) noexcept = 0;
    virtual bool BeginFrame() noexcept = 0;
    virtual void EndFrame() noexcept = 0;
    virtual void ClearColor(float r, float g, float b, float a) noexcept = 0;
    virtual bool DrawMesh(const Mesh& mesh) noexcept = 0;
    virtual bool RecreateSurface(ANativeWindow* window) noexcept = 0;
    virtual void Shutdown() noexcept = 0;
};

} // namespace noir::render
