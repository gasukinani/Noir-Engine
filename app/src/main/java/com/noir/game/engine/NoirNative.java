package com.noir.game.engine;

public final class NoirNative {
    private static boolean loaded;
    static { try { System.loadLibrary("noir3d"); loaded=true; } catch (UnsatisfiedLinkError ignored) { loaded=false; } }
    private NoirNative() {}
    public static boolean isLoaded(){return loaded;}
    public static native String engineVersion();
    public static native long engineBuildId();
    public static native void stepRigidBody(float[] state,float dt,float gravity);
    public static native void stepRigidBodyAdvanced(float[] state,float dt,float gravity,float damping,float floorY);
    public static native void stepRigidBodyContact(float[] state,float dt,float gravity,float damping,float floorY,float restitution,float friction);
    public static native float raySphereHit(float[] rayOrigin,float[] rayDir,float[] center,float radius);
    public static native float rayAabbHit(float[] rayOrigin,float[] rayDir,float[] min,float[] max);
    public static native float sweepSphereAabb(float[] origin,float radius,float[] direction,float maxDistance,float[] min,float[] max);
    public static native boolean sphereAabbOverlap(float[] center,float radius,float[] min,float[] max);
    public static native float springDamper(float current,float velocity,float target,float stiffness,float damping,float dt);
    public static native float fixedStepAlpha(float accumulator,float fixedDelta);
    public static native int countAabbPairs(float[] boxes);
    public static native float smoothDamp(float current,float target,float currentVelocity,float smoothTime,float maxSpeed,float dt);
    public static native String mobilePbrShader();
    public static native void setWorldEnvironment(float exposure,float ambient,float skyStrength,float sunStrength,float fogDensity,float cloudStrength,boolean enabled);
    public static native String worldEnvironmentInfo();
    public static native String worldEnvironmentGlesShader();
    public static native String worldEnvironmentVulkanShader();
    public static native boolean vulkanSupported();
    public static native String vulkanStatus();
    public static native String glesBackendInfo();
    public static native boolean graphicsInitialize();
    public static native void graphicsResize(int width,int height);
    public static native String csharpToolchainScan(String directory);
    public static native void graphicsSetScene(float[] snapshot);
    public static native void graphicsSetEnvironment(int skyMode,float exposure,float skyBrightness,float fogDensity,float sunX,float sunY,float sunZ);
    public static native void graphicsSetQuality(int qualityTier);
    public static native void graphicsFrame(float yaw,float pitch,float distance,float targetX,float targetY,float targetZ,boolean editorMode);
    public static native float graphicsFrameTimeMs();
    public static native void graphicsShutdown();
    public static native String themeColor(String key);
    public static native float[] themeMetrics();
    public static native boolean vulkanInitialize();
    public static native void vulkanShutdown();
    public static native boolean vulkanDeviceReady();
    public static native String vulkanDeviceInfo();
    public static native String vulkanFeatureInfo();
    public static native float[] editorLayout(float width,float height,float density);
    public static native boolean vulkanAttachSurface(android.view.Surface surface, android.content.res.AssetManager assets);
    public static native void vulkanResize(int width,int height);
    public static native void vulkanSetScene(float[] snapshot);
    public static native void vulkanSetCamera(float yaw,float pitch,float distance,float targetX,float targetY,float targetZ);
    public static native void vulkanSetEnvironment(int skyMode,float exposure,float skyBrightness,float fogDensity,float sunX,float sunY,float sunZ);
    public static native void vulkanSetQuality(int qualityTier);
    public static native boolean vulkanDrawFrame();
    public static native void vulkanDetachSurface();
}