#include <jni.h>
#include <dirent.h>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>

namespace {
static bool endsWithDll(const char* name){
    if(!name)return false;
    size_t n=std::strlen(name);
    return n>=4 && std::strcmp(name+n-4,".dll")==0;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noir_game_engine_NoirNative_csharpToolchainScan(JNIEnv* env,jclass,jstring path){
    if(!path)return env->NewStringUTF("C# toolchain: no directory");
    const char* raw=env->GetStringUTFChars(path,nullptr);
    if(!raw)return env->NewStringUTF("C# toolchain: invalid path");
    DIR* dir=opendir(raw);
    if(!dir){
        env->ReleaseStringUTFChars(path,raw);
        return env->NewStringUTF("C# toolchain: directory unavailable");
    }

    std::vector<std::string> dlls;
    bool core=false,compiler=false,roslyn=false;
    dirent* entry=nullptr;
    while((entry=readdir(dir))!=nullptr){
        if(!endsWithDll(entry->d_name))continue;
        std::string name(entry->d_name);
        dlls.push_back(name);
        if(name=="Noir.dll")core=true;
        else if(name=="Noir.CSharp.Compiler.dll")compiler=true;
        else if(name=="Microsoft.CodeAnalysis.CSharp.dll")roslyn=true;
    }
    closedir(dir);
    env->ReleaseStringUTFChars(path,raw);

    std::sort(dlls.begin(),dlls.end());
    std::string out="C# toolchain DLLs="+std::to_string(dlls.size())+
        " | Noir="+(core?std::string("OK"):std::string("MISSING"))+
        " | Compiler="+(compiler?std::string("OK"):std::string("MISSING"))+
        " | Roslyn="+(roslyn?std::string("OK"):std::string("MISSING"));
    if(!dlls.empty()){
        out+=" | Files:";
        size_t limit=std::min<size_t>(dlls.size(),32);
        for(size_t i=0;i<limit;i++)out+=" "+dlls[i];
        if(dlls.size()>limit)out+=" ...";
    }
    return env->NewStringUTF(out.c_str());
}
