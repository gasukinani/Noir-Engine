#include <jni.h>
#include "RenderManager.h"

using noir::render::GraphicsAPI;
using noir::render::RenderManager;

extern "C" JNIEXPORT void JNICALL
Java_com_noir_game_engine_MainActivity_nativeSetGraphicsAPI(JNIEnv*, jclass, jint index) {
    const GraphicsAPI api = index == 1 ? GraphicsAPI::VULKAN : GraphicsAPI::OPENGL_ES;
    // UI-thread call: only queues the transition. GPU destruction/creation happens
    // later on the active render thread, after the Android Surface is rebound.
    RenderManager::Instance().SwitchGraphicsAPI(api, nullptr);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_noir_game_engine_MainActivity_nativeGetGraphicsAPI(JNIEnv*, jclass) {
    return RenderManager::Instance().RequestedAPI() == GraphicsAPI::VULKAN ? 1 : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_noir_game_engine_NoirNative_nativeApplyPendingGraphicsAPI(JNIEnv*, jclass) {
    return RenderManager::Instance().ApplyPendingSwitch() ? JNI_TRUE : JNI_FALSE;
}
