#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
uniform vec3 uCamera;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uSkyColor;
uniform vec3 uBaseColor;
uniform float uExposure;
uniform float uRoughness;
uniform float uMetallic;
uniform float uFog;
out vec4 frag;

float sat(float x){return clamp(x,0.0,1.0);}
vec3 fresnel(vec3 f0,float c){return f0+(1.0-f0)*pow(1.0-c,5.0);}
float distribution(float NoH,float a){
    float a2=a*a;
    float d=NoH*NoH*(a2-1.0)+1.0;
    return a2/max(0.0001,3.14159265*d*d);
}
float visibility(float NoV,float NoL,float a){
    float k=(a+1.0)*(a+1.0)/8.0;
    return (NoV/(NoV*(1.0-k)+k))*(NoL/(NoL*(1.0-k)+k));
}
vec3 aces(vec3 x){
    return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0);
}
void main(){
    vec3 N=normalize(vNormal);
    vec3 V=normalize(uCamera-vWorld);
    vec3 L=normalize(-uSunDir);
    vec3 H=normalize(V+L);
    float NoV=sat(dot(N,V)),NoL=sat(dot(N,L)),NoH=sat(dot(N,H)),VoH=sat(dot(V,H));
    float r=max(0.08,uRoughness);
    float a=r*r;
    vec3 F0=mix(vec3(0.04),uBaseColor,uMetallic);
    vec3 F=fresnel(F0,VoH);
    float D=distribution(NoH,a);
    float G=visibility(NoV,NoL,r);
    vec3 spec=(D*G*F)/max(0.001,4.0*NoV*NoL);
    vec3 kd=(1.0-F)*(1.0-uMetallic);
    vec3 direct=(kd*uBaseColor/3.14159265+spec)*uSunColor*NoL;
    vec3 color=direct+kd*uBaseColor*(0.20+0.34*NoV)+uSkyColor*(0.12+0.30*NoV);
    float fog=1.0-exp(-length(uCamera-vWorld)*max(0.0,uFog));
    color=mix(color,uSkyColor,sat(fog));
    color=aces(max(color,vec3(0.0)) * max(0.05,uExposure));
    color=pow(color,vec3(1.0/2.2));
    frag=vec4(color,1.0);
}
