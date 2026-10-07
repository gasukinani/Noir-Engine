#pragma once

namespace noir::gfx {

class Renderer {
public:
    Renderer();
    ~Renderer();

    bool initialize();
    void shutdown();
    void resize(int width, int height);
    void frame(float yawDeg, float pitchDeg, float distance,
               float targetX, float targetY, float targetZ,
               bool editorMode);
    float frameTimeMs() const;
    const char* backendInfo() const;

private:
    struct Impl;
    Impl* impl_;
};

} // namespace noir::gfx
