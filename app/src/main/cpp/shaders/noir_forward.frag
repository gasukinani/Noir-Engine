#version 450

layout(push_constant) uniform Frame {
    mat4 vp;
    vec4 cameraExposure;
    vec4 sunBrightness;
    vec4 environment;
} pc;

layout(location=0) in vec3 vWorld;
layout(location=1) in vec3 vNormal;
layout(location=2) in vec4 vColor;
layout(location=0) out vec4 outColor;

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
vec3 skyForMode(float mode){
    if(mode>0.5 && mode<1.5)return vec3(0.30,0.46,0.70);
    if(mode>2.5 && mode<3.5)return vec3(0.16,0.25,0.48);
    return vec3(0.38,0.52,0.72);
}
vec3 aces(vec3 x){
    return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0);
}
void main(){
    vec3 N=normalize(vNormal);
    vec3 V=normalize(pc.cameraExposure.xyz-vWorld);
    vec3 L=normalize(-pc.sunBrightness.xyz);
    vec3 H=normalize(V+L);
    float NoV=sat(dot(N,V)),NoL=sat(dot(N,L)),NoH=sat(dot(N,H)),VoH=sat(dot(V,H));
    float rough=max(0.08,vColor.a);
    float metal=mix(0.02,0.72,clamp((pc.environment.w-1.0)/3.0,0.0,1.0))*0.12;
    vec3 base=max(vColor.rgb,vec3(0.001));
    vec3 F0=mix(vec3(0.04),base,metal);
    vec3 F=fresnel(F0,VoH);
    float D=distribution(NoH,rough*rough);
    float G=visibility(NoV,NoL,rough);
    vec3 spec=(D*G*F)/max(0.001,4.0*NoV*NoL);
    vec3 kd=(1.0-F)*(1.0-metal);
    float sunEnergy=max(0.15,pc.sunBrightness.w);
    vec3 direct=(kd*base/3.14159265+spec)*vec3(1.75,1.55,1.30)*NoL*sunEnergy;
    vec3 sky=skyForMode(pc.environment.x)*pc.environment.y;
    vec3 color=direct+kd*base*(0.18+0.34*NoV)+sky*(0.20+0.36*spec);
    float dist=length(pc.cameraExposure.xyz-vWorld);
    float fog=1.0-exp(-dist*max(0.0,pc.environment.z));
    color=mix(color,sky,sat(fog));
    color*=max(0.05,pc.cameraExposure.w);
    color=aces(max(color,vec3(0.0)));
    color=pow(color,vec3(1.0/2.2));
    outColor=vec4(color,1.0);
}
