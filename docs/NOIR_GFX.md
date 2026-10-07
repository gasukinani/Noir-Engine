# NoirGFX — Native Mobile 3D Renderer

NoirGFX is the editor viewport renderer used by the Android build.

## Architecture

- **C++17:** owns OpenGL ES 3.0 context rendering, procedural world geometry, camera projection, PBR shading and sky.
- **JNI:** `noir_gles.cpp` exposes the small lifecycle API to Java.
- **Java:** `NoirSurface` owns touch input and camera/editor interaction.
- **Editor UI:** `NoirEditorView` remains a transparent interaction overlay for hierarchy, inspector, gizmos and tools.
- **Theme:** `noir_theme.cpp` provides native design tokens consumed by `NoirTheme.java`.

The editor does **not** depend on Three.js, a remote CDN, or a WebView for its 3D scene.

## Rendering

The mobile renderer provides:

- procedural terrain blocks and landmarks;
- directional lighting;
- GGX-style PBR specular response;
- roughness and metallic controls;
- HDR-ish exposure and ACES-like tone shaping;
- atmospheric fog;
- procedural sky gradient and sun disc;
- depth testing and back-face culling;
- MSAA framebuffer selection when supported.

The renderer intentionally uses a bounded procedural scene so the editor remains usable on mobile GPUs.

## Editor behavior

The camera uses the same orbit model as the editor gizmos:

- one finger: orbit;
- two fingers: pan + pinch zoom;
- inspector transform editing;
- move/rotate/scale gizmos;
- reset camera;
- runtime mode switches to the game camera.

## Release

Android release builds use `:app:assembleRelease`. CI builds the native renderer from `CMakeLists.txt` and checks the `.game` project template and C# API.
