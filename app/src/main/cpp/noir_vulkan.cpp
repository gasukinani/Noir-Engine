#include <jni.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#define NOIR_VK_LOG(...) __android_log_print(ANDROID_LOG_INFO,"NoirVulkan",__VA_ARGS__)

namespace {

struct Vec3 { float x,y,z; };
static Vec3 operator+(Vec3 a,Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
static Vec3 operator-(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
static Vec3 operator*(Vec3 a,float s){return {a.x*s,a.y*s,a.z*s};}
static float dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
static Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
static Vec3 normalize(Vec3 v){float l=std::sqrt(std::max(0.000001f,dot(v,v)));return v*(1.0f/l);}

struct Mat4 { float m[16]; };
static Mat4 identity(){Mat4 r{};r.m[0]=r.m[5]=r.m[10]=r.m[15]=1;return r;}
static Mat4 mul(const Mat4&a,const Mat4&b){
    Mat4 r{};
    for(int c=0;c<4;c++)for(int row=0;row<4;row++)
        r.m[c*4+row]=a.m[row]*b.m[c*4]+a.m[4+row]*b.m[c*4+1]+a.m[8+row]*b.m[c*4+2]+a.m[12+row]*b.m[c*4+3];
    return r;
}
static Mat4 perspective(float fov,float aspect,float zn,float zf){
    Mat4 r{};float f=1.0f/std::tan(fov*0.5f);
    r.m[0]=f/aspect;r.m[5]=f;r.m[10]=(zf+zn)/(zn-zf);r.m[11]=-1;r.m[14]=(2*zf*zn)/(zn-zf);return r;
}
static Mat4 lookAt(Vec3 eye,Vec3 center,Vec3 up){
    Vec3 f=normalize(center-eye),s=normalize(cross(f,up)),u=cross(s,f);Mat4 r=identity();
    r.m[0]=s.x;r.m[1]=s.y;r.m[2]=s.z;r.m[4]=u.x;r.m[5]=u.y;r.m[6]=u.z;
    r.m[8]=-f.x;r.m[9]=-f.y;r.m[10]=-f.z;
    r.m[12]=-dot(s,eye);r.m[13]=-dot(u,eye);r.m[14]=dot(f,eye);return r;
}
static Mat4 rotationX(float a){Mat4 r=identity();float c=std::cos(a),s=std::sin(a);r.m[5]=c;r.m[6]=s;r.m[9]=-s;r.m[10]=c;return r;}
static Mat4 rotationY(float a){Mat4 r=identity();float c=std::cos(a),s=std::sin(a);r.m[0]=c;r.m[2]=-s;r.m[8]=s;r.m[10]=c;return r;}
static Mat4 rotationZ(float a){Mat4 r=identity();float c=std::cos(a),s=std::sin(a);r.m[0]=c;r.m[1]=s;r.m[4]=-s;r.m[5]=c;return r;}
static Vec3 rotate(Vec3 p,float rx,float ry,float rz){
    Mat4 m=mul(mul(rotationZ(rz),rotationY(ry)),rotationX(rx));
    return {m.m[0]*p.x+m.m[4]*p.y+m.m[8]*p.z,m.m[1]*p.x+m.m[5]*p.y+m.m[9]*p.z,m.m[2]*p.x+m.m[6]*p.y+m.m[10]*p.z};
}

struct Vertex { float px,py,pz,nx,ny,nz,r,g,b,a; };
struct Instance { float x,y,z,sx,sy,sz,rx,ry,rz; int kind; };
struct FramePC { float vp[16]; float cameraExposure[4]; float sunBrightness[4]; float environment[4]; };

struct Runtime {
    VkInstance instance=VK_NULL_HANDLE;VkPhysicalDevice physical=VK_NULL_HANDLE;VkDevice device=VK_NULL_HANDLE;
    VkQueue graphics=VK_NULL_HANDLE;uint32_t family=UINT32_MAX;VkSurfaceKHR surface=VK_NULL_HANDLE;
    VkSwapchainKHR swapchain=VK_NULL_HANDLE;VkFormat format=VK_FORMAT_B8G8R8A8_SRGB;VkExtent2D extent{1,1};
    VkRenderPass renderPass=VK_NULL_HANDLE;VkCommandPool commandPool=VK_NULL_HANDLE;VkCommandBuffer cmd=VK_NULL_HANDLE;
    VkSemaphore imageAvailable=VK_NULL_HANDLE,renderFinished=VK_NULL_HANDLE;VkFence fence=VK_NULL_HANDLE;
    VkImage images[8]{};VkImageView views[8]{};VkFramebuffer framebuffers[8]{};uint32_t imageCount=0;
    VkImage depthImage=VK_NULL_HANDLE;VkDeviceMemory depthMemory=VK_NULL_HANDLE;VkImageView depthView=VK_NULL_HANDLE;VkFormat depthFormat=VK_FORMAT_D32_SFLOAT;
    VkShaderModule vert=VK_NULL_HANDLE,frag=VK_NULL_HANDLE;VkPipelineLayout pipelineLayout=VK_NULL_HANDLE;VkPipeline pipeline=VK_NULL_HANDLE;
    VkBuffer vertexBuffer=VK_NULL_HANDLE;VkDeviceMemory vertexMemory=VK_NULL_HANDLE;void* mapped=nullptr;size_t vertexCapacity=0;uint32_t vertexCount=0;
    AAssetManager* assets=nullptr;ANativeWindow* window=nullptr;bool initialized=false;
    std::vector<Instance> scene;int skyMode=2;float exposure=1.0f,brightness=1.0f,fog=0.008f;float quality=1.0f;Vec3 sun{-0.38f,-0.82f,-0.32f};
    float yaw=-90,pitch=12,distance=18,targetX=0,targetY=1.4f,targetZ=0;
} g;

static bool instanceExt(const char*n){uint32_t c=0;if(vkEnumerateInstanceExtensionProperties(nullptr,&c,nullptr)!=VK_SUCCESS)return false;std::vector<VkExtensionProperties> e(c);if(c)vkEnumerateInstanceExtensionProperties(nullptr,&c,e.data());for(auto&x:e)if(!std::strcmp(x.extensionName,n))return true;return false;}
static bool deviceExt(VkPhysicalDevice d,const char*n){uint32_t c=0;if(vkEnumerateDeviceExtensionProperties(d,nullptr,&c,nullptr)!=VK_SUCCESS)return false;std::vector<VkExtensionProperties> e(c);if(c)vkEnumerateDeviceExtensionProperties(d,nullptr,&c,e.data());for(auto&x:e)if(!std::strcmp(x.extensionName,n))return true;return false;}
static uint32_t memoryType(uint32_t bits,VkMemoryPropertyFlags flags){VkPhysicalDeviceMemoryProperties p{};vkGetPhysicalDeviceMemoryProperties(g.physical,&p);for(uint32_t i=0;i<p.memoryTypeCount;i++)if((bits&(1u<<i))&&(p.memoryTypes[i].propertyFlags&flags)==flags)return i;return UINT32_MAX;}
static bool createBuffer(VkDeviceSize size,VkBufferUsageFlags usage,VkMemoryPropertyFlags props,VkBuffer&b,VkDeviceMemory&m,void**mapOut){
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bi.size=size;bi.usage=usage;bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if(vkCreateBuffer(g.device,&bi,nullptr,&b)!=VK_SUCCESS)return false;
    VkMemoryRequirements mr{};vkGetBufferMemoryRequirements(g.device,b,&mr);uint32_t mt=memoryType(mr.memoryTypeBits,props);
    if(mt==UINT32_MAX)return false;VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=mr.size;ai.memoryTypeIndex=mt;
    if(vkAllocateMemory(g.device,&ai,nullptr,&m)!=VK_SUCCESS)return false;if(vkBindBufferMemory(g.device,b,m,0)!=VK_SUCCESS)return false;
    if(mapOut&&vkMapMemory(g.device,m,0,size,0,mapOut)!=VK_SUCCESS)return false;return true;
}
static bool findDepthFormat(){
    VkFormatProperties p{};vkGetPhysicalDeviceFormatProperties(g.physical,VK_FORMAT_D32_SFLOAT,&p);
    if(p.optimalTilingFeatures&VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT){g.depthFormat=VK_FORMAT_D32_SFLOAT;return true;}
    vkGetPhysicalDeviceFormatProperties(g.physical,VK_FORMAT_D24_UNORM_S8_UINT,&p);
    if(p.optimalTilingFeatures&VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT){g.depthFormat=VK_FORMAT_D24_UNORM_S8_UINT;return true;}
    return false;
}
static void destroySwap(){
    if(!g.device)return;vkDeviceWaitIdle(g.device);
    if(g.mapped&&g.vertexMemory){vkUnmapMemory(g.device,g.vertexMemory);g.mapped=nullptr;}
    if(g.vertexBuffer)vkDestroyBuffer(g.device,g.vertexBuffer,nullptr);
    if(g.vertexMemory)vkFreeMemory(g.device,g.vertexMemory,nullptr);
    g.vertexBuffer=VK_NULL_HANDLE;g.vertexMemory=VK_NULL_HANDLE;g.vertexCapacity=0;
    if(g.pipeline)vkDestroyPipeline(g.device,g.pipeline,nullptr);g.pipeline=VK_NULL_HANDLE;
    if(g.pipelineLayout)vkDestroyPipelineLayout(g.device,g.pipelineLayout,nullptr);g.pipelineLayout=VK_NULL_HANDLE;
    if(g.vert)vkDestroyShaderModule(g.device,g.vert,nullptr);if(g.frag)vkDestroyShaderModule(g.device,g.frag,nullptr);g.vert=g.frag=VK_NULL_HANDLE;
    for(uint32_t i=0;i<g.imageCount;i++){if(g.framebuffers[i])vkDestroyFramebuffer(g.device,g.framebuffers[i],nullptr);if(g.views[i])vkDestroyImageView(g.device,g.views[i],nullptr);g.framebuffers[i]=VK_NULL_HANDLE;g.views[i]=VK_NULL_HANDLE;}
    g.imageCount=0;
    if(g.depthView)vkDestroyImageView(g.device,g.depthView,nullptr);if(g.depthImage)vkDestroyImage(g.device,g.depthImage,nullptr);if(g.depthMemory)vkFreeMemory(g.device,g.depthMemory,nullptr);
    g.depthView=VK_NULL_HANDLE;g.depthImage=VK_NULL_HANDLE;g.depthMemory=VK_NULL_HANDLE;
    if(g.renderPass)vkDestroyRenderPass(g.device,g.renderPass,nullptr);g.renderPass=VK_NULL_HANDLE;
    if(g.commandPool)vkDestroyCommandPool(g.device,g.commandPool,nullptr);g.commandPool=VK_NULL_HANDLE;g.cmd=VK_NULL_HANDLE;
    if(g.imageAvailable)vkDestroySemaphore(g.device,g.imageAvailable,nullptr);if(g.renderFinished)vkDestroySemaphore(g.device,g.renderFinished,nullptr);if(g.fence)vkDestroyFence(g.device,g.fence,nullptr);
    g.imageAvailable=g.renderFinished=VK_NULL_HANDLE;g.fence=VK_NULL_HANDLE;
    if(g.swapchain)vkDestroySwapchainKHR(g.device,g.swapchain,nullptr);g.swapchain=VK_NULL_HANDLE;
}
static void reset(){
    if(g.device)destroySwap();
    if(g.surface&&g.instance)vkDestroySurfaceKHR(g.instance,g.surface,nullptr);g.surface=VK_NULL_HANDLE;
    if(g.device)vkDestroyDevice(g.device,nullptr);g.device=VK_NULL_HANDLE;
    if(g.window)ANativeWindow_release(g.window);g.window=nullptr;
    if(g.instance)vkDestroyInstance(g.instance,nullptr);g.instance=VK_NULL_HANDLE;
    if(g.assets)g.assets=nullptr;
    g.initialized=false;
}
static bool makeInstance(){
    if(!instanceExt(VK_KHR_SURFACE_EXTENSION_NAME)||!instanceExt("VK_KHR_android_surface"))return false;
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};ai.pApplicationName="Noir";ai.applicationVersion=1;ai.pEngineName="NoirGFX";ai.engineVersion=1;ai.apiVersion=VK_API_VERSION_1_0;
    const char* exts[]={VK_KHR_SURFACE_EXTENSION_NAME,"VK_KHR_android_surface"};
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&ai;ci.enabledExtensionCount=2;ci.ppEnabledExtensionNames=exts;
    return vkCreateInstance(&ci,nullptr,&g.instance)==VK_SUCCESS;
}
static bool makeDevice(){
    uint32_t c=0;if(vkEnumeratePhysicalDevices(g.instance,&c,nullptr)!=VK_SUCCESS||!c)return false;std::vector<VkPhysicalDevice>d(c);vkEnumeratePhysicalDevices(g.instance,&c,d.data());
    for(VkPhysicalDevice pd:d){
        uint32_t qc=0;vkGetPhysicalDeviceQueueFamilyProperties(pd,&qc,nullptr);std::vector<VkQueueFamilyProperties>q(qc);vkGetPhysicalDeviceQueueFamilyProperties(pd,&qc,q.data());
        for(uint32_t i=0;i<qc;i++){VkBool32 present=VK_FALSE;vkGetPhysicalDeviceSurfaceSupportKHR(pd,i,g.surface,&present);if((q[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)&&present){
            g.physical=pd;g.family=i;VkPhysicalDeviceProperties p{};vkGetPhysicalDeviceProperties(pd,&p);
            if(!deviceExt(pd,VK_KHR_SWAPCHAIN_EXTENSION_NAME))return false;
            float pr=1.0f;VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=i;qi.queueCount=1;qi.pQueuePriorities=&pr;
            const char* exts[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME};VkPhysicalDeviceFeatures f{};
            VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};di.queueCreateInfoCount=1;di.pQueueCreateInfos=&qi;di.enabledExtensionCount=1;di.ppEnabledExtensionNames=exts;di.pEnabledFeatures=&f;
            if(vkCreateDevice(pd,&di,nullptr,&g.device)!=VK_SUCCESS)return false;vkGetDeviceQueue(g.device,i,0,&g.graphics);return true;
        }}
    }
    return false;
}
static bool makeSwap(){
    VkSurfaceCapabilitiesKHR caps{};if(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(g.physical,g.surface,&caps)!=VK_SUCCESS)return false;
    uint32_t fc=0;vkGetPhysicalDeviceSurfaceFormatsKHR(g.physical,g.surface,&fc,nullptr);if(!fc)return false;std::vector<VkSurfaceFormatKHR>fs(fc);vkGetPhysicalDeviceSurfaceFormatsKHR(g.physical,g.surface,&fc,fs.data());
    VkSurfaceFormatKHR fmt=fs[0];for(auto&f:fs)if(f.format==VK_FORMAT_B8G8R8A8_SRGB&&f.colorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR){fmt=f;break;}
    VkExtent2D ex=caps.currentExtent;if(ex.width==0xffffffffu){ex.width=std::max(caps.minImageExtent.width,std::min(caps.maxImageExtent.width,(uint32_t)ANativeWindow_getWidth(g.window)));ex.height=std::max(caps.minImageExtent.height,std::min(caps.maxImageExtent.height,(uint32_t)ANativeWindow_getHeight(g.window)));}
    uint32_t count=caps.minImageCount+1;if(caps.maxImageCount&&count>caps.maxImageCount)count=caps.maxImageCount;
    VkSwapchainCreateInfoKHR si{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};si.surface=g.surface;si.minImageCount=count;si.imageFormat=fmt.format;si.imageColorSpace=fmt.colorSpace;si.imageExtent=ex;si.imageArrayLayers=1;si.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;si.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;si.preTransform=caps.currentTransform;si.compositeAlpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;si.presentMode=VK_PRESENT_MODE_FIFO_KHR;si.clipped=VK_TRUE;
    if(vkCreateSwapchainKHR(g.device,&si,nullptr,&g.swapchain)!=VK_SUCCESS)return false;
    g.format=fmt.format;g.extent=ex;vkGetSwapchainImagesKHR(g.device,g.swapchain,&g.imageCount,nullptr);if(!g.imageCount||g.imageCount>8)return false;vkGetSwapchainImagesKHR(g.device,g.swapchain,&g.imageCount,g.images);
    VkImageViewCreateInfo iv{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};iv.viewType=VK_IMAGE_VIEW_TYPE_2D;iv.format=g.format;iv.subresourceRange.aspectMask=VK_IMAGE_ASPECT_COLOR_BIT;iv.subresourceRange.levelCount=1;iv.subresourceRange.layerCount=1;
    for(uint32_t i=0;i<g.imageCount;i++){iv.image=g.images[i];if(vkCreateImageView(g.device,&iv,nullptr,&g.views[i])!=VK_SUCCESS)return false;}
    return true;
}
static bool makeDepth(){
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ii.imageType=VK_IMAGE_TYPE_2D;ii.format=g.depthFormat;ii.extent={g.extent.width,g.extent.height,1};ii.mipLevels=1;ii.arrayLayers=1;ii.samples=VK_SAMPLE_COUNT_1_BIT;ii.tiling=VK_IMAGE_TILING_OPTIMAL;ii.usage=VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    if(vkCreateImage(g.device,&ii,nullptr,&g.depthImage)!=VK_SUCCESS)return false;
    VkMemoryRequirements mr{};vkGetImageMemoryRequirements(g.device,g.depthImage,&mr);uint32_t mt=memoryType(mr.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);if(mt==UINT32_MAX)return false;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=mr.size;ai.memoryTypeIndex=mt;if(vkAllocateMemory(g.device,&ai,nullptr,&g.depthMemory)!=VK_SUCCESS)return false;vkBindImageMemory(g.device,g.depthImage,g.depthMemory,0);
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};vi.image=g.depthImage;vi.viewType=VK_IMAGE_VIEW_TYPE_2D;vi.format=g.depthFormat;vi.subresourceRange.aspectMask=VK_IMAGE_ASPECT_DEPTH_BIT;vi.subresourceRange.levelCount=1;vi.subresourceRange.layerCount=1;
    return vkCreateImageView(g.device,&vi,nullptr,&g.depthView)==VK_SUCCESS;
}
static std::vector<uint32_t> shader(const char*name){
    std::vector<uint32_t> out;if(!g.assets)return out;AAsset*a=AAssetManager_open(g.assets,name,AASSET_MODE_BUFFER);if(!a)return out;size_t bytes=AAsset_getLength(a);if(bytes<4){AAsset_close(a);return out;}out.resize((bytes+3)/4);AAsset_read(a,out.data(),bytes);AAsset_close(a);return out;
}
static bool makeRenderPass(){
    VkAttachmentDescription color{};color.format=g.format;color.samples=VK_SAMPLE_COUNT_1_BIT;color.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;color.storeOp=VK_ATTACHMENT_STORE_OP_STORE;color.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;color.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentDescription depth{};depth.format=g.depthFormat;depth.samples=VK_SAMPLE_COUNT_1_BIT;depth.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;depth.storeOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;depth.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE;depth.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;depth.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;depth.finalLayout=VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    VkAttachmentDescription at[2]={color,depth};VkAttachmentReference cr{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL},dr{1,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;sub.colorAttachmentCount=1;sub.pColorAttachments=&cr;sub.pDepthStencilAttachment=&dr;
    VkRenderPassCreateInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};ri.attachmentCount=2;ri.pAttachments=at;ri.subpassCount=1;ri.pSubpasses=&sub;
    return vkCreateRenderPass(g.device,&ri,nullptr,&g.renderPass)==VK_SUCCESS;
}
static bool makePipeline(){
    auto vs=shader("vulkan/noir_forward.vert.spv"),fs=shader("vulkan/noir_forward.frag.spv");if(vs.empty()||fs.empty()){NOIR_VK_LOG("Missing Vulkan forward SPIR-V assets");return false;}
    VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};sm.codeSize=vs.size()*4;sm.pCode=vs.data();if(vkCreateShaderModule(g.device,&sm,nullptr,&g.vert)!=VK_SUCCESS)return false;sm.codeSize=fs.size()*4;sm.pCode=fs.data();if(vkCreateShaderModule(g.device,&sm,nullptr,&g.frag)!=VK_SUCCESS)return false;
    VkPipelineShaderStageCreateInfo st[2]={{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};st[0].stage=VK_SHADER_STAGE_VERTEX_BIT;st[0].module=g.vert;st[0].pName="main";st[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;st[1].module=g.frag;st[1].pName="main";
    VkVertexInputBindingDescription bd{};bd.binding=0;bd.stride=sizeof(Vertex);bd.inputRate=VK_VERTEX_INPUT_RATE_VERTEX;
    VkVertexInputAttributeDescription ad[3]{};ad[0]={0,0,VK_FORMAT_R32G32B32_SFLOAT,0};ad[1]={0,1,VK_FORMAT_R32G32B32_SFLOAT,12};ad[2]={0,2,VK_FORMAT_R32G32B32A32_SFLOAT,24};
    VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};vi.vertexBindingDescriptionCount=1;vi.pVertexBindingDescriptions=&bd;vi.vertexAttributeDescriptionCount=3;vi.pVertexAttributeDescriptions=ad;
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};vp.viewportCount=1;vp.scissorCount=1;
    VkDynamicState dyns[2]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};VkPipelineDynamicStateCreateInfo dyn{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};dyn.dynamicStateCount=2;dyn.pDynamicStates=dyns;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};rs.polygonMode=VK_POLYGON_MODE_FILL;rs.cullMode=VK_CULL_MODE_BACK_BIT;rs.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;rs.lineWidth=1;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineDepthStencilStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};ds.depthTestEnable=VK_TRUE;ds.depthWriteEnable=VK_TRUE;ds.depthCompareOp=VK_COMPARE_OP_LESS_OR_EQUAL;
    VkPipelineColorBlendAttachmentState cb{};cb.colorWriteMask=0xF;
    VkPipelineColorBlendStateCreateInfo cbs{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};cbs.attachmentCount=1;cbs.pAttachments=&cb;
    VkPushConstantRange pc{};pc.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;pc.offset=0;pc.size=sizeof(FramePC);
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pl.pushConstantRangeCount=1;pl.pPushConstantRanges=&pc;if(vkCreatePipelineLayout(g.device,&pl,nullptr,&g.pipelineLayout)!=VK_SUCCESS)return false;
    VkGraphicsPipelineCreateInfo pi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};pi.stageCount=2;pi.pStages=st;pi.pVertexInputState=&vi;pi.pInputAssemblyState=&ia;pi.pViewportState=&vp;pi.pRasterizationState=&rs;pi.pMultisampleState=&ms;pi.pDepthStencilState=&ds;pi.pColorBlendState=&cbs;pi.pDynamicState=&dyn;pi.layout=g.pipelineLayout;pi.renderPass=g.renderPass;pi.subpass=0;
    return vkCreateGraphicsPipelines(g.device,VK_NULL_HANDLE,1,&pi,nullptr,&g.pipeline)==VK_SUCCESS;
}
static bool makeFrameResources(){
    VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cp.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;cp.queueFamilyIndex=g.family;if(vkCreateCommandPool(g.device,&cp,nullptr,&g.commandPool)!=VK_SUCCESS)return false;
    VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=g.commandPool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;if(vkAllocateCommandBuffers(g.device,&ca,&g.cmd)!=VK_SUCCESS)return false;
    VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};if(vkCreateSemaphore(g.device,&si,nullptr,&g.imageAvailable)!=VK_SUCCESS||vkCreateSemaphore(g.device,&si,nullptr,&g.renderFinished)!=VK_SUCCESS)return false;
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;return vkCreateFence(g.device,&fi,nullptr,&g.fence)==VK_SUCCESS;
}
static Vec3 skyColor(){
    switch(g.skyMode){
        case 1:return {0.31f,0.46f,0.70f};
        case 3:return {0.15f,0.23f,0.46f};
        default:return {0.38f,0.52f,0.72f};
    }
}
static Vec3 colorKind(int k){
    switch(k){
        case 15:return {0.09f,0.36f,0.66f};
        case 16:return {0.18f,0.46f,0.22f};
        case 17:return {0.15f,0.50f,0.22f};
        case 5:return {0.40f,0.43f,0.46f};
        default:return {0.48f,0.52f,0.58f};
    }
}
static void pushTri(std::vector<Vertex>&out,Vec3 a,Vec3 b,Vec3 c,Vec3 n,Vec3 col,float rough){out.push_back({a.x,a.y,a.z,n.x,n.y,n.z,col.x,col.y,col.z,rough});out.push_back({b.x,b.y,b.z,n.x,n.y,n.z,col.x,col.y,col.z,rough});out.push_back({c.x,c.y,c.z,n.x,n.y,n.z,col.x,col.y,col.z,rough});}
static void addCube(std::vector<Vertex>&o,const Instance&n,Vec3 col,float rough){
    const Vec3 p[8]={{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};
    const int f[6][4]={{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{3,2,6,7},{4,5,1,0}};
    const Vec3 no[6]={{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,1,0},{0,-1,0}};
    float rx=n.rx*0.0174532925f,ry=n.ry*0.0174532925f,rz=n.rz*0.0174532925f;
    for(int i=0;i<6;i++){Vec3 q[4];for(int j=0;j<4;j++){Vec3 v=p[f[i][j]];v={v.x*n.sx,v.y*n.sy,v.z*n.sz};q[j]=rotate(v,rx,ry,rz)+Vec3{n.x,n.y,n.z};}Vec3 nn=normalize(rotate(no[i],rx,ry,rz));pushTri(o,q[0],q[1],q[2],nn,col,rough);pushTri(o,q[0],q[2],q[3],nn,col,rough);}
}
static void addCone(std::vector<Vertex>&o,const Instance&n,Vec3 col){
    float rx=n.rx*0.0174532925f,ry=n.ry*0.0174532925f,rz=n.rz*0.0174532925f;const int sides=12;
    for(int i=0;i<sides;i++){float a0=6.2831853f*i/sides,a1=6.2831853f*(i+1)/sides;Vec3 a{std::cos(a0)*n.sx,0,std::sin(a0)*n.sz},b{std::cos(a1)*n.sx,0,std::sin(a1)*n.sz},c{0,2.0f*n.sy,0};Vec3 nn=normalize(rotate({std::cos((a0+a1)*0.5f),0.7f,std::sin((a0+a1)*0.5f)},rx,ry,rz));a=rotate(a,rx,ry,rz)+Vec3{n.x,n.y,n.z};b=rotate(b,rx,ry,rz)+Vec3{n.x,n.y,n.z};c=rotate(c,rx,ry,rz)+Vec3{n.x,n.y,n.z};pushTri(o,a,b,c,nn,col,0.82f);}
}
static void addRock(std::vector<Vertex>&o,const Instance&n,Vec3 col){
    Instance s=n;s.sx*=1.2f;s.sy*=0.8f;s.sz*=1.0f;
    const Vec3 v[6]={{0,1,0},{0,-1,0},{-1,0,0},{1,0,0},{0,0,-1},{0,0,1}};const int t[8][3]={{0,2,4},{0,4,3},{0,3,5},{0,5,2},{1,4,2},{1,3,4},{1,5,3},{1,2,5}};
    float rx=s.rx*0.0174532925f,ry=s.ry*0.0174532925f,rz=s.rz*0.0174532925f;
    for(auto&q:t){Vec3 a=v[q[0]],b=v[q[1]],c=v[q[2]];a={a.x*s.sx,a.y*s.sy,a.z*s.sz};b={b.x*s.sx,b.y*s.sy,b.z*s.sz};c={c.x*s.sx,c.y*s.sy,c.z*s.sz};Vec3 nn=normalize(cross(b-a,c-a));a=rotate(a,rx,ry,rz)+Vec3{s.x,s.y,s.z};b=rotate(b,rx,ry,rz)+Vec3{s.x,s.y,s.z};c=rotate(c,rx,ry,rz)+Vec3{s.x,s.y,s.z};pushTri(o,a,b,c,nn,col,0.92f);}
}
static void rebuildVertices(){
    std::vector<Vertex> out;out.reserve(std::min<size_t>(g.scene.size()*36,12000));int foliage=0;int stride=g.quality<=1.0f?3:(g.quality<3.0f?2:1);
    for(const Instance&n:g.scene){
        if(n.kind==17&&((foliage++)%stride)!=0)continue;
        Vec3 col=colorKind(n.kind);float rough=n.kind==15?0.18f:(n.kind==17?0.82f:0.65f);
        if(n.kind==17)addCone(out,n,col);else if(n.kind==5)addRock(out,n,col);else addCube(out,n,col,rough);
    }
    if(out.empty())return;
    size_t bytes=out.size()*sizeof(Vertex);
    if(!g.vertexBuffer||bytes>g.vertexCapacity){
        if(g.mapped&&g.vertexMemory)vkUnmapMemory(g.device,g.vertexMemory);
        if(g.vertexBuffer)vkDestroyBuffer(g.device,g.vertexBuffer,nullptr);
        if(g.vertexMemory)vkFreeMemory(g.device,g.vertexMemory,nullptr);g.mapped=nullptr;
        size_t cap=1;while(cap<bytes)cap*=2;g.vertexCapacity=cap;
        if(!createBuffer(g.vertexCapacity,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,g.vertexBuffer,g.vertexMemory,&g.mapped)){g.vertexCount=0;return;}
    }
    std::memcpy(g.mapped,out.data(),bytes);g.vertexCount=static_cast<uint32_t>(out.size());
}
static bool makeAll(){if(!findDepthFormat()||!makeSwap()||!makeDepth()||!makeRenderPass()||!makePipeline()||!makeFrameResources())return false;rebuildVertices();return true;}
static Mat4 viewProj(){float yr=g.yaw*0.0174532925f,pr=g.pitch*0.0174532925f;Vec3 cam{g.targetX+std::cos(pr)*std::cos(yr)*g.distance,g.targetY+std::sin(pr)*g.distance,g.targetZ+std::cos(pr)*std::sin(yr)*g.distance};return mul(perspective(1.117f,float(g.extent.width)/float(std::max(1u,g.extent.height)),0.05f,180),lookAt(cam,{g.targetX,g.targetY,g.targetZ},{0,1,0}));}
static Vec3 cameraPos(){float yr=g.yaw*0.0174532925f,pr=g.pitch*0.0174532925f;return {g.targetX+std::cos(pr)*std::cos(yr)*g.distance,g.targetY+std::sin(pr)*g.distance,g.targetZ+std::cos(pr)*std::sin(yr)*g.distance};}
static bool recordAndDraw(uint32_t image){
    vkResetCommandBuffer(g.cmd,0);VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};if(vkBeginCommandBuffer(g.cmd,&bi)!=VK_SUCCESS)return false;
    Vec3 sky=skyColor();VkClearValue clear[2]{};clear[0].color.float32[0]=std::min(1.0f,sky.x*g.brightness);clear[0].color.float32[1]=std::min(1.0f,sky.y*g.brightness);clear[0].color.float32[2]=std::min(1.0f,sky.z*g.brightness);clear[0].color.float32[3]=1;clear[1].depthStencil.depth=1;
    VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};rp.renderPass=g.renderPass;rp.framebuffer=g.framebuffers[image];rp.renderArea.extent=g.extent;rp.clearValueCount=2;rp.pClearValues=clear;
    vkCmdBeginRenderPass(g.cmd,&rp,VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0,0,float(g.extent.width),float(g.extent.height),0,1};VkRect2D sc{{0,0},g.extent};vkCmdSetViewport(g.cmd,0,1,&vp);vkCmdSetScissor(g.cmd,0,1,&sc);
    vkCmdBindPipeline(g.cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,g.pipeline);VkDeviceSize off=0;vkCmdBindVertexBuffers(g.cmd,0,1,&g.vertexBuffer,&off);
    FramePC pc{};Mat4 mvp=viewProj();std::memcpy(pc.vp,mvp.m,sizeof(pc.vp));Vec3 cam=cameraPos();pc.cameraExposure[0]=cam.x;pc.cameraExposure[1]=cam.y;pc.cameraExposure[2]=cam.z;pc.cameraExposure[3]=g.exposure;pc.sunBrightness[0]=g.sun.x;pc.sunBrightness[1]=g.sun.y;pc.sunBrightness[2]=g.sun.z;pc.sunBrightness[3]=g.brightness;pc.environment[0]=float(g.skyMode);pc.environment[1]=g.brightness;pc.environment[2]=g.fog;pc.environment[3]=g.quality;
    vkCmdPushConstants(g.cmd,g.pipelineLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(pc),&pc);
    if(g.vertexCount)vkCmdDraw(g.cmd,g.vertexCount,1,0,0);
    vkCmdEndRenderPass(g.cmd);return vkEndCommandBuffer(g.cmd)==VK_SUCCESS;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanSupported(JNIEnv*,jclass){return (instanceExt(VK_KHR_SURFACE_EXTENSION_NAME)&&instanceExt("VK_KHR_android_surface"))?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jstring JNICALL Java_com_noir_game_engine_NoirNative_vulkanStatus(JNIEnv*e,jclass){if(!makeInstance())return e->NewStringUTF("Vulkan Android surface unavailable");uint32_t c=0;vkEnumeratePhysicalDevices(g.instance,&c,nullptr);vkDestroyInstance(g.instance,nullptr);g.instance=VK_NULL_HANDLE;return e->NewStringUTF(c?"Vulkan forward-capable GPU found":"No Vulkan GPU");}
extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanInitialize(JNIEnv*,jclass){return JNI_TRUE;}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanShutdown(JNIEnv*,jclass){reset();}
extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanDeviceReady(JNIEnv*,jclass){return g.initialized?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jstring JNICALL Java_com_noir_game_engine_NoirNative_vulkanDeviceInfo(JNIEnv*e,jclass){if(!g.physical)return e->NewStringUTF("Vulkan GPU not attached");VkPhysicalDeviceProperties p{};vkGetPhysicalDeviceProperties(g.physical,&p);std::string s=p.deviceName;return e->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_com_noir_game_engine_NoirNative_vulkanFeatureInfo(JNIEnv*e,jclass){
    if(!g.physical)return e->NewStringUTF("Vulkan feature probe: no physical device selected");VkPhysicalDeviceFeatures f{};vkGetPhysicalDeviceFeatures(g.physical,&f);std::string s="samplerAnisotropy="+std::string(f.samplerAnisotropy?"yes":"no")+" | sampleRateShading="+std::string(f.sampleRateShading?"yes":"no");return e->NewStringUTF(s.c_str());
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanAttachSurface(JNIEnv*env,jclass,jobject surface,jobject assetManager){
    reset();if(!makeInstance())return JNI_FALSE;if(!surface||!assetManager)return JNI_FALSE;g.window=ANativeWindow_fromSurface(env,surface);g.assets=AAssetManager_fromJava(env,assetManager);if(!g.window||!g.assets){reset();return JNI_FALSE;}
    VkAndroidSurfaceCreateInfoKHR si{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};si.window=g.window;if(vkCreateAndroidSurfaceKHR(g.instance,&si,nullptr,&g.surface)!=VK_SUCCESS){reset();return JNI_FALSE;}
    if(!makeDevice()||!makeAll()){reset();return JNI_FALSE;}g.initialized=true;return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanResize(JNIEnv*,jclass,jint w,jint h){if(!g.initialized)return;(void)w;(void)h;}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanSetScene(JNIEnv*env,jclass,jfloatArray arr){
    if(!g.initialized||!arr)return;jsize len=env->GetArrayLength(arr);if(len<10){g.scene.clear();rebuildVertices();return;}std::vector<jfloat>d((size_t)len);env->GetFloatArrayRegion(arr,0,len,d.data());g.scene.clear();int n=std::min<int>(len/10,256);g.scene.reserve(n);for(int i=0;i<n;i++){const float*p=d.data()+i*10;Instance x{p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],p[8],(int)std::lround(p[9])};g.scene.push_back(x);}rebuildVertices();
}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanSetCamera(JNIEnv*,jclass,jfloat yaw,jfloat pitch,jfloat distance,jfloat tx,jfloat ty,jfloat tz){g.yaw=yaw;g.pitch=pitch;g.distance=distance;g.targetX=tx;g.targetY=ty;g.targetZ=tz;}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanSetEnvironment(JNIEnv*,jclass,jint mode,jfloat exposure,jfloat brightness,jfloat fog,jfloat sx,jfloat sy,jfloat sz){g.skyMode=mode;g.exposure=std::max(0.05f,float(exposure));g.brightness=std::max(0.0f,float(brightness));g.fog=std::max(0.0f,float(fog));g.sun=normalize({sx,sy,sz});}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanSetQuality(JNIEnv*,jclass,jint tier){g.quality=std::max(1.0f,std::min(4.0f,float(tier)));}
extern "C" JNIEXPORT jboolean JNICALL Java_com_noir_game_engine_NoirNative_vulkanDrawFrame(JNIEnv*,jclass){
    if(!g.initialized)return JNI_FALSE;if(vkWaitForFences(g.device,1,&g.fence,VK_TRUE,1000000000ull)!=VK_SUCCESS)return JNI_FALSE;
    uint32_t image=0;VkResult ar=vkAcquireNextImageKHR(g.device,g.swapchain,1000000000ull,g.imageAvailable,VK_NULL_HANDLE,&image);if(ar!=VK_SUCCESS&&ar!=VK_SUBOPTIMAL_KHR)return JNI_FALSE;
    vkResetFences(g.device,1,&g.fence);if(!recordAndDraw(image))return JNI_FALSE;
    VkPipelineStageFlags stage=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.waitSemaphoreCount=1;si.pWaitSemaphores=&g.imageAvailable;si.pWaitDstStageMask=&stage;si.commandBufferCount=1;si.pCommandBuffers=&g.cmd;si.signalSemaphoreCount=1;si.pSignalSemaphores=&g.renderFinished;if(vkQueueSubmit(g.graphics,1,&si,g.fence)!=VK_SUCCESS)return JNI_FALSE;
    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&g.renderFinished;pi.swapchainCount=1;pi.pSwapchains=&g.swapchain;pi.pImageIndices=&image;VkResult pr=vkQueuePresentKHR(g.graphics,&pi);return(pr==VK_SUCCESS||pr==VK_SUBOPTIMAL_KHR)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_vulkanDetachSurface(JNIEnv*,jclass){reset();}
