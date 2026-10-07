package com.noir.game.engine;

import android.content.Context;
import android.content.SharedPreferences;

public final class NoirGraphicsBackend {
    public enum Type { GLES, VULKAN }

    private static final String PREFS="noir_graphics";
    private static final String KEY="backend";

    private NoirGraphicsBackend(){}

    public static Type load(Context context){
        String value=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"GLES");
        try{return Type.valueOf(value);}catch(Exception ignored){return Type.GLES;}
    }

    public static void save(Context context,Type type){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(KEY,(type==null?Type.GLES:type).name()).apply();
    }

    public static boolean vulkanAvailable(){
        try{return NoirNative.vulkanSupported();}catch(Throwable ignored){return false;}
    }

    public static String status(){
        try{return NoirNative.vulkanStatus();}catch(Throwable ignored){return "Vulkan probe unavailable";}
    }

    public static boolean initializeVulkanStage(){
        try{return NoirNative.vulkanInitialize();}catch(Throwable ignored){return false;}
    }

    public static void shutdownVulkanStage(){
        try{NoirNative.vulkanShutdown();}catch(Throwable ignored){}
    }

    public static boolean vulkanDeviceReady(){
        try{return NoirNative.vulkanDeviceReady();}catch(Throwable ignored){return false;}
    }

    public static String vulkanDeviceInfo(){
        try{return NoirNative.vulkanDeviceInfo();}catch(Throwable ignored){return "Vulkan device info unavailable";}
    }

    public static String vulkanFeatureInfo(){
        try{return NoirNative.vulkanFeatureInfo();}catch(Throwable ignored){return "Vulkan feature probe unavailable";}
    }
}