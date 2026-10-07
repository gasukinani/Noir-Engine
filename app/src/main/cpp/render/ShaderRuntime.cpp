#include "ShaderRuntime.h"

#include <GLES3/gl3.h>
#include <vulkan/vulkan.h>

namespace noir::render {

bool CompileGLESShader(const ShaderAsset& asset, unsigned int glShaderHandle) noexcept {
    if(!asset.HasGLSL() || glShaderHandle == 0) return false;
    const char* source = asset.glsl().c_str();
    GLint length = static_cast<GLint>(asset.glsl().size());
    glShaderSource(static_cast<GLuint>(glShaderHandle), 1, &source, &length);
    glCompileShader(static_cast<GLuint>(glShaderHandle));
    GLint ok = GL_FALSE;
    glGetShaderiv(static_cast<GLuint>(glShaderHandle), GL_COMPILE_STATUS, &ok);
    return ok == GL_TRUE;
}

bool CreateVulkanShaderModule(const ShaderAsset& asset, VkDevice device, VkShaderModule* moduleOut) noexcept {
    if(!asset.HasSPIRV() || device == VK_NULL_HANDLE || moduleOut == nullptr) return false;
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = asset.spirv().size() * sizeof(std::uint32_t);
    ci.pCode = asset.spirv().data();
    return vkCreateShaderModule(device, &ci, nullptr, moduleOut) == VK_SUCCESS;
}

} // namespace noir::render
