#version 300 es
precision mediump float;
uniform sampler2D uLow;
uniform sampler2D uBase;
uniform float uStrength;
in vec2 vUV;
out vec4 frag;
void main(){
    vec3 blur=texture(uLow,vUV).rgb;
    vec3 base=texture(uBase,vUV).rgb;
    frag=vec4(base+blur*uStrength,1.0);
}
