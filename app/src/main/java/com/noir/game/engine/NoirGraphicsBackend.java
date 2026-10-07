package com.noir.game.engine;

import android.content.Context;
import android.content.SharedPreferences;

public final class NoirGraphicsBackend {
    public enum Type { GLES, VULKAN }

    private static final String PREFS="noir_graphics";
    private static final String KEY="backend";
    private static final String KEY_VULKAN_BLOCKED="vulkan_blocked";

    private NoirGraphicsBackend(){}

    public static Type load(Context context){
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String value=p.getString(KEY,"GLES");
        try{
            Type t=Type.valueOf(value);
            if(t==Type.VULKAN&&p.getBoolean(KEY_VULKAN_BLOCKED,false))return Type.GLES;
            return t;
        }catch(Exception ignored){return Type.GLES;}
    }

    public static void save(Context context,Type type){
        Type t=type==null?Type.GLES:type;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(KEY,t.name())
                .putBoolean(KEY_VULKAN_BLOCKED,false)
                .apply();
    }

    public static void markVulkanStarted(Context context){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_VULKAN_BLOCKED,true).apply();
    }

    public static void confirmVulkan(Context context){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(KEY,Type.VULKAN.name())
                .putBoolean(KEY_VULKAN_BLOCKED,false).apply();
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