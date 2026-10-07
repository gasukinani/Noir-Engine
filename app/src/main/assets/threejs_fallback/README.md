# Three.js fallback sample

This folder is sample-only and is not the primary Noir renderer.

The production Android editor/runtime stays native C++ (OpenGL ES 3.0/Vulkan). This proof-of-concept is useful only for validating the SS3 outdoor composition if a device-specific native driver path misbehaves.

The sample uses a pinned CDN version to keep the repository small. A future offline fallback should vendor the exact Three.js build into this folder.
