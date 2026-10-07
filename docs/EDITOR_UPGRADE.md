# Noir Editor Upgrade

The editor now avoids presenting the current Vulkan presentation-only surface as a finished 3D scene renderer.

## 3D viewport
The GLES 3 forward/PBR renderer remains the editor viewport until the Vulkan scene pipeline has real mesh, depth, material and PBR draw submission. The editor world is now deterministic procedural terrain with varied elevations and five landmarks.

Per-object renderer state is cached in reusable arrays, removing the previous per-draw array-copy allocation.

## Vulkan diagnostics
The native backend exposes physical-device capability information for sampler anisotropy, wide lines, non-solid fill mode, sample-rate shading, geometry shaders and tessellation shaders.

These are capability reports, not claims that every feature is enabled in the scene pipeline.

## C# API
csharp/Noir.csproj is the authoritative desktop API assembly project and compiles csharp/Noir.Mobile.Scripting/*.cs into Noir.dll.

## Vulkan next milestone
A complete Vulkan scene renderer still needs SPIR-V shaders, depth targets, descriptor layouts, material/uniform buffers, textures/samplers, PBR lighting, shadows, synchronization, swapchain recreation and a shared scene draw list.
