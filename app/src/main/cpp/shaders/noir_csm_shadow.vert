#version 450
layout(push_constant) uniform ShadowPC {
    mat4 lightViewProj;
    mat4 model;
} pc;
layout(location=0) in vec3 aPos;
void main(){
    gl_Position=pc.lightViewProj*pc.model*vec4(aPos,1.0);
}
