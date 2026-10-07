#pragma once

#include "ShaderAsset.h"
#include <vulkan/vulkan.h>

namespace noir::render {

bool CompileGLESShader(const ShaderAsset& asset, unsigned int glShaderHandle) noexcept;
bool CreateVulkanShaderModule(const ShaderAsset& asset, VkDevice device, VkShaderModule* outModule) noexcept;

} // namespace noir::render
