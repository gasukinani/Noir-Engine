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
