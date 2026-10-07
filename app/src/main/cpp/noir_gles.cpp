#include <jni.h>
#include "noir_graphics.h"

static noir::gfx::Renderer g_renderer;

extern "C" JNIEXPORT jstring JNICALL
Java_com_noir_game_engine_NoirNative_glesBackendInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_renderer.backendInfo());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_noir_game_engine_NoirNative_graphicsInitialize(JNIEnv*, jclass) {
    return g_renderer.initialize() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_noir_game_engine_NoirNative_graphicsResize(JNIEnv*, jclass, jint width, jint height) {
    g_renderer.resize(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_noir_game_engine_NoirNative_graphicsFrame(
        JNIEnv*, jclass, jfloat yaw, jfloat pitch, jfloat distance,
        jfloat targetX, jfloat targetY, jfloat targetZ, jboolean editorMode) {
    g_renderer.frame(yaw,pitch,distance,targetX,targetY,targetZ,editorMode==JNI_TRUE);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_noir_game_engine_NoirNative_graphicsFrameTimeMs(JNIEnv*, jclass) {
    return g_renderer.frameTimeMs();
}

extern "C" JNIEXPORT void JNICALL
Java_com_noir_game_engine_NoirNative_graphicsShutdown(JNIEnv*, jclass) {
    g_renderer.shutdown();
}
