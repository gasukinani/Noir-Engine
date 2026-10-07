#pragma once

#include <android/native_window.h>
#include <cstdint>
#include <memory>
#include <mutex>

#include "GraphicsAPI.h"
#include "IRenderer.h"
#include "SceneStateCache.h"

namespace noir::render {

class RenderManager final {
public:
    static RenderManager& Instance() noexcept;

    // Called from Java/UI threads. This only records the requested transition;
    // the render thread applies it so GL/Vulkan objects are never destroyed from
    // the Android main thread.
    bool SwitchGraphicsAPI(GraphicsAPI newAPI, ANativeWindow* window) noexcept;

    // Called by the active render thread between frames.
    bool ApplyPendingSwitch() noexcept;

    bool IsSwitchPending() const noexcept;
    GraphicsAPI RequestedAPI() const noexcept;
    GraphicsAPI ActiveAPI() const noexcept;
    bool IsPausedForSwitch() const noexcept;

    void CaptureSceneState(const SceneStateCache& state) noexcept;
    const SceneStateCache& SceneState() const noexcept;

    void AttachRenderer(std::unique_ptr<IRenderer> renderer,
                        GraphicsAPI api) noexcept;

private:
    RenderManager() = default;
    ~RenderManager() = default;
    RenderManager(const RenderManager&) = delete;
    RenderManager& operator=(const RenderManager&) = delete;

    std::unique_ptr<IRenderer> CreateRendererLocked(GraphicsAPI api) noexcept;

    mutable std::mutex mutex_;
    std::unique_ptr<IRenderer> activeRenderer_;
    SceneStateCache sceneCache_{};
    ANativeWindow* pendingWindow_ = nullptr;
    GraphicsAPI activeAPI_ = GraphicsAPI::OPENGL_ES;
    GraphicsAPI requestedAPI_ = GraphicsAPI::OPENGL_ES;
    bool pendingSwitch_ = false;
    bool pausedForSwitch_ = false;
    std::uint64_t requestSerial_ = 0;
};

} // namespace noir::render
