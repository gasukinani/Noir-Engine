#include "noir_graphics.h"
#include "noir_world_scene.h"

#include <GLES3/gl3.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <chrono>
#include <string>
#include <cstdio>
#include <jni.h>

#define NOIR_LOG(...) __android_log_print(ANDROID_LOG_INFO, "NoirGfx", __VA_ARGS__)

static std::string g_lastError;

static void setNativeError(const char* stage, const char* detail) {
    g_lastError = std::string(stage) + ": " + (detail ? detail : "unknown error");
    NOIR_LOG("%s", g_lastError.c_str());
}

static bool checkGl(const char* stage) {
    GLenum err = glGetError();
    if (err == GL_NO_ERROR) return true;
    char msg[96];
    std::snprintf(msg, sizeof(msg), "OpenGL ES error 0x%04x", static_cast<unsigned>(err));
    setNativeError(stage, msg);
    return false;
}

namespace noir::gfx {

namespace {

struct Vec3 {
    float x,y,z;
    Vec3 operator+(const Vec3& b) const { return {x+b.x,y+b.y,z+b.z}; }
    Vec3 operator-(const Vec3& b) const { return {x-b.x,y-b.y,z-b.z}; }
    Vec3 operator*(float s) const { return {x*s,y*s,z*s}; }
};

static float dot(const Vec3&a,const Vec3&b){return a.x*b.x+a.y*b.y+a.z*b.z;}
static Vec3 cross(const Vec3&a,const Vec3&b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
static Vec3 norm(Vec3 v){float l=std::sqrt(std::max(1e-8f,dot(v,v)));return v*(1.0f/l);}

struct Mat4 { float m[16]; };

static Mat4 identity(){
    Mat4 r{};
    r.m[0]=r.m[5]=r.m[10]=r.m[15]=1.0f;
    return r;
}
static Mat4 mul(const Mat4&a,const Mat4&b){
    Mat4 r{};
    for(int c=0;c<4;c++) for(int row=0;row<4;row++)
        r.m[c*4+row]=a.m[0*4+row]*b.m[c*4+0]+a.m[1*4+row]*b.m[c*4+1]+
                     a.m[2*4+row]*b.m[c*4+2]+a.m[3*4+row]*b.m[c*4+3];
    return r;
}
static Mat4 perspective(float fov,float aspect,float zn,float zf){
    Mat4 r{};
    float f=1.0f/std::tan(fov*0.5f);
    r.m[0]=f/aspect;r.m[5]=f;r.m[10]=(zf+zn)/(zn-zf);
    r.m[11]=-1.0f;r.m[14]=(2.0f*zf*zn)/(zn-zf);
    return r;
}
static Mat4 lookAt(Vec3 eye,Vec3 center,Vec3 up){
    Vec3 f=norm(center-eye),s=norm(cross(f,up)),u=cross(s,f);
    Mat4 r=identity();
    r.m[0]=s.x;r.m[1]=s.y;r.m[2]=s.z;
    r.m[4]=u.x;r.m[5]=u.y;r.m[6]=u.z;
    r.m[8]=-f.x;r.m[9]=-f.y;r.m[10]=-f.z;
    r.m[12]=-dot(s,eye);r.m[13]=-dot(u,eye);r.m[14]=dot(f,eye);
    return r;
}
static Mat4 rotationX(float a){
    Mat4 r=identity();float c=std::cos(a),s=std::sin(a);
    r.m[5]=c;r.m[6]=s;r.m[9]=-s;r.m[10]=c;return r;
}
static Mat4 rotationY(float a){
    Mat4 r=identity();float c=std::cos(a),s=std::sin(a);
    r.m[0]=c;r.m[2]=-s;r.m[8]=s;r.m[10]=c;return r;
}
static Mat4 rotationZ(float a){
    Mat4 r=identity();float c=std::cos(a),s=std::sin(a);
    r.m[0]=c;r.m[1]=s;r.m[4]=-s;r.m[5]=c;return r;
}
static Mat4 model(float x,float y,float z,float rx,float ry,float rz,float sx,float sy,float sz){
    Mat4 r=mul(identity(),rotationZ(rz));
    r=mul(r,rotationY(ry));
    r=mul(r,rotationX(rx));
    Mat4 s=identity();s.m[0]=sx;s.m[5]=sy;s.m[10]=sz;
    r=mul(r,s);
    r.m[12]=x;r.m[13]=y;r.m[14]=z;
    return r;
}
static float terrainHeight(float x,float z){
    return 0.22f*std::sin(x*0.38f)+0.18f*std::cos(z*0.31f)
         +0.12f*std::sin((x+z)*0.67f)+0.07f*std::cos((x-z)*1.17f);
}
static GLuint compile(GLenum type,const char*src){
    GLuint s=glCreateShader(type);
    if(!s){ setNativeError("glCreateShader", "returned 0"); return 0; }
    glShaderSource(s,1,&src,nullptr);
    glCompileShader(s);
    GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){
        char log[2048];GLsizei n=0;glGetShaderInfoLog(s,sizeof(log),&n,log);
        setNativeError("shader compile", log);
        glDeleteShader(s);return 0;
    }
    return s;
}
static GLuint program(const char*vs,const char*fs){
    GLuint v=compile(GL_VERTEX_SHADER,vs),f=compile(GL_FRAGMENT_SHADER,fs);
    if(!v||!f){if(v)glDeleteShader(v);if(f)glDeleteShader(f);return 0;}
    GLuint p=glCreateProgram();glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);
    glDeleteShader(v);glDeleteShader(f);
    GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);
    if(!ok){
        char log[2048];GLsizei n=0;glGetProgramInfoLog(p,sizeof(log),&n,log);
        setNativeError("program link", log);glDeleteProgram(p);return 0;
    }
    return p;
}

static constexpr const char* kVs = R"GLSL(
#version 300 es
precision highp float;
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
uniform mat4 uVP;
uniform mat4 uModel;
out vec3 vWorld;
out vec3 vNormal;
void main(){
    vec4 wp=uModel*vec4(aPos,1.0);
    vWorld=wp.xyz;
    vNormal=normalize(mat3(uModel)*aNormal);
    gl_Position=uVP*wp;
}
)GLSL";

static constexpr const char* kFs = R"GLSL(
#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
uniform vec3 uCamera;
uniform vec3 uColor;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uSkyColor;
uniform float uRoughness;
uniform float uMetallic;
uniform float uExposure;
uniform float uFog;
uniform float uQuality;
out vec4 frag;

float sat(float x){return clamp(x,0.0,1.0);}
vec3 fresnel(vec3 f0,float cosTheta){return f0+(1.0-f0)*pow(1.0-cosTheta,5.0);}
float distribution(float NoH,float a){
    float a2=a*a;float d=NoH*NoH*(a2-1.0)+1.0;
    return a2/max(0.0001,3.14159265*d*d);
}
float visibility(float NoV,float NoL,float a){
    float k=(a+1.0)*(a+1.0)/8.0;
    float gv=NoV/(NoV*(1.0-k)+k),gl=NoL/(NoL*(1.0-k)+k);
    return gv*gl;
}
void main(){
    vec3 N=normalize(vNormal);
    vec3 V=normalize(uCamera-vWorld);
    vec3 L=normalize(-uSunDir);
    vec3 H=normalize(V+L);
    float NoV=sat(dot(N,V)),NoL=sat(dot(N,L)),NoH=sat(dot(N,H)),VoH=sat(dot(V,H));
    float rough=max(0.08,uRoughness),a=rough*rough;
    vec3 f0=mix(vec3(0.04),uColor,uMetallic);
    vec3 F=fresnel(f0,VoH);
    float D=distribution(NoH,a),G=visibility(NoV,NoL,rough);
    vec3 spec=(D*G*F)/max(0.001,4.0*NoV*NoL);
    spec*=mix(0.72,1.0,clamp((uQuality-1.0)/3.0,0.0,1.0));
    vec3 kd=(1.0-F)*(1.0-uMetallic);
    vec3 diffuse=kd*uColor/3.14159265;
    vec3 direct=(diffuse+spec)*uSunColor*NoL;
    vec3 ambient=uSkyColor*(0.16+0.42*NoV)*uColor;
    float edge=smoothstep(0.0,1.0,sat((vWorld.y+0.25)*0.25));
    vec3 color=(direct+ambient)*(0.72+0.28*edge)*uExposure;
    color=vec3(1.0)-exp(-color);
    color=pow(color,vec3(0.4545));
    float dist=length(uCamera-vWorld);
    float fog=1.0-exp(-dist*uFog);
    vec3 fogColor=vec3(0.16,0.23,0.34);
    color=mix(color,fogColor,sat(fog));
    frag=vec4(color,1.0);
}
)GLSL";

static constexpr const char* kSafeVs = R"GLSL(
#version 300 es
precision mediump float;
const vec3 P[8]=vec3[8](
    vec3(-1,-1,-1),vec3(1,-1,-1),vec3(1,1,-1),vec3(-1,1,-1),
    vec3(-1,-1,1),vec3(1,-1,1),vec3(1,1,1),vec3(-1,1,1));
const int I[36]=int[36](
    0,1,2,0,2,3, 5,4,7,5,7,6, 4,0,3,4,3,7,
    1,5,6,1,6,2, 3,2,6,3,6,7, 4,5,1,4,1,0);
uniform mat4 uVP;
uniform mat4 uModel;
out vec3 vN;
void main(){
    int id=I[gl_VertexID];
    vec3 p=P[id];
    int face=gl_VertexID/6;
    vN=face==0?vec3(0,0,-1):face==1?vec3(0,0,1):face==2?vec3(-1,0,0):
       face==3?vec3(1,0,0):face==4?vec3(0,1,0):vec3(0,-1,0);
    gl_Position=uVP*uModel*vec4(p,1.0);
}
)GLSL";

static constexpr const char* kSafeFs = R"GLSL(
#version 300 es
precision mediump float;
in vec3 vN;
uniform vec3 uColor;
uniform vec3 uSunDir;
out vec4 frag;
void main(){
    vec3 N=normalize(vN);
    float ndl=max(dot(N,normalize(-uSunDir)),0.0);
    vec3 c=uColor*(0.28+0.72*ndl);
    c=vec3(1.0)-exp(-c*1.25);
    frag=vec4(pow(c,vec3(0.4545)),1.0);
}
)GLSL";

static constexpr const char* kSafeSkyVs = R"GLSL(
#version 300 es
precision mediump float;
out vec2 uv;
void main(){
    const vec2 P[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
    uv=P[gl_VertexID]*0.5+0.5;
    gl_Position=vec4(P[gl_VertexID],0,1);
}
)GLSL";

static constexpr const char* kSafeSkyFs = R"GLSL(
#version 300 es
precision mediump float;
in vec2 uv;
uniform vec3 uSunDir;
out vec4 frag;
void main(){
    float h=clamp(uv.y,0.0,1.0);
    vec3 top=vec3(0.05,0.16,0.34);
    vec3 horizon=vec3(0.55,0.66,0.78);
    vec3 c=mix(horizon,top,pow(h,0.62));
    vec2 p=uv*2.0-1.0;
    vec3 ray=normalize(vec3(p.x*1.35,p.y,1.0));
    float sun=max(dot(ray,normalize(-uSunDir)),0.0);
    c+=vec3(1.0,0.68,0.34)*pow(sun,96.0)*0.7;
    frag=vec4(c,1.0);
}
)GLSL";

