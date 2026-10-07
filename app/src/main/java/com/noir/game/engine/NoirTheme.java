package com.noir.game.engine;

import android.graphics.Color;

/** Shared design tokens supplied by the native Noir theme module. */
public final class NoirTheme {
    private NoirTheme(){}

    public static int color(String key,int fallback){
        try{
            String value=NoirNative.themeColor(key);
            return Color.parseColor(value);
        }catch(Throwable ignored){return fallback;}
    }

    public static float corner(){
        try{
            float[] m=NoirNative.themeMetrics();
            return m!=null&&m.length>0?m[0]:9f;
        }catch(Throwable ignored){return 9f;}
    }

    public static float spacing(){
        try{
            float[] m=NoirNative.themeMetrics();
            return m!=null&&m.length>1?m[1]:6f;
        }catch(Throwable ignored){return 6f;}
    }
}
