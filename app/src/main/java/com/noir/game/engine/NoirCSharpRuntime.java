package com.noir.game.engine;

import android.content.Context;
import java.io.*;
import java.util.*;

/** Installs the complete C# SDK/compiler payload from APK assets into app-private storage. */
public final class NoirCSharpRuntime {
    private static final String ASSET_ROOT="csharp/sdk";
    private NoirCSharpRuntime(){}

    public static File ensureInstalled(Context context) throws IOException {
        File external=context.getExternalFilesDir(null);
        if(external==null)throw new IOException("External app storage unavailable");
        File root=new File(external,"noir-csharp/sdk");
        if(!root.exists() && !root.mkdirs()) throw new IOException("Unable to create C# SDK directory");
        String[] names=context.getAssets().list(ASSET_ROOT);
        if(names==null) names=new String[0];

        int copiedFiles=0;
        int copiedDlls=0;
        for(String name:names){
            if(name==null || name.contains("/") || name.contains(".."))continue;
            File target=new File(root,name);
            copyAsset(context,ASSET_ROOT+"/"+name,target);
            copiedFiles++;
            if(name.toLowerCase(Locale.US).endsWith(".dll"))copiedDlls++;
        }
        if(copiedDlls==0)throw new IOException("No C# SDK DLLs were packaged in the APK");

        File core=new File(root,"Noir.dll");
        File compiler=new File(root,"Noir.CSharp.Compiler.dll");
        File roslyn=new File(root,"Microsoft.CodeAnalysis.CSharp.dll");
        if(!core.isFile()||!compiler.isFile()||!roslyn.isFile())
            throw new IOException("C# toolchain bundle is incomplete: core/compiler/Roslyn DLL missing");

        return root;
    }

    public static File sdkDirectory(Context context){
        try{return ensureInstalled(context);}catch(IOException e){return null;}
    }

    public static boolean hasDll(Context context,String dllName){
        if(dllName==null||dllName.isEmpty())return false;
        File root=sdkDirectory(context);return root!=null&&new File(root,dllName).isFile();
    }

    public static String toolchainInfo(Context context) {
        try{
            File root=ensureInstalled(context);
            String nativeReport=NoirNative.isLoaded()
                    ?NoirNative.csharpToolchainScan(root.getAbsolutePath())
                    :"Native toolchain scanner unavailable";
            return root.getAbsolutePath()+"\n"+nativeReport;
        }catch(Throwable e){
            return "C# SDK ERROR: "+e.getMessage();
        }
    }

    private static void copyAsset(Context context,String asset,File target)throws IOException{
        File parent=target.getParentFile();
        if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Unable to create "+parent);
        try(InputStream in=context.getAssets().open(asset);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){
            byte[] buffer=new byte[16384];
            int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
    }
}