static constexpr const char* kSkyVs = R"GLSL(
#version 300 es
precision highp float;
const vec2 p[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
out vec2 uv;
void main(){uv=p[gl_VertexID]*0.5+0.5;gl_Position=vec4(p[gl_VertexID],0,1);}
)GLSL";

static constexpr const char* kSkyFs = R"GLSL(
#version 300 es
precision highp float;
in vec2 uv;
uniform vec3 uTop;
uniform vec3 uHorizon;
uniform vec3 uSunDir;
uniform float uPitch;
uniform float uSkyMode;
out vec4 frag;

float hash21(vec2 p){
    p=fract(p*vec2(123.34,456.21));
    p+=dot(p,p+45.32);
    return fract(p.x*p.y);
}
float noise2(vec2 p){
    vec2 i=floor(p),f=fract(p);
    f=f*f*(3.0-2.0*f);
    float a=hash21(i);
    float b=hash21(i+vec2(1.0,0.0));
    float c=hash21(i+vec2(0.0,1.0));
    float d=hash21(i+vec2(1.0,1.0));
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
}
float fbm(vec2 p){
    float v=0.0,a=0.52;
    for(int i=0;i<4;i++){v+=noise2(p)*a;p=p*2.03+7.1;a*=0.5;}
    return v;
}
void main(){
    vec2 p=uv*2.0-1.0;
    float h=clamp(uv.y,0.0,1.0);
    vec3 ray=normalize(vec3(p.x*1.35,p.y,1.0));
    vec3 sunDir=normalize(-uSunDir);
    float sunDot=max(dot(ray,sunDir),0.0);
    vec3 c=mix(uHorizon,uTop,pow(h,0.62));

    if(uSkyMode>0.5 && uSkyMode<1.5){
        float rayleigh=pow(1.0-max(ray.y,0.0),1.65);
        float mie=pow(sunDot,8.0);
        float horizon=smoothstep(-0.20,0.55,ray.y);
        vec3 scatter=vec3(0.24,0.38,0.68)*rayleigh+
                      vec3(0.84,0.88,0.98)*(0.20+0.62*horizon)+
                      vec3(1.0,0.46,0.18)*mie*0.55;
        c=mix(scatter,c,0.28);
    }else if(uSkyMode>2.5 && uSkyMode<3.5){
        c=mix(c,vec3(0.07,0.12,0.24),smoothstep(0.0,0.9,1.0-h));
        c+=vec3(0.55,0.30,0.16)*pow(sunDot,18.0);
    }

    // Low-cost mobile cloud layer: broad billows + a high-frequency breakup.
    float cloudLayer=smoothstep(0.47,0.73,fbm(vec2(uv.x*3.8+h*1.7,uv.y*2.5)));
    cloudLayer*=smoothstep(0.08,0.70,uv.y);
    cloudLayer*=uSkyMode==3.0?0.35:0.70;
    vec3 cloudTint=vec3(0.94,0.97,1.0);
    c=mix(c,cloudTint,cloudLayer*0.52);

    float disc=pow(sunDot,160.0);
    float halo=pow(sunDot,18.0)*0.20;
    c+=vec3(1.0,0.72,0.40)*(disc+halo);

    // Gentle horizon haze helps large outdoor scenes read with depth.
    float haze=smoothstep(-0.10,0.35,ray.y);
    c=mix(c,vec3(0.60,0.72,0.86),0.06*(1.0-haze));
    frag=vec4(c,1.0);
}
)GLSL";

struct Vertex { float px,py,pz,nx,ny,nz; };
struct SceneInstance {
    float x,y,z;
    float sx,sy,sz;
    float rx,ry,rz;
    int kind;
};

static void addCone(std::vector<Vertex>& out,float radius,float height,int sides){
    const float pi=3.14159265359f;
    for(int i=0;i<sides;i++){
        float a0=2.0f*pi*float(i)/float(sides);
        float a1=2.0f*pi*float(i+1)/float(sides);
        float x0=std::cos(a0)*radius,z0=std::sin(a0)*radius;
        float x1=std::cos(a1)*radius,z1=std::sin(a1)*radius;
        float nx=std::cos((a0+a1)*0.5f),nz=std::sin((a0+a1)*0.5f);
        // Side
        out.push_back({x0,0,z0,nx,0.7f,nz});
        out.push_back({x1,0,z1,nx,0.7f,nz});
        out.push_back({0,height,0,nx,0.7f,nz});
        // Ground cap
        out.push_back({0,0,0,0,-1,0});
        out.push_back({x1,0,z1,0,-1,0});
        out.push_back({x0,0,z0,0,-1,0});
    }
}

static void addRock(std::vector<Vertex>& out){
    const Vec3 v[6]={{0,1.0f,0},{0,-0.8f,0},{-1,0,0},{1,0,0},{0,0,-1},{0,0,1}};
    const int tri[8][3]={{0,2,4},{0,4,3},{0,3,5},{0,5,2},{1,4,2},{1,3,4},{1,5,3},{1,2,5}};
    for(const auto& t:tri){
        Vec3 a=v[t[0]],b=v[t[1]],cc=v[t[2]];
        Vec3 n=norm(cross(b-a,cc-a));
        out.push_back({a.x,a.y,a.z,n.x,n.y,n.z});
        out.push_back({b.x,b.y,b.z,n.x,n.y,n.z});
        out.push_back({cc.x,cc.y,cc.z,n.x,n.y,n.z});
    }
}

static void addTerrain(std::vector<Vertex>& out,float size,int cells){
    float step=(size*2.0f)/float(cells);
    for(int z=0;z<cells;z++)for(int x=0;x<cells;x++){
        float x0=-size+x*step,x1=x0+step,z0=-size+z*step,z1=z0+step;
        float h00=terrainHeight(x0,z0),h10=terrainHeight(x1,z0),h01=terrainHeight(x0,z1),h11=terrainHeight(x1,z1);
        Vec3 a{x0,h00,z0},b{x1,h10,z0},cc{x1,h11,z1},d{x0,h01,z1};
        Vec3 n0=norm(cross(b-a,cc-a)),n1=norm(cross(cc-a,d-a));
        out.push_back({a.x,a.y,a.z,n0.x,n0.y,n0.z});
        out.push_back({b.x,b.y,b.z,n0.x,n0.y,n0.z});
        out.push_back({cc.x,cc.y,cc.z,n0.x,n0.y,n0.z});
        out.push_back({a.x,a.y,a.z,n1.x,n1.y,n1.z});
        out.push_back({cc.x,cc.y,cc.z,n1.x,n1.y,n1.z});
        out.push_back({d.x,d.y,d.z,n1.x,n1.y,n1.z});
    }
}
static void addWater(std::vector<Vertex>& out,float size,float y){
    float s=size;
    Vec3 a{-s,y,-s},b{s,y,-s},cc{s,y,s},d{-s,y,s},n{0,1,0};
    out.push_back({a.x,a.y,a.z,n.x,n.y,n.z});out.push_back({b.x,b.y,b.z,n.x,n.y,n.z});out.push_back({cc.x,cc.y,cc.z,n.x,n.y,n.z});
    out.push_back({a.x,a.y,a.z,n.x,n.y,n.z});out.push_back({cc.x,cc.y,cc.z,n.x,n.y,n.z});out.push_back({d.x,d.y,d.z,n.x,n.y,n.z});
}

static void addCube(std::vector<Vertex>& out,float x,float y,float z,float sx,float sy,float sz){
    static const float p[36][6]={
        {-1,-1,-1,0,0,-1},{1,-1,-1,0,0,-1},{1,1,-1,0,0,-1},
        {-1,-1,-1,0,0,-1},{1,1,-1,0,0,-1},{-1,1,-1,0,0,-1},
        {-1,-1,1,0,0,1},{-1,1,1,0,0,1},{1,1,1,0,0,1},
        {-1,-1,1,0,0,1},{1,1,1,0,0,1},{1,-1,1,0,0,1},
        {-1,-1,-1,-1,0,0},{-1,1,-1,-1,0,0},{-1,1,1,-1,0,0},
        {-1,-1,-1,-1,0,0},{-1,1,1,-1,0,0},{-1,-1,1,-1,0,0},
        {1,-1,1,1,0,0},{1,1,1,1,0,0},{1,1,-1,1,0,0},
        {1,-1,1,1,0,0},{1,1,-1,1,0,0},{1,-1,-1,1,0,0},
        {-1,1,-1,0,1,0},{1,1,-1,0,1,0},{1,1,1,0,1,0},
        {-1,1,-1,0,1,0},{1,1,1,0,1,0},{-1,1,1,0,1,0},
        {-1,-1,1,0,-1,0},{1,-1,1,0,-1,0},{1,-1,-1,0,-1,0},
        {-1,-1,1,0,-1,0},{1,-1,-1,0,-1,0},{-1,-1,-1,0,-1,0}
    };
    for(const auto& q:p)out.push_back({x+q[0]*sx,y+q[1]*sy,z+q[2]*sz,q[3],q[4],q[5]});
}

} // namespace

struct Renderer::Impl {
    GLuint pbr=0,sky=0,safe=0,safeSky=0,vao=0,vbo=0,skyVao=0;
    GLuint shapeVbo[5]{},shapeVao[5]{};
    int shapeCount[5]{};
    std::vector<Vertex> objects;
    std::vector<SceneInstance> scene;
    int width=1,height=1;
    float frameMs=16.6f;
    bool ready=false;
    int skyMode=2;
    float environmentExposure=1.0f;
    float skyBrightness=1.0f;
    float fogDensity=0.018f;
    Vec3 sunDir{-0.38f,-0.82f,-0.32f};
    float qualityTier=1.0f;
    bool safeMode=false;

    void destroy(){
        if(vbo)glDeleteBuffers(1,&vbo);
        if(vao)glDeleteVertexArrays(1,&vao);
        if(skyVao)glDeleteVertexArrays(1,&skyVao);
        for(int i=0;i<5;i++){
            if(shapeVbo[i])glDeleteBuffers(1,&shapeVbo[i]);
            if(shapeVao[i])glDeleteVertexArrays(1,&shapeVao[i]);
            shapeVbo[i]=shapeVao[i]=0;shapeCount[i]=0;
        }
        if(pbr)glDeleteProgram(pbr);
        if(sky)glDeleteProgram(sky);
        if(safe)glDeleteProgram(safe);
        if(safeSky)glDeleteProgram(safeSky);
        vbo=vao=skyVao=pbr=sky=safe=safeSky=0;ready=false;safeMode=false;
    }

    bool build(){
        while(glGetError()!=GL_NO_ERROR) {}
        g_lastError.clear();
        safeMode=false;
        pbr=program(kVs,kFs);
        sky=program(kSkyVs,kSkyFs);
        if(!pbr||!sky){
            if(pbr)glDeleteProgram(pbr);
            if(sky)glDeleteProgram(sky);
            pbr=sky=0;
            safe=program(kSafeVs,kSafeFs);
            safeSky=program(kSafeSkyVs,kSafeSkyFs);
            if(!safe||!safeSky){
                if(safe)glDeleteProgram(safe);
                if(safeSky)glDeleteProgram(safeSky);
                safe=safeSky=0;
                setNativeError("renderer initialization", "both PBR and safe shaders failed");
                return false;
            }
            glGenVertexArrays(1,&shapeVao[0]);
            glBindVertexArray(shapeVao[0]);
            std::vector<Vertex> cube;
            addCube(cube,0,0,0,1,1,1);
            shapeCount[0]=static_cast<int>(cube.size());
            glGenBuffers(1,&shapeVbo[0]);
            glBindBuffer(GL_ARRAY_BUFFER,shapeVbo[0]);
            glBufferData(GL_ARRAY_BUFFER,cube.size()*sizeof(Vertex),cube.data(),GL_STATIC_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),(void*)0);
            glBindVertexArray(0);
            glGenVertexArrays(1,&skyVao);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDisable(GL_CULL_FACE);
            safeMode=true;
            ready=true;
            NOIR_LOG("Complex native PBR failed; safe native C++ world renderer active");
            return true;
        }

        objects.clear();
        for(int z=-3;z<=3;z++) for(int x=-3;x<=3;x++){
            float wx=x*2.4f,wz=z*2.4f,h=terrainHeight(wx,wz);
            addCube(objects,wx,h-0.05f,wz,1.15f,0.08f+std::max(0.0f,h)*0.08f,1.15f);
        }
        addCube(objects,0,1.15f,0,1.7f,1.15f,1.7f);
        addCube(objects,-7,1.0f,-6,1.25f,1.0f,1.25f);
        addCube(objects,7,1.35f,-6,1.45f,1.35f,1.45f);
        addCube(objects,-7,0.85f,7,1.05f,0.85f,1.05f);
        addCube(objects,7,1.1f,7,1.3f,1.1f,1.3f);

        for(int i=0;i<10;i++){
            float a=float(i)*6.2831853f/10.0f;
            float x=std::cos(a)*(9.0f+float(i%3)*1.5f);
            float z=std::sin(a)*(9.0f+float(i%2)*1.8f);
            float h=terrainHeight(x,z);
            addCube(objects,x,h+1.0f,z,0.22f,1.0f,0.22f);
            addCube(objects,x,h+2.0f,z,0.82f,0.95f,0.82f);
        }

        glGenVertexArrays(1,&vao);glGenBuffers(1,&vbo);
        glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glBufferData(GL_ARRAY_BUFFER,objects.size()*sizeof(Vertex),objects.data(),GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),(void*)0);
        glEnableVertexAttribArray(1);glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),(void*)(3*sizeof(float)));
        glBindVertexArray(0);
        std::vector<Vertex> shapes[5];
        addCube(shapes[0],0,0,0,1,1,1);
        addCone(shapes[1],1.0f,2.0f,12);
        addRock(shapes[2]);
        addTerrain(shapes[3],1.0f,18);
        addWater(shapes[4],1.0f,0.0f);
        for(int s=0;s<5;s++){
            shapeCount[s]=static_cast<int>(shapes[s].size());
            glGenVertexArrays(1,&shapeVao[s]);
            glGenBuffers(1,&shapeVbo[s]);
            glBindVertexArray(shapeVao[s]);glBindBuffer(GL_ARRAY_BUFFER,shapeVbo[s]);
            glBufferData(GL_ARRAY_BUFFER,shapes[s].size()*sizeof(Vertex),shapes[s].data(),GL_STATIC_DRAW);
            glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),(void*)0);
            glEnableVertexAttribArray(1);glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,sizeof(Vertex),(void*)(3*sizeof(float)));
            glBindVertexArray(0);
        }
        glGenVertexArrays(1,&skyVao);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDisable(GL_CULL_FACE);
        if(!checkGl("renderer initialization")) return false;
        ready=true;return true;
    }

    void setScene(const std::vector<SceneInstance>& in){
        scene=in;
        if(scene.empty()){
            std::vector<noir::world::Instance> fallback;
            noir::world::buildDefaultWorld(fallback);
            scene.reserve(fallback.size());
            for(const auto& w:fallback)scene.push_back({w.x,w.y,w.z,w.sx,w.sy,w.sz,w.rx,w.ry,w.rz,w.kind});
        }
    }
    void setEnvironment(int mode,float exposure,float brightness,float fog,const Vec3& sun){
        skyMode=std::max(0,mode);
        environmentExposure=std::max(0.05f,exposure);
        skyBrightness=std::max(0.0f,brightness);
        fogDensity=std::max(0.0f,fog*12.0f);
        float sunLen=dot(sun,sun);
        sunDir=sunLen>0.0001f?norm(sun):Vec3{-0.38f,-0.82f,-0.32f};
    }

    static Vec3 colorForKind(int kind){
        switch(kind){
            case 1: return {0.62f,0.38f,0.16f};   // CHARACTER3D
            case 2: return {0.20f,0.52f,0.90f};   // PLAYER3D
            case 3: return {0.10f,0.75f,0.96f};   // CAMERA3D
            case 4: return {1.00f,0.78f,0.20f};   // LIGHT3D
            case 5: return {0.34f,0.55f,0.82f};   // MESH3D
            case 9: return {0.34f,0.62f,0.70f};   // STATIC_BODY3D
            case 15:return {0.12f,0.46f,0.70f};   // WATER3D
            case 16:return {0.24f,0.52f,0.28f};   // TERRAIN3D
            case 17:return {0.20f,0.60f,0.27f};   // FOLIAGE3D
            case 21:return {0.65f,0.46f,0.88f};   // REFLECTION_PROBE3D
            case 24:return {0.75f,0.50f,0.25f};   // SKY3D
            case 36:return {0.42f,0.39f,0.34f};   // ROCK3D
            default:return {0.47f,0.52f,0.60f};
        }
    }

    void drawSafeScene(const Mat4& vp,const Vec3& cam){
        glUseProgram(safe);
        glBindVertexArray(shapeVao[0]);
        GLint vpLoc=glGetUniformLocation(safe,"uVP");
        GLint modelLoc=glGetUniformLocation(safe,"uModel");
        GLint colorLoc=glGetUniformLocation(safe,"uColor");
        GLint sunLoc=glGetUniformLocation(safe,"uSunDir");
        glUniformMatrix4fv(vpLoc,1,GL_FALSE,vp.m);
        glUniform3f(sunLoc,sunDir.x,sunDir.y,sunDir.z);
        if(scene.empty()){
            Mat4 m=model(0,-0.65f,0,0,0,0,20.0f,0.5f,20.0f);
            glUniformMatrix4fv(modelLoc,1,GL_FALSE,m.m);
            glUniform3f(colorLoc,0.22f,0.30f,0.22f);
            glDrawArrays(GL_TRIANGLES,0,shapeCount[0]);
            for(int i=0;i<6;i++){
                float a=float(i)*1.04719755f;
                m=model(std::cos(a)*6.0f,0.9f,std::sin(a)*6.0f,0,0,0,1.0f,1.8f,1.0f);
                glUniformMatrix4fv(modelLoc,1,GL_FALSE,m.m);
                glUniform3f(colorLoc,0.38f+0.04f*i,0.32f+0.025f*i,0.24f);
                glDrawArrays(GL_TRIANGLES,0,shapeCount[0]);
            }
        }else{
            int foliage=0;
            int stride=qualityTier<=1.0f?3:(qualityTier<3.0f?2:1);
            for(const SceneInstance& n:scene){
                if(n.kind==23)continue;
                if(n.kind==17 && ((foliage++)%stride)!=0)continue;
                float sy=std::max(0.05f,std::fabs(n.sy));
                if(n.kind==16)sy*=0.35f;
                if(n.kind==15)sy=0.04f;
                if(n.kind==17)sy*=0.65f;
                Mat4 m=model(n.x,n.y,n.z,n.rx*0.0174532925f,n.ry*0.0174532925f,n.rz*0.0174532925f,
                             std::max(0.05f,std::fabs(n.sx)),sy,std::max(0.05f,std::fabs(n.sz)));
                Vec3 color=colorForKind(n.kind);
                if(n.kind==17) color={0.15f,0.48f,0.20f};
                if(n.kind==15) color={0.08f,0.35f,0.62f};
                glUniformMatrix4fv(modelLoc,1,GL_FALSE,m.m);
                glUniform3f(colorLoc,color.x,color.y,color.z);
                glDrawArrays(GL_TRIANGLES,0,shapeCount[0]);
            }
        }
        glBindVertexArray(0);
    }

    void drawSafeSky(){
        glDisable(GL_DEPTH_TEST);
        glUseProgram(safeSky);
        glBindVertexArray(skyVao);
        GLint sunLoc=glGetUniformLocation(safeSky,"uSunDir");
        glUniform3f(sunLoc,sunDir.x,sunDir.y,sunDir.z);
        glDrawArrays(GL_TRIANGLES,0,3);
        glBindVertexArray(0);
        glEnable(GL_DEPTH_TEST);
    }

    void drawSceneInstances(const Mat4& vp,const Vec3& cam){
        if(scene.empty())return;
        glUseProgram(pbr);
        glBindVertexArray(vao);
        glUniformMatrix4fv(glGetUniformLocation(pbr,"uVP"),1,GL_FALSE,vp.m);
        glUniform3f(glGetUniformLocation(pbr,"uCamera"),cam.x,cam.y,cam.z);
        glUniform3f(glGetUniformLocation(pbr,"uSunDir"),sunDir.x,sunDir.y,sunDir.z);
        glUniform3f(glGetUniformLocation(pbr,"uSunColor"),1.85f,1.62f,1.32f);
        Vec3 sky=skyColor();
        glUniform3f(glGetUniformLocation(pbr,"uSkyColor"),sky.x,sky.y,sky.z);
        glUniform1f(glGetUniformLocation(pbr,"uExposure"),environmentExposure);
        glUniform1f(glGetUniformLocation(pbr,"uFog"),fogDensity);
        glUniform1f(glGetUniformLocation(pbr,"uQuality"),qualityTier);
        glUniform1f(glGetUniformLocation(pbr,"uRoughness"),0.58f);
        glUniform1f(glGetUniformLocation(pbr,"uMetallic"),0.06f);
        int visibleFoliage=0;
        int foliageStride=qualityTier<=1.0f?3:(qualityTier<3.0f?2:1);
        for(const SceneInstance& n:scene){
            if(n.kind==23)continue;
            if(n.kind==17 && ((visibleFoliage++)%foliageStride)!=0)continue;
            Mat4 m=model(n.x,n.y,n.z,n.rx*0.0174532925f,n.ry*0.0174532925f,n.rz*0.0174532925f,
                         std::max(0.05f,std::fabs(n.sx)),std::max(0.05f,std::fabs(n.sy)),std::max(0.05f,std::fabs(n.sz)));
            Vec3 color=colorForKind(n.kind);
            glUniform3f(glGetUniformLocation(pbr,"uColor"),color.x,color.y,color.z);
            glUniformMatrix4fv(glGetUniformLocation(pbr,"uModel"),1,GL_FALSE,m.m);
            int shape=(n.kind==17)?1:(n.kind==36?2:(n.kind==16?3:(n.kind==15?4:0)));
            glBindVertexArray(shapeVao[shape]);
            glDrawArrays(GL_TRIANGLES,0,shapeCount[shape]);
        }
        glBindVertexArray(0);
    }

    Vec3 skyColor() const {
        switch(skyMode){
            case 1:return {0.32f,0.46f,0.68f}; // physical
            case 3:return {0.20f,0.30f,0.52f}; // shader material
            case 4:return {0.18f,0.28f,0.42f}; // gradient
            case 5:return {0.25f,0.38f,0.58f}; // cubemap
            case 6:return {0.40f,0.50f,0.62f}; // HDRI
            default:return {0.36f,0.50f,0.72f};
        }
    }

    void drawCubeRange(size_t first,size_t count,const Mat4& vp,const Vec3& color,float rough,float metal,
                       const Vec3& cam,const Vec3& sun,const Vec3& skyColor){
        glUseProgram(pbr);
        glBindVertexArray(vao);
        glUniformMatrix4fv(glGetUniformLocation(pbr,"uVP"),1,GL_FALSE,vp.m);
        glUniform3f(glGetUniformLocation(pbr,"uCamera"),cam.x,cam.y,cam.z);
        glUniform3f(glGetUniformLocation(pbr,"uColor"),color.x,color.y,color.z);
        glUniform3f(glGetUniformLocation(pbr,"uSunDir"),sun.x,sun.y,sun.z);
        glUniform3f(glGetUniformLocation(pbr,"uSunColor"),1.8f,1.52f,1.15f);
        glUniform3f(glGetUniformLocation(pbr,"uSkyColor"),skyColor.x,skyColor.y,skyColor.z);
        glUniform1f(glGetUniformLocation(pbr,"uRoughness"),rough);
        glUniform1f(glGetUniformLocation(pbr,"uMetallic"),metal);
        glUniform1f(glGetUniformLocation(pbr,"uQuality"),qualityTier);
        glUniform1f(glGetUniformLocation(pbr,"uExposure"),1.08f);
        glUniform1f(glGetUniformLocation(pbr,"uFog"),0.018f);
        // The geometry is already in world space. uModel remains identity.
        Mat4 id=identity();glUniformMatrix4fv(glGetUniformLocation(pbr,"uModel"),1,GL_FALSE,id.m);
        glDrawArrays(GL_TRIANGLES,(GLint)first,(GLsizei)count);
        glBindVertexArray(0);
    }
};

