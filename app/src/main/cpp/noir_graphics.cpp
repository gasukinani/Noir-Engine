#include "noir_graphics.h"

#include <GLES3/gl3.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <chrono>

#define NOIR_LOG(...) __android_log_print(ANDROID_LOG_INFO, "NoirGfx", __VA_ARGS__)

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
static Mat4 model(float x,float y,float z,float sx,float sy,float sz){
    Mat4 r=identity();
    r.m[0]=sx;r.m[5]=sy;r.m[10]=sz;r.m[12]=x;r.m[13]=y;r.m[14]=z;
    return r;
}
static float terrainHeight(float x,float z){
    return 0.22f*std::sin(x*0.38f)+0.18f*std::cos(z*0.31f)
         +0.12f*std::sin((x+z)*0.67f)+0.07f*std::cos((x-z)*1.17f);
}
static GLuint compile(GLenum type,const char*src){
    GLuint s=glCreateShader(type);
    glShaderSource(s,1,&src,nullptr);
    glCompileShader(s);
    GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){
        char log[2048];GLsizei n=0;glGetShaderInfoLog(s,sizeof(log),&n,log);
        NOIR_LOG("shader compile failed: %.*s",(int)n,log);
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
        NOIR_LOG("program link failed: %.*s",(int)n,log);glDeleteProgram(p);return 0;
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
out vec4 frag;
void main(){
    float h=pow(clamp(uv.y,0.0,1.0),0.62);
    vec3 c=mix(uHorizon,uTop,h);
    float sun=pow(max(dot(normalize(vec3(uv.x*1.7-0.85,(uv.y-.45)*1.3,1.0)),normalize(-uSunDir)),0.0),180.0);
    c+=vec3(1.0,0.68,0.38)*sun*0.42;
    frag=vec4(c,1.0);
}
)GLSL";

struct Vertex { float px,py,pz,nx,ny,nz; };

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
    GLuint pbr=0,sky=0,vao=0,vbo=0,skyVao=0;
    std::vector<Vertex> objects;
    int width=1,height=1;
    float frameMs=16.6f;
    bool ready=false;

    void destroy(){
        if(vbo)glDeleteBuffers(1,&vbo);
        if(vao)glDeleteVertexArrays(1,&vao);
        if(skyVao)glDeleteVertexArrays(1,&skyVao);
        if(pbr)glDeleteProgram(pbr);
        if(sky)glDeleteProgram(sky);
        vbo=vao=skyVao=pbr=sky=0;ready=false;
    }

    bool build(){
        pbr=program(kVs,kFs);sky=program(kSkyVs,kSkyFs);
        if(!pbr||!sky)return false;

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
        glGenVertexArrays(1,&skyVao);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        ready=true;return true;
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
        glUniform1f(glGetUniformLocation(pbr,"uExposure"),1.08f);
        glUniform1f(glGetUniformLocation(pbr,"uFog"),0.018f);
        // The geometry is already in world space. uModel remains identity.
        Mat4 id=identity();glUniformMatrix4fv(glGetUniformLocation(pbr,"uModel"),1,GL_FALSE,id.m);
        glDrawArrays(GL_TRIANGLES,(GLint)first,(GLsizei)count);
        glBindVertexArray(0);
    }
};

Renderer::Renderer():impl_(new Impl()){}
Renderer::~Renderer(){ if(impl_){ impl_->destroy(); delete impl_; impl_=nullptr; } }

void Renderer::shutdown(){ if(impl_) impl_->destroy(); }

