# Noir unified shader assets

Each material family may ship two prepared forms:

- `*.glsl` for OpenGL ES 3.0.
- `*.spv` for Vulkan, generated during the build.

The Android runtime does not invoke shaderc when the user changes the graphics API. Precompiled SPIR-V avoids runtime compiler CPU spikes, memory pressure, and shader/pipeline hitching. `ShaderAsset` keeps both representations under one asset identity.
