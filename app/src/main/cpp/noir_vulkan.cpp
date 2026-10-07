#include <jni.h>
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <algorithm>

namespace {
constexpr const char* TAG="NoirVulkan";

struct Runtime {
    VkInstance instance=VK_NULL_HANDLE;
    VkPhysicalDevice physical=VK_NULL_HANDLE;
    VkDevice device=VK_NULL_HANDLE;
    VkQueue graphics=VK_NULL_HANDLE;
    VkQueue present=VK_NULL_HANDLE;
    uint32_t graphicsFamily=UINT32_MAX;
    uint32_t presentFamily=UINT32_MAX;
    VkSurfaceKHR surface=VK_NULL_HANDLE;
    VkSwapchainKHR swapchain=VK_NULL_HANDLE;
    VkFormat format=VK_FORMAT_B8G8R8A8_UNORM;
    VkExtent2D extent{1,1};
    VkRenderPass renderPass=VK_NULL_HANDLE;
    VkCommandPool commandPool=VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer=VK_NULL_HANDLE;
    VkSemaphore imageAvailable=VK_NULL_HANDLE;
    VkSemaphore renderFinished=VK_NULL_HANDLE;
    VkFence inFlight=VK_NULL_HANDLE;
    VkImage images[8]{};
    VkImageView views[8]{};
    VkFramebuffer framebuffers[8]{};
    uint32_t imageCount=0;
    VkPhysicalDeviceProperties props{};
    uint32_t apiVersion=VK_API_VERSION_1_0;
    ANativeWindow* window=nullptr;
    bool initialized=false;
    bool surfaceReady=false;
};
Runtime g;

bool hasInstanceExt(const char* n){
    uint32_t c=0;if(vkEnumerateInstanceExtensionProperties(nullptr,&c,nullptr)!=VK_SUCCESS)return false;
    VkExtensionProperties* e=new VkExtensionProperties[c?c:1];
    if(c==0){delete[] e;return false;}
    vkEnumerateInstanceExtensionProperties(nullptr,&c,e);bool ok=false;
    for(uint32_t i=0;i<c;i++)if(std::strcmp(e[i].extensionName,n)==0){ok=true;break;}
    delete[] e;return ok;
}
bool hasDeviceExt(VkPhysicalDevice d,const char* n){
    uint32_t c=0;if(vkEnumerateDeviceExtensionProperties(d,nullptr,&c,nullptr)!=VK_SUCCESS)return false;
    VkExtensionProperties* e=new VkExtensionProperties[c?c:1];
    if(c==0){delete[] e;return false;}
    vkEnumerateDeviceExtensionProperties(d,nullptr,&c,e);bool ok=false;
    for(uint32_t i=0;i<c;i++)if(std::strcmp(e[i].extensionName,n)==0){ok=true;break;}
    delete[] e;return ok;
}
void destroySwapchain(){
    if(g.device!=VK_NULL_HANDLE)vkDeviceWaitIdle(g.device);
    for(uint32_t i=0;i<g.imageCount&&i<8;i++){
        if(g.framebuffers[i]){vkDestroyFramebuffer(g.device,g.framebuffers[i],nullptr);g.framebuffers[i]=VK_NULL_HANDLE;}
        if(g.views[i]){vkDestroyImageView(g.device,g.views[i],nullptr);g.views[i]=VK_NULL_HANDLE;}
    }
    g.imageCount=0;
    if(g.renderPass){vkDestroyRenderPass(g.device,g.renderPass,nullptr);g.renderPass=VK_NULL_HANDLE;}
    if(g.commandPool){vkDestroyCommandPool(g.device,g.commandPool,nullptr);g.commandPool=VK_NULL_HANDLE;}
    if(g.imageAvailable){vkDestroySemaphore(g.device,g.imageAvailable,nullptr);g.imageAvailable=VK_NULL_HANDLE;}
    if(g.renderFinished){vkDestroySemaphore(g.device,g.renderFinished,nullptr);g.renderFinished=VK_NULL_HANDLE;}
    if(g.inFlight){vkDestroyFence(g.device,g.inFlight,nullptr);g.inFlight=VK_NULL_HANDLE;}
    if(g.swapchain){vkDestroySwapchainKHR(g.device,g.swapchain,nullptr);g.swapchain=VK_NULL_HANDLE;}
}
void reset(){
    destroySwapchain();
    if(g.surface&&g.instance){vkDestroySurfaceKHR(g.instance,g.surface,nullptr);g.surface=VK_NULL_HANDLE;}
    if(g.device){vkDeviceWaitIdle(g.device);vkDestroyDevice(g.device,nullptr);g.device=VK_NULL_HANDLE;}
    if(g.window){ANativeWindow_release(g.window);g.window=nullptr;}
    if(g.instance){vkDestroyInstance(g.instance,nullptr);g.instance=VK_NULL_HANDLE;}
    g=Runtime{};
}
bool createInstance(){
    if(!hasInstanceExt(VK_KHR_SURFACE_EXTENSION_NAME)||!hasInstanceExt("VK_KHR_android_surface"))return false;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName="Noir 3D Game Engine";app.applicationVersion=VK_MAKE_VERSION(1,0,0);
    app.pEngineName="Noir";app.engineVersion=VK_MAKE_VERSION(1,0,0);app.apiVersion=VK_API_VERSION_1_0;
    const char* exts[]={VK_KHR_SURFACE_EXTENSION_NAME,"VK_KHR_android_surface"};
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo=&app;ci.enabledExtensionCount=2;ci.ppEnabledExtensionNames=exts;
    return vkCreateInstance(&ci,nullptr,&g.instance)==VK_SUCCESS;
}
bool createSurface(ANativeWindow* w){
    if(!w||!g.instance)return false;
    VkAndroidSurfaceCreateInfoKHR ci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    ci.window=w;
    return vkCreateAndroidSurfaceKHR(g.instance,&ci,nullptr,&g.surface)==VK_SUCCESS;
}
bool pickDevice(){
    uint32_t count=0;if(vkEnumeratePhysicalDevices(g.instance,&count,nullptr)!=VK_SUCCESS||count==0)return false;
    VkPhysicalDevice* ds=new VkPhysicalDevice[count];
    if(vkEnumeratePhysicalDevices(g.instance,&count,ds)!=VK_SUCCESS){delete[] ds;return false;}
    for(uint32_t d=0;d<count;d++){
        uint32_t qcount=0;vkGetPhysicalDeviceQueueFamilyProperties(ds[d],&qcount,nullptr);
        VkQueueFamilyProperties* q=new VkQueueFamilyProperties[qcount?qcount:1];
        vkGetPhysicalDeviceQueueFamilyProperties(ds[d],&qcount,q);
        for(uint32_t i=0;i<qcount;i++){
            if(!(q[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)||!q[i].queueCount)continue;
            VkBool32 present=VK_FALSE;vkGetPhysicalDeviceSurfaceSupportKHR(ds[d],i,g.surface,&present);
            if(present){
                g.physical=ds[d];g.graphicsFamily=i;g.presentFamily=i;
                vkGetPhysicalDeviceProperties(g.physical,&g.props);g.apiVersion=g.props.apiVersion;
                delete[] q;delete[] ds;return true;
            }
        }
        delete[] q;
    }
    delete[] ds;return false;
}
bool createDevice(){
    if(!g.physical||g.graphicsFamily==UINT32_MAX||!hasDeviceExt(g.physical,VK_KHR_SWAPCHAIN_EXTENSION_NAME))return false;
    float pr=1.0f;VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qi.queueFamilyIndex=g.graphicsFamily;qi.queueCount=1;qi.pQueuePriorities=&pr;
    const char* exts[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkPhysicalDeviceFeatures features{};
    VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    ci.queueCreateInfoCount=1;ci.pQueueCreateInfos=&qi;ci.enabledExtensionCount=1;ci.ppEnabledExtensionNames=exts;ci.pEnabledFeatures=&features;
    if(vkCreateDevice(g.physical,&ci,nullptr,&g.device)!=VK_SUCCESS)return false;
    vkGetDeviceQueue(g.device,g.graphicsFamily,0,&g.graphics);
    g.present=g.graphics;return true;
}
bool createSwapchain(){
    VkSurfaceCapabilitiesKHR caps{};if(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(g.physical,g.surface,&caps)!=VK_SUCCESS)return false;
    uint32_t fc=0;vkGetPhysicalDeviceSurfaceFormatsKHR(g.physical,g.surface,&fc,nullptr);if(!fc)return false;
    VkSurfaceFormatKHR* fs=new VkSurfaceFormatKHR[fc];vkGetPhysicalDeviceSurfaceFormatsKHR(g.physical,g.surface,&fc,fs);
    VkSurfaceFormatKHR chosen=fs[0];
    for(uint32_t i=0;i<fc;i++)if(fs[i].format==VK_FORMAT_B8G8R8A8_SRGB&&fs[i].colorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR){chosen=fs[i];break;}
    delete[] fs;
    VkExtent2D ex=caps.currentExtent;
    if(ex.width==0xffffffffu){ex.width=std::max(caps.minImageExtent.width,std::min(caps.maxImageExtent.width,(uint32_t)ANativeWindow_getWidth(g.window)));ex.height=std::max(caps.minImageExtent.height,std::min(caps.maxImageExtent.height,(uint32_t)ANativeWindow_getHeight(g.window)));}
    uint32_t imageCount=caps.minImageCount+1;if(caps.maxImageCount&&imageCount>caps.maxImageCount)imageCount=caps.maxImageCount;
    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface=g.surface;ci.minImageCount=imageCount;ci.imageFormat=chosen.format;ci.imageColorSpace=chosen.colorSpace;ci.imageExtent=ex;
    ci.imageArrayLayers=1;ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;
    ci.preTransform=caps.currentTransform;ci.compositeAlpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;ci.presentMode=VK_PRESENT_MODE_FIFO_KHR;ci.clipped=VK_TRUE;
    if(vkCreateSwapchainKHR(g.device,&ci,nullptr,&g.swapchain)!=VK_SUCCESS)return false;
    g.format=chosen.format;g.extent=ex;return true;
}
bool createRenderTargets(){
    VkAttachmentDescription color{};color.format=g.format;color.samples=VK_SAMPLE_COUNT_1_BIT;color.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;color.storeOp=VK_ATTACHMENT_STORE_OP_STORE;color.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;color.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{};ref.attachment=0;ref.layout=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkSubpassDescription sub{};sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;sub.colorAttachmentCount=1;sub.pColorAttachments=&ref;
    VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};rp.attachmentCount=1;rp.pAttachments=&color;rp.subpassCount=1;rp.pSubpasses=&sub;
    if(vkCreateRenderPass(g.device,&rp,nullptr,&g.renderPass)!=VK_SUCCESS)return false;
    uint32_t count=0;vkGetSwapchainImagesKHR(g.device,g.swapchain,&count,nullptr);if(!count||count>8)return false;
    g.imageCount=count;vkGetSwapchainImagesKHR(g.device,g.swapchain,&count,g.images);
    VkImageViewCreateInfo iv{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};iv.viewType=VK_IMAGE_VIEW_TYPE_2D;iv.format=g.format;iv.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;iv.subresourceRange.levelCount=1;iv.subresourceRange.layerCount=1;
    VkFramebufferCreateInfo fb{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};fb.renderPass=g.renderPass;fb.attachmentCount=1;fb.width=g.extent.width;fb.height=g.extent.height;fb.layers=1;
    for(uint32_t i=0;i<g.imageCount;i++){
        iv.image=g.images[i];
        if(vkCreateImageView(g.device,&iv,nullptr,&g.views[i])!=VK_SUCCESS)return false;
        fb.pAttachments=&g.views[i];
        if(vkCreateFramebuffer(g.device,&fb,nullptr,&g.framebuffers[i])!=VK_SUCCESS)return false;
    }
    VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cp.queueFamilyIndex=g.graphicsFamily;cp.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if(vkCreateCommandPool(g.device,&cp,nullptr,&g.commandPool)!=VK_SUCCESS)return false;
    VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=g.commandPool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;
    if(vkAllocateCommandBuffers(g.device,&ca,&g.commandBuffer)!=VK_SUCCESS)return false;
    VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};if(vkCreateSemaphore(g.device,&si,nullptr,&g.imageAvailable)!=VK_SUCCESS)return false;if(vkCreateSemaphore(g.device,&si,nullptr,&g.renderFinished)!=VK_SUCCESS)return false;
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;if(vkCreateFence(g.device,&fi,nullptr,&g.inFlight)!=VK_SUCCESS)return false;
    return true;
}
std::string api(uint32_t v){return std::to_string(VK_VERSION_MAJOR(v))+"."+std::to_string(VK_VERSION_MINOR(v))+"."+std::to_string(VK_VERSION_PATCH(v));}
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanSupported(JNIEnv*,jclass){
    return (hasInstanceExt(VK_KHR_SURFACE_EXTENSION_NAME)&&hasInstanceExt("VK_KHR_android_surface"))?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_noir_game_engine_NoirNative_vulkanStatus(JNIEnv* e,jclass){
    if(!createInstance())return e->NewStringUTF("Vulkan Android surface unavailable");
    uint32_t c=0;VkResult r=vkEnumeratePhysicalDevices(g.instance,&c,nullptr);std::string s=(r==VK_SUCCESS&&c)?"Vulkan loader + Android surface + GPU ready":"No Vulkan GPU";
    vkDestroyInstance(g.instance,nullptr);g.instance=VK_NULL_HANDLE;return e->NewStringUTF(s.c_str());
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanInitialize(JNIEnv*,jclass){
    reset();if(!createInstance())return JNI_FALSE;return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanShutdown(JNIEnv*,jclass){reset();}
extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanDeviceReady(JNIEnv*,jclass){return g.initialized?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jstring JNICALL Java_com_noir_game_engine_NoirNative_vulkanDeviceInfo(JNIEnv* e,jclass){
    if(!g.initialized)return e->NewStringUTF("Vulkan surface not attached");
    std::string s=std::string(g.props.deviceName)+" | API "+api(g.apiVersion)+" | swapchain "+std::to_string(g.extent.width)+"x"+std::to_string(g.extent.height);
    return e->NewStringUTF(s.c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_com_noir_game_engine_NoirNative_vulkanFeatureInfo(JNIEnv* e,jclass){
    if(!g.physical)return e->NewStringUTF("Vulkan feature probe: no physical device selected");
    VkPhysicalDeviceFeatures f{}; vkGetPhysicalDeviceFeatures(g.physical,&f);
    std::string s="samplerAnisotropy="+std::string(f.samplerAnisotropy?"yes":"no");
    s+=" | wideLines="+std::string(f.wideLines?"yes":"no");
    s+=" | fillModeNonSolid="+std::string(f.fillModeNonSolid?"yes":"no");
    s+=" | sampleRateShading="+std::string(f.sampleRateShading?"yes":"no");
    s+=" | geometryShader="+std::string(f.geometryShader?"yes":"no");
    s+=" | tessellationShader="+std::string(f.tessellationShader?"yes":"no");
    return e->NewStringUTF(s.c_str());
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanAttachSurface(JNIEnv* env,jclass,jobject surface){
    if(!surface)return JNI_FALSE;
    reset();if(!createInstance())return JNI_FALSE;
    ANativeWindow* w=ANativeWindow_fromSurface(env,surface);if(!w){reset();return JNI_FALSE;}g.window=w;
    if(!createSurface(w)||!pickDevice()||!createDevice()||!createSwapchain()||!createRenderTargets()){reset();return JNI_FALSE;}
    g.surfaceReady=true;g.initialized=true;return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanDrawFrame(JNIEnv*,jclass){
    if(!g.initialized||!g.surfaceReady)return JNI_FALSE;
    if(vkWaitForFences(g.device,1,&g.inFlight,VK_TRUE,1000000000ull)!=VK_SUCCESS)return JNI_FALSE;
    uint32_t image=0;VkResult ar=vkAcquireNextImageKHR(g.device,g.swapchain,1000000000ull,g.imageAvailable,VK_NULL_HANDLE,&image);
    if(ar==VK_ERROR_OUT_OF_DATE_KHR||ar==VK_SUBOPTIMAL_KHR)return JNI_FALSE;if(ar!=VK_SUCCESS)return JNI_FALSE;
    vkResetFences(g.device,1,&g.inFlight);vkResetCommandBuffer(g.commandBuffer,0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};if(vkBeginCommandBuffer(g.commandBuffer,&bi)!=VK_SUCCESS)return JNI_FALSE;
    VkClearValue clear{};clear.color.float32[0]=0.06f;clear.color.float32[1]=0.10f;clear.color.float32[2]=0.16f;clear.color.float32[3]=1.0f;
    VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};rp.renderPass=g.renderPass;rp.framebuffer=g.framebuffers[image];rp.renderArea.extent=g.extent;rp.clearValueCount=1;rp.pClearValues=&clear;
    vkCmdBeginRenderPass(g.commandBuffer,&rp,VK_SUBPASS_CONTENTS_INLINE);vkCmdEndRenderPass(g.commandBuffer);if(vkEndCommandBuffer(g.commandBuffer)!=VK_SUCCESS)return JNI_FALSE;
    VkPipelineStageFlags stage=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&g.imageAvailable;si.pWaitDstStageMask=&stage;si.commandBufferCount=1;si.pCommandBuffers=&g.commandBuffer;si.signalSemaphoreCount=1;si.pSignalSemaphores=&g.renderFinished;
    if(vkQueueSubmit(g.graphics,1,&si,g.inFlight)!=VK_SUCCESS)return JNI_FALSE;
    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&g.renderFinished;pi.swapchainCount=1;pi.pSwapchains=&g.swapchain;pi.pImageIndices=&image;
    VkResult pr=vkQueuePresentKHR(g.present,&pi);return (pr==VK_SUCCESS||pr==VK_SUBOPTIMAL_KHR)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanDetachSurface(JNIEnv*,jclass){reset();}