# Noir 3D Game Engine v1.1.0

Noir is a mobile-first Android 3D editor/runtime with a native C++ graphics library, project manager, C# scripting SDK, shader/editor tooling, and Unity-style scene editing.

## Engine app

Package ID: `com.noir.game.engine`

The installed engine is a normal launcher application:
- `ProjectManagerActivity` is the launcher entry point.
- `MainActivity` hosts the actual editor.
- Native `libnoir3d.so` contains NoirGFX and runtime systems.
- The editor viewport is rendered by **NoirGFX C++ / OpenGL ES 3.0**, not a WebView or remote Three.js page.

## v1.1.0

- Native **NoirGFX** C++17/OpenGL ES 3 renderer
- Procedural 3D terrain and landmarks
- PBR-style GGX lighting, roughness and metallic materials
- Procedural sky, sun, fog, exposure and tone mapping
- Native editor camera with orbit/pan/pinch controls
- Java editor interaction layer with scene tree, inspector and gizmos
- Native `noir_theme.cpp` design-token system
- Built-in NoirDark, NoirMidnight and NoirSolarized themes
- C# .NET 10 SDK and Android 36.1 game template
- Roslyn-based C# compiler host
- Fixed compiler `build` mode so it emits the requested DLL
- Android Release pipeline builds the C# SDK before the APK
- Eight project-owned C# DLLs are embedded under `assets/csharp/sdk/`
- APK verification checks that the C# DLLs are physically present

## Project package layout

```text
project/
  project.game
  scenes/
  scripts/
  csharp/
    Noir.Game/
  assets/
  models/
  textures/
  materials/
  animations/
  shaders/
  audio/
  export/
```

## C# SDK inside the APK

The Android Release workflow builds the Noir API, mobile scripting SDK, game template, editor tooling, compiler host and sample DLLs. Gradle copies the verified SDK bundle into the final APK as:

```text
assets/csharp/sdk/*.dll
```

The app also extracts the packaged DLLs into its private `files/noir-csharp/sdk/` directory at startup so the editor/runtime tooling can access the same bundle.

## Build

The Android release workflow uses JDK 17, Gradle 8.10.2, Android API 35, NDK 28.1.13356709, .NET 10 and .NET Android 36.1.

The workflow builds the C# SDK first, emits sample DLLs, packages the DLL bundle, then runs `:app:assembleRelease` and verifies the APK contents.

## Themes

Theme files live in `app/src/main/assets/themes/` and native runtime tokens live in `app/src/main/cpp/noir_theme.cpp`.

## Release

The current repository build is v1.1.0. GitHub Actions publishes the Release APK and C# SDK as workflow artifacts. A signed production GitHub Release can be created when repository release/signing credentials are configured.

See `docs/SIGNING_AND_EXPORT.md` and `docs/NOIR_GFX.md`.
