package com.noir.game.engine;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Persists a small crash report so native/editor failures can be diagnosed after restart. */
public final class NoirCrashReporter {
    private static boolean installed;
    private NoirCrashReporter(){}

    public static synchronized void install(Context context){
        if(installed)return;
        installed=true;
        final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{
                File root=context.getExternalFilesDir(null);
                if(root!=null){
                    File dir=new File(root,"noir-crash");
                    if(!dir.exists())dir.mkdirs();
                    File report=new File(dir,"last_crash.txt");
                    try(Writer out=new OutputStreamWriter(new FileOutputStream(report),StandardCharsets.UTF_8)){
                        out.write("Noir Engine crash report\n");
                        out.write("Time: "+new Date()+"\n");
                        out.write("Device: "+Build.MANUFACTURER+" "+Build.MODEL+"\n");
                        out.write("SDK: "+Build.VERSION.SDK_INT+"\n");
                        out.write("Backend: "+NoirGraphicsBackend.load(context)+"\n\n");
                        error.printStackTrace(new PrintWriter(out));
                    }
                }
                // Disable Vulkan before Android kills the process; next launch uses GLES.
                NoirGraphicsBackend.save(context,NoirGraphicsBackend.Type.GLES);
            }catch(Throwable ignored){}
            if(previous!=null)previous.uncaughtException(thread,error);
        });
    }

    public static File lastReport(Context context){
        File root=context.getExternalFilesDir(null);
        return root==null?null:new File(root,"noir-crash/last_crash.txt");
    }
}