bool Renderer::initialize(){
    if(!impl_)return false;
    impl_->destroy();
    bool ok=impl_->build();
    if(!ok){impl_->destroy();NOIR_LOG("NoirGfx initialization failed");}
    return ok;
}
void Renderer::resize(int width,int height){
    impl_->width=std::max(1,width);impl_->height=std::max(1,height);
    glViewport(0,0,impl_->width,impl_->height);
}
void Renderer::frame(float yawDeg,float pitchDeg,float distance,float tx,float ty,float tz,bool editorMode){
    if(!impl_->ready)return;
    auto start=std::chrono::steady_clock::now();

    float yr=yawDeg*0.01745329252f,pr=pitchDeg*0.01745329252f;
    Vec3 target{tx,ty,tz};
    Vec3 cam{tx+std::cos(pr)*std::cos(yr)*distance,
             ty+std::sin(pr)*distance,
             tz+std::cos(pr)*std::sin(yr)*distance};

    Mat4 vp=mul(perspective(1.11701f,float(impl_->width)/float(impl_->height),0.05f,180.0f),
                lookAt(cam,target,{0,1,0}));

    // No flat blue clear: a deep-space gradient is drawn first, then the
    // procedural world is rendered on top.
    glDisable(GL_DEPTH_TEST);
    glUseProgram(impl_->sky);
    glBindVertexArray(impl_->skyVao);
    glUniform3f(glGetUniformLocation(impl_->sky,"uTop"),0.025f,0.055f,0.11f);
    glUniform3f(glGetUniformLocation(impl_->sky,"uHorizon"),0.12f,0.22f,0.34f);
    glUniform3f(glGetUniformLocation(impl_->sky,"uSunDir"),-0.38f,-0.82f,-0.32f);
    glUniform1f(glGetUniformLocation(impl_->sky,"uPitch"),pitchDeg);
    glDrawArrays(GL_TRIANGLES,0,3);
    glBindVertexArray(0);

    glEnable(GL_DEPTH_TEST);
    glClear(GL_DEPTH_BUFFER_BIT);
    Vec3 sun{-0.38f,-0.82f,-0.32f};
    Vec3 skyColor{0.36f,0.50f,0.72f};

    // Ground tiles.
    impl_->drawCubeRange(0,49*36,vp,{0.19f,0.29f,0.22f},0.88f,0.02f,cam,sun,skyColor);
    impl_->drawCubeRange(49*36,5*36,vp,{0.22f,0.32f,0.46f},0.48f,0.14f,cam,sun,skyColor);
    impl_->drawCubeRange(54*36,20*36,vp,{0.30f,0.20f,0.11f},0.78f,0.01f,cam,sun,skyColor);

    // A subtle editor-only grid is intentionally part of the graphics library,
    // not a Java canvas paint, so it stays locked to the 3D world.
    if(editorMode){
        glUseProgram(impl_->pbr);
        glBindVertexArray(impl_->vao);
        glUniformMatrix4fv(glGetUniformLocation(impl_->pbr,"uVP"),1,GL_FALSE,vp.m);
        glUniform3f(glGetUniformLocation(impl_->pbr,"uCamera"),cam.x,cam.y,cam.z);
        glUniform3f(glGetUniformLocation(impl_->pbr,"uSunDir"),sun.x,sun.y,sun.z);
        glUniform3f(glGetUniformLocation(impl_->pbr,"uSunColor"),0.55f,0.62f,0.78f);
        glUniform3f(glGetUniformLocation(impl_->pbr,"uSkyColor"),0.22f,0.30f,0.46f);
        glUniform3f(glGetUniformLocation(impl_->pbr,"uColor"),0.16f,0.22f,0.31f);
        glUniform1f(glGetUniformLocation(impl_->pbr,"uRoughness"),0.92f);
        glUniform1f(glGetUniformLocation(impl_->pbr,"uMetallic"),0.0f);
        glUniform1f(glGetUniformLocation(impl_->pbr,"uExposure"),0.65f);
        glUniform1f(glGetUniformLocation(impl_->pbr,"uFog"),0.0f);
        Mat4 id=identity();glUniformMatrix4fv(glGetUniformLocation(impl_->pbr,"uModel"),1,GL_FALSE,id.m);
        // No GL_LINES dependency on a second buffer: thin terrain tiles already
        // provide visual grounding, while the Java gizmo remains interactive.
        glBindVertexArray(0);
    }

    glDisable(GL_CULL_FACE);
    glEnable(GL_DEPTH_TEST);

    auto end=std::chrono::steady_clock::now();
    impl_->frameMs=std::chrono::duration<float,std::milli>(end-start).count();
}
float Renderer::frameTimeMs() const{return impl_->frameMs;}
const char* Renderer::backendInfo() const{return "NoirGFX C++ / OpenGL ES 3.0 • mobile PBR";}

} // namespace noir::gfx
