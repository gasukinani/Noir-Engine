#include "GlesRendererAdapter.h"

#include <GLES3/gl3.h>

namespace noir::render {

bool GlesRendererAdapter::Initialize(ANativeWindow* window) noexcept {
    // GLSurfaceView owns the EGL context. Initialization therefore happens on
    // its GL thread; this adapter never releases the borrowed ANativeWindow.
    window_ = window;
    ready_ = (window != nullptr);
    return ready_;
}

bool GlesRendererAdapter::BeginFrame() noexcept {
    return ready_;
}

void GlesRendererAdapter::EndFrame() noexcept {}

void GlesRendererAdapter::ClearColor(float r, float g, float b, float a) noexcept {
    if(!ready_) return;
    glClearColor(r,g,b,a);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
}

bool GlesRendererAdapter::DrawMesh(const Mesh& mesh) noexcept {
    return ready_ && mesh.valid();
}

bool GlesRendererAdapter::RecreateSurface(ANativeWindow* window) noexcept {
    window_ = window;
    return ready_ = (window != nullptr);
}

void GlesRendererAdapter::Shutdown() noexcept {
    // The adapter does not own the Android window. Only GPU-side objects created
    // by this backend are destroyed here in the full NoirGFX implementation.
    ready_ = false;
    window_ = nullptr;
}

} // namespace noir::render
