#pragma once

namespace noir::gfx {

class Renderer {
public:
    Renderer();
    ~Renderer();

    bool initialize();
    void shutdown();
    void resize(int width, int height);
    void setScene(const float* snapshot, int floatCount);
    void setEnvironment(int skyMode, float exposure, float skyBrightness, float fogDensity,
                        float sunX, float sunY, float sunZ);
    void setQuality(int qualityTier);
    void frame(float yawDeg, float pitchDeg, float distance,
               float targetX, float targetY, float targetZ,
               bool editorMode);
    void frameRuntime(float x,float y,float z,float yawDeg,float pitchDeg,bool editorMode);
    float frameTimeMs() const;
    const char* backendInfo() const;
    const char* lastError() const;
    bool safeMode() const;

private:
    struct Impl;
    Impl* impl_;
};

} // namespace noir::gfx
