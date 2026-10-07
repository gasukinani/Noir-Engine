#version 300 es
precision mediump float;
uniform sampler2D uSource;
uniform vec2 uTexelSize;
in vec2 vUV;
out vec4 frag;
void main(){
    vec3 c=vec3(0.0);
    c+=texture(uSource,vUV+uTexelSize*vec2(-1,-1)).rgb;
    c+=texture(uSource,vUV+uTexelSize*vec2( 1,-1)).rgb;
    c+=texture(uSource,vUV+uTexelSize*vec2(-1, 1)).rgb;
    c+=texture(uSource,vUV+uTexelSize*vec2( 1, 1)).rgb;
    c*=0.25;
    float lum=dot(c,vec3(0.2126,0.7152,0.0722));
    frag=vec4(c*max(0.0,(lum-0.85)*1.35),1.0);
}
