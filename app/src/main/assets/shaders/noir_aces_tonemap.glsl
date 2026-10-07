#version 300 es
precision highp float;
uniform sampler2D uHDR;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
in vec2 vUV;
out vec4 frag;

vec3 ACES(vec3 x){
    return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0);
}
void main(){
    vec3 c=texture(uHDR,vUV).rgb*max(uExposure,0.05);
    c=ACES(c);
    float l=dot(c,vec3(0.2126,0.7152,0.0722));
    c=mix(vec3(l),c,uSaturation);
    c=(c-0.5)*uContrast+0.5;
    frag=vec4(pow(clamp(c,0.0,1.0),vec3(1.0/2.2)),1.0);
}
