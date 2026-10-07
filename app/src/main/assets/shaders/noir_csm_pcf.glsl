#version 300 es
precision highp float;
uniform sampler2D uShadow0;
uniform sampler2D uShadow1;
uniform sampler2D uShadow2;
uniform vec4 uCascadeSplits;
uniform vec2 uShadowTexel;
in vec4 vLight0;
in vec4 vLight1;
in vec4 vLight2;
uniform float uPcfRadius;
float samplePCF(sampler2D shadowTex, vec4 lightPos){
    vec3 p=lightPos.xyz/max(lightPos.w,0.0001);
    p=p*0.5+0.5;
    if(any(lessThan(p.xy,vec2(0.0)))||any(greaterThan(p.xy,vec2(1.0)))||p.z>1.0)return 1.0;
    float sum=0.0;
    for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++){
        if(abs(float(x))>uPcfRadius||abs(float(y))>uPcfRadius)continue;
        float depth=texture(shadowTex,p.xy+vec2(x,y)*uShadowTexel).r;
        sum+=p.z-0.0015<=depth?1.0:0.0;
    }
    float samples=uPcfRadius>1.5?25.0:9.0;
    return sum/samples;
}
float csmShadow(float viewDepth){
    if(viewDepth<uCascadeSplits.x)return samplePCF(uShadow0,vLight0);
    if(viewDepth<uCascadeSplits.y)return samplePCF(uShadow1,vLight1);
    if(viewDepth<uCascadeSplits.z)return samplePCF(uShadow2,vLight2);
    return samplePCF(uShadow2,vLight2);
}
