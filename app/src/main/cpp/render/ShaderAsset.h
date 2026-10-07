#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <utility>

namespace noir::render {

enum class ShaderStage : std::uint8_t {
    VERTEX,
    FRAGMENT,
    COMPUTE
};

class ShaderAsset final {
public:
    ShaderAsset() = default;
    ShaderAsset(ShaderStage stage, std::string name, std::string glslSource)
        : stage_(stage), name_(std::move(name)), glsl_(std::move(glslSource)) {}

    ShaderStage stage() const noexcept { return stage_; }
    const std::string& name() const noexcept { return name_; }
    const std::string& glsl() const noexcept { return glsl_; }
    const std::vector<std::uint32_t>& spirv() const noexcept { return spirv_; }

    void SetGLSL(std::string source) { glsl_ = std::move(source); }
    void SetSPIRV(std::vector<std::uint32_t> code) { spirv_ = std::move(code); }

    bool HasGLSL() const noexcept { return !glsl_.empty(); }
    bool HasSPIRV() const noexcept { return !spirv_.empty(); }

private:
    ShaderStage stage_ = ShaderStage::VERTEX;
    std::string name_;
    std::string glsl_;
    std::vector<std::uint32_t> spirv_;
};

} // namespace noir::render
