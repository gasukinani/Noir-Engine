package com.noir.game.engine.render;

/**
 * Complete mobile-friendly world environment profile.
 *
 * Keeps the editor/runtime representation independent from a specific graphics
 * API so the same scene can drive GLES, Vulkan, and future backends.
 */
public final class WorldEnvironmentSettings {
    public enum SkyMode { COLOR, GRADIENT, CUBEMAP, PROCEDURAL_SKY, PHYSICAL_SKY, HDRI, SHADER_SKY_MATERIAL }

    public SkyMode skyMode=SkyMode.PROCEDURAL_SKY;
    public String skyAsset="";
    public String skyMaterial="";
    public float skyBrightness=1f;
    public float sunEnergy=2f;
    public float ambientEnergy=.7f;
    public float fogDensity=.008f;
    public float fogHeight=12f;
    public float exposure=1f;
    public float whitePoint=1f;
    public float cloudCoverage=.15f;
    public float cloudDensity=.2f;
    public float cloudSpeed=.006f;
    public float sunYaw=-152f;
    public float sunPitch=-55f;
    public final float[] horizon={.32f,.40f,.55f,1f};
    public final float[] zenith={.04f,.08f,.16f,1f};
    public boolean fogEnabled=true;
    public boolean volumetricEnabled=false;
    public boolean cloudsEnabled=true;
    public boolean sunRays=true;
    public boolean tonemap=true;
    public boolean autoExposure=false;

    public void setSkyMode(SkyMode mode){skyMode=mode==null?SkyMode.PROCEDURAL_SKY:mode;}
    public void setFog(float density,float height){
        fogDensity=clamp(density,0f,.25f);
        fogHeight=Math.max(0f,height);
        fogEnabled=fogDensity>0f;
    }
    public void setExposure(float value){exposure=clamp(value,.01f,8f);}
    public void setSkyBrightness(float value){skyBrightness=clamp(value,0f,16f);}
    public void setAmbientEnergy(float value){ambientEnergy=clamp(value,0f,8f);}
    public void setSunEnergy(float value){sunEnergy=clamp(value,0f,32f);}
    public void setClouds(float coverage,float density){
        cloudCoverage=clamp(coverage,0f,1f);
        cloudDensity=clamp(density,0f,1f);
        cloudsEnabled=cloudCoverage>0f&&cloudDensity>0f;
    }
    public void setSun(float yawDegrees,float pitchDegrees,float energy){
        sunYaw=yawDegrees;
        sunPitch=clamp(pitchDegrees,-89f,89f);
        setSunEnergy(energy);
    }
    public void setCloudMotion(float speed){cloudSpeed=clamp(speed,-.05f,.05f);}

    public float[] sunDirection(){
        double yaw=Math.toRadians(sunYaw), pitch=Math.toRadians(sunPitch);
        float cp=(float)Math.cos(pitch);
        return new float[]{(float)(Math.cos(yaw)*cp),(float)Math.sin(pitch),(float)(Math.sin(yaw)*cp)};
    }

    public boolean valid(){
        return skyBrightness>=0f&&sunEnergy>=0f&&ambientEnergy>=0f&&
               fogDensity>=0f&&fogHeight>=0f&&exposure>0f&&
               cloudCoverage>=0f&&cloudDensity>=0f;
    }

    public float ambientLuminance(){return ambientEnergy*skyBrightness;}

    private static float clamp(float v,float min,float max){return Math.max(min,Math.min(max,v));}
}