static Renderer* gRenderer=nullptr;

Renderer::Renderer():impl_(new Impl()){gRenderer=this;}
Renderer::~Renderer(){
    if(gRenderer==this)gRenderer=nullptr;
    if(impl_){ impl_->destroy(); delete impl_; impl_=nullptr; }
}

void Renderer::shutdown(){ if(impl_) impl_->destroy(); }

bool Renderer::initialize(){
    if(!impl_)return false;
    impl_->destroy();
    bool ok=impl_->build();
    if(!ok){
        if(g_lastError.empty()) setNativeError("initialize", "unknown renderer setup failure");
        impl_->destroy();
        NOIR_LOG("NoirGfx initialization failed: %s",g_lastError.c_str());
    }
    return ok;
}
void Renderer::resize(int width,int height){
    impl_->width=std::max(1,width);impl_->height=std::max(1,height);
    glViewport(0,0,impl_->width,impl_->height);
}
void Renderer::setScene(const float* snapshot,int floatCount){
    std::vector<SceneInstance> next;
    if(snapshot&&floatCount>=10){
        int count=std::min(floatCount/10,256);
        next.reserve(count);
        for(int i=0;i<count;i++){
            const float* p=snapshot+i*10;
            SceneInstance n{};
            n.x=p[0];n.y=p[1];n.z=p[2];
            n.sx=p[3];n.sy=p[4];n.sz=p[5];
            n.rx=p[6];n.ry=p[7];n.rz=p[8];
            n.kind=static_cast<int>(std::lround(p[9]));
            next.push_back(n);
        }
    }
    impl_->setScene(next);
}
void Renderer::setEnvironment(int skyMode,float exposure,float skyBrightness,float fogDensity,
                              float sunX,float sunY,float sunZ){
    impl_->setEnvironment(skyMode,exposure,skyBrightness,fogDensity,{sunX,sunY,sunZ});
}
void Renderer::setQuality(int qualityTier){
    impl_->qualityTier=std::max(1.0f,std::min(4.0f,float(qualityTier)));
}
void Renderer::frame(float yawDeg,float pitchDeg,float distance,float tx,float ty,float tz,bool editorMode){
    if(!impl_->ready)return;
    auto start=std::chrono::steady_clock::now();

    glViewport(0,0,impl_->width,impl_->height);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.035f,0.055f,0.085f,1.0f);
    glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);

    float yr=yawDeg*0.01745329252f,pr=pitchDeg*0.01745329252f;
    Vec3 target{tx,ty,tz};
    Vec3 cam{tx+std::cos(pr)*std::cos(yr)*distance,
             ty+std::sin(pr)*distance,
             tz+std::cos(pr)*std::sin(yr)*distance};

    Mat4 vp=mul(perspective(1.11701f,float(impl_->width)/float(impl_->height),0.05f,180.0f),
                lookAt(cam,target,{0,1,0}));

    if(impl_->safeMode){
        impl_->drawSafeSky();
        glEnable(GL_DEPTH_TEST);
        glClear(GL_DEPTH_BUFFER_BIT);
        impl_->drawSafeScene(vp,cam);
        auto end=std::chrono::steady_clock::now();
        impl_->frameMs=std::chrono::duration<float,std::milli>(end-start).count();
        return;
    }

    // No flat blue clear: a deep-space gradient is drawn first, then the
    // procedural world is rendered on top.
    glDisable(GL_DEPTH_TEST);
    glUseProgram(impl_->sky);
    glBindVertexArray(impl_->skyVao);
    Vec3 skyTop=impl_->skyColor();
    Vec3 skyHorizon{skyTop.x*2.4f,skyTop.y*2.1f,skyTop.z*1.85f};
    if(impl_->skyMode==1){
        skyTop={0.20f,0.35f,0.62f};
        skyHorizon={0.52f,0.58f,0.68f};
    }else if(impl_->skyMode==3){
        skyTop={0.09f,0.16f,0.30f};
        skyHorizon={0.34f,0.22f,0.46f};
    }
    skyTop=skyTop*impl_->skyBrightness;
    skyHorizon=skyHorizon*impl_->skyBrightness;
    glUniform3f(glGetUniformLocation(impl_->sky,"uTop"),std::min(1.0f,skyTop.x),std::min(1.0f,skyTop.y),std::min(1.0f,skyTop.z));
    glUniform3f(glGetUniformLocation(impl_->sky,"uHorizon"),std::min(1.0f,skyHorizon.x),std::min(1.0f,skyHorizon.y),std::min(1.0f,skyHorizon.z));
    glUniform3f(glGetUniformLocation(impl_->sky,"uSunDir"),impl_->sunDir.x,impl_->sunDir.y,impl_->sunDir.z);
    glUniform1f(glGetUniformLocation(impl_->sky,"uPitch"),pitchDeg);
    glUniform1f(glGetUniformLocation(impl_->sky,"uSkyMode"),float(impl_->skyMode));
    glDrawArrays(GL_TRIANGLES,0,3);
    glBindVertexArray(0);

    glEnable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glClear(GL_DEPTH_BUFFER_BIT);
    Vec3 sun=impl_->sunDir;
    Vec3 skyColor=impl_->skyColor();

    // The synchronized scene (or the native shared fallback) is the complete world.
    impl_->drawSceneInstances(vp,cam);

    glDisable(GL_CULL_FACE);
    glEnable(GL_DEPTH_TEST);

    auto end=std::chrono::steady_clock::now();
    impl_->frameMs=std::chrono::duration<float,std::milli>(end-start).count();
}
void Renderer::frameRuntime(float x,float y,float z,float yawDeg,float pitchDeg,bool editorMode){
    if(!impl_->ready)return;
    auto start=std::chrono::steady_clock::now();
    glViewport(0,0,impl_->width,impl_->height);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.035f,0.055f,0.085f,1.0f);
    glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);

    float yr=yawDeg*0.01745329252f,pr=pitchDeg*0.01745329252f;
    Vec3 cam{x,y,z};
    Vec3 forward{std::cos(pr)*std::cos(yr),std::sin(pr),std::cos(pr)*std::sin(yr)};
    Vec3 target=cam+forward;
    Mat4 vp=mul(perspective(1.11701f,float(impl_->width)/float(impl_->height),0.05f,180.0f),
                lookAt(cam,target,{0,1,0}));

    if(impl_->safeMode){
        impl_->drawSafeSky();
        glEnable(GL_DEPTH_TEST);
        glClear(GL_DEPTH_BUFFER_BIT);
        impl_->drawSafeScene(vp,cam);
        auto end=std::chrono::steady_clock::now();
        impl_->frameMs=std::chrono::duration<float,std::milli>(end-start).count();
        return;
    }

    glDisable(GL_DEPTH_TEST);
    glUseProgram(impl_->sky);
    glBindVertexArray(impl_->skyVao);
    Vec3 skyTop=impl_->skyColor();
    Vec3 skyHorizon{skyTop.x*2.4f,skyTop.y*2.1f,skyTop.z*1.85f};
    skyTop=skyTop*impl_->skyBrightness;
    skyHorizon=skyHorizon*impl_->skyBrightness;
    glUniform3f(glGetUniformLocation(impl_->sky,"uTop"),std::min(1.0f,skyTop.x),std::min(1.0f,skyTop.y),std::min(1.0f,skyTop.z));
    glUniform3f(glGetUniformLocation(impl_->sky,"uHorizon"),std::min(1.0f,skyHorizon.x),std::min(1.0f,skyHorizon.y),std::min(1.0f,skyHorizon.z));
    glUniform3f(glGetUniformLocation(impl_->sky,"uSunDir"),impl_->sunDir.x,impl_->sunDir.y,impl_->sunDir.z);
    glUniform1f(glGetUniformLocation(impl_->sky,"uPitch"),pitchDeg);
    glUniform1f(glGetUniformLocation(impl_->sky,"uSkyMode"),float(impl_->skyMode));
    glDrawArrays(GL_TRIANGLES,0,3);
    glBindVertexArray(0);

    glEnable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glClear(GL_DEPTH_BUFFER_BIT);
    impl_->drawSceneInstances(vp,cam);

    auto end=std::chrono::steady_clock::now();
    impl_->frameMs=std::chrono::duration<float,std::milli>(end-start).count();
}

