#include <jni.h>
#include <cstdint>

static constexpr const char* NOIR_VERSION = "1.1.0";
static constexpr std::uint64_t NOIR_BUILD_ID = 0x4E4F495231313030ULL;

extern "C" JNIEXPORT jstring JNICALL
Java_com_noir_game_engine_NoirNative_engineVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(NOIR_VERSION);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_noir_game_engine_NoirNative_engineBuildId(JNIEnv*, jclass) {
    return static_cast<jlong>(NOIR_BUILD_ID);
}
