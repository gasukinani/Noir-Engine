#include <jni.h>
#include <cstring>

namespace {
struct Theme {
    const char* background="#F6F3EC";
    const char* surface="#FFFDF8";
    const char* surface2="#ECE8DE";
    const char* border="#D7D0C4";
    const char* accent="#5C79A6";
    const char* accent2="#8A6F52";
    const char* text="#2C3036";
    const char* muted="#6E737B";
    const char* good="#347A57";
    const char* warn="#9A6A22";
    const char* bad="#B14949";
    float corner=7.0f;
    float spacing=7.0f;
};
static Theme g_theme;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noir_game_engine_NoirNative_themeColor(JNIEnv* env,jclass,jstring key){
    if(!key)return env->NewStringUTF(g_theme.background);
    const char* k=env->GetStringUTFChars(key,nullptr);
    const char* v=g_theme.background;
    if(std::strcmp(k,"surface")==0)v=g_theme.surface;
    else if(std::strcmp(k,"surface2")==0)v=g_theme.surface2;
    else if(std::strcmp(k,"border")==0)v=g_theme.border;
    else if(std::strcmp(k,"accent")==0)v=g_theme.accent;
    else if(std::strcmp(k,"accent2")==0)v=g_theme.accent2;
    else if(std::strcmp(k,"text")==0)v=g_theme.text;
    else if(std::strcmp(k,"muted")==0)v=g_theme.muted;
    else if(std::strcmp(k,"good")==0)v=g_theme.good;
    else if(std::strcmp(k,"warn")==0)v=g_theme.warn;
    else if(std::strcmp(k,"bad")==0)v=g_theme.bad;
    env->ReleaseStringUTFChars(key,k);
    return env->NewStringUTF(v);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_noir_game_engine_NoirNative_themeMetrics(JNIEnv* env,jclass){
    jfloatArray out=env->NewFloatArray(2);
    if(!out)return nullptr;
    const float v[2]={g_theme.corner,g_theme.spacing};
    env->SetFloatArrayRegion(out,0,2,v);
    return out;
}
