#version 450

layout(push_constant) uniform Frame {
    mat4 vp;
    vec4 cameraExposure;
    vec4 sunBrightness;
    vec4 environment;
} pc;

layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec4 aColor;

layout(location=0) out vec3 vWorld;
layout(location=1) out vec3 vNormal;
layout(location=2) out vec4 vColor;

void main(){
    vWorld=aPos;
    vNormal=normalize(aNormal);
    vColor=aColor;
    gl_Position=pc.vp*vec4(aPos,1.0);
}
