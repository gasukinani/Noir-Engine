#version 300 es
precision highp float;
uniform samplerCube uIrradiance;
uniform samplerCube uPrefilteredEnv;
uniform sampler2D uBrdfLut;
uniform vec3 uNormal;
uniform vec3 uViewDir;
uniform float uRoughness;
uniform float uMetallic;
uniform vec3 uBaseColor;

vec3 noirIBL(){
    vec3 N=normalize(uNormal);
    vec3 V=normalize(uViewDir);
    float NoV=max(dot(N,V),0.0);
    vec3 F0=mix(vec3(0.04),uBaseColor,uMetallic);
    vec3 F=F0+(1.0-F0)*pow(1.0-NoV,5.0);

    vec3 diffuse=texture(uIrradiance,N).rgb*uBaseColor*(1.0-uMetallic);
    vec3 R=reflect(-V,N);
    const float MAX_LOD=5.0;
    vec3 prefiltered=textureLod(uPrefilteredEnv,R,uRoughness*MAX_LOD).rgb;
    vec2 brdf=texture(uBrdfLut,vec2(NoV,uRoughness)).rg;
    vec3 specular=prefiltered*(F*brdf.x+brdf.y);
    return diffuse*0.30+specular;
}
