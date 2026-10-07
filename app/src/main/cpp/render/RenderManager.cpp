#include "RenderManager.h"

#include "GlesRendererAdapter.h"
#include "VulkanRendererAdapter.h"

namespace noir::render {

RenderManager& RenderManager::Instance() noexcept {
    static RenderManager manager;
    return manager;
}

bool RenderManager::SwitchGraphicsAPI(GraphicsAPI newAPI, ANativeWindow* window) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if(newAPI == activeAPI_ && !pendingSwitch_) return true;
    requestedAPI_ = newAPI;
    pendingWindow_ = window;
    pendingSwitch_ = true;
    pausedForSwitch_ = true;
    ++requestSerial_;
    return true;
}

bool RenderManager::ApplyPendingSwitch() noexcept {
    std::unique_ptr<IRenderer> oldRenderer;
    GraphicsAPI requested;
    GraphicsAPI previous;
    std::uint64_t serial;
    ANativeWindow* window = nullptr;

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if(!pendingSwitch_) {
            pausedForSwitch_ = false;
            return true;
        }
        requested = requestedAPI_;
        previous = activeAPI_;
        serial = requestSerial_;
        window = pendingWindow_;
        oldRenderer = std::move(activeRenderer_);
    }

    // IMPORTANT: this function is executed on the graphics/render thread.
    // That makes GL object deletion and Vulkan device synchronization legal.
    if(oldRenderer) {
        oldRenderer->Shutdown();
    }

    auto replacement = CreateRendererLocked(requested);
    if(!replacement) {
        std::lock_guard<std::mutex> lock(mutex_);
        pendingSwitch_ = false;
        pausedForSwitch_ = false;
        return false;
    }

    if(!replacement->Initialize(window)) {
        replacement->Shutdown();
        // Rebuild the old API rather than leaving the editor with no renderer.
        auto fallback = CreateRendererLocked(previous);
        bool ok = fallback && fallback->Initialize(window);
        std::lock_guard<std::mutex> lock(mutex_);
        activeRenderer_ = std::move(fallback);
        pendingSwitch_ = false;
        pausedForSwitch_ = false;
        return ok;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        activeRenderer_ = std::move(replacement);
        activeAPI_ = requested;
        if(requestSerial_ == serial){
            pendingSwitch_ = false;
            pausedForSwitch_ = false;
        }
        // A newer UI request remains pending and will be applied on the next render tick.
    }
    return true;
}

std::unique_ptr<IRenderer> RenderManager::CreateRendererLocked(GraphicsAPI api) noexcept {
    if(api == GraphicsAPI::VULKAN) {
        return std::unique_ptr<IRenderer>(new VulkanRendererAdapter());
    }
    return std::unique_ptr<IRenderer>(new GlesRendererAdapter());
}

bool RenderManager::IsSwitchPending() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return pendingSwitch_;
}

GraphicsAPI RenderManager::RequestedAPI() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return requestedAPI_;
}

GraphicsAPI RenderManager::ActiveAPI() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return activeAPI_;
}

bool RenderManager::IsPausedForSwitch() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return pausedForSwitch_;
}

void RenderManager::CaptureSceneState(const SceneStateCache& state) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    sceneCache_ = state;
}

const SceneStateCache& RenderManager::SceneState() const noexcept {
    return sceneCache_;
}

void RenderManager::AttachRenderer(std::unique_ptr<IRenderer> renderer,
                                   GraphicsAPI api) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    activeRenderer_ = std::move(renderer);
    activeAPI_ = api;
    requestedAPI_ = api;
    pendingSwitch_ = false;
    pausedForSwitch_ = false;
}

} // namespace noir::render