float Renderer::frameTimeMs() const{return impl_->frameMs;}
const char* Renderer::backendInfo() const{return "NoirGFX C++ / OpenGL ES 3.0 • mobile forward PBR";}
const char* Renderer::lastError() const{return g_lastError.c_str();}
bool Renderer::safeMode() const{return impl_ && impl_->safeMode;}


extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_graphicsFrameRuntime
  (JNIEnv*,jclass,jfloat x,jfloat y,jfloat z,jfloat yaw,jfloat pitch,jboolean editorMode){
    if(!gRenderer)return;
    gRenderer->frameRuntime(x,y,z,yaw,pitch,editorMode);
}

extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_graphicsSetScene
  (JNIEnv* env,jclass,jfloatArray snapshot){
    if(!gRenderer||!snapshot)return;
    jsize len=env->GetArrayLength(snapshot);
    if(len<=0){gRenderer->setScene(nullptr,0);return;}
    std::vector<jfloat> data(static_cast<size_t>(len));
    env->GetFloatArrayRegion(snapshot,0,len,data.data());
    gRenderer->setScene(data.data(),static_cast<int>(len));
}

extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_graphicsSetQuality
  (JNIEnv*,jclass,jint qualityTier){
    if(!gRenderer)return;
    gRenderer->setQuality(static_cast<int>(qualityTier));
}

extern "C" JNIEXPORT void JNICALL Java_com_noir_game_engine_NoirNative_graphicsSetEnvironment
  (JNIEnv*,jclass,jint skyMode,jfloat exposure,jfloat brightness,jfloat fogDensity,
   jfloat sunX,jfloat sunY,jfloat sunZ){
    if(!gRenderer)return;
    gRenderer->setEnvironment(skyMode,exposure,brightness,fogDensity,sunX,sunY,sunZ);
}

} // namespace noir::gfx
