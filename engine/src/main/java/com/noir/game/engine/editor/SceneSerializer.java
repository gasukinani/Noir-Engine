package com.noir.game.engine.editor;

import com.noir.game.engine.scene.NoirNode;
import com.noir.game.engine.scene.NoirScene;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic .game scene writer used by the mobile editor Save command. */
public final class SceneSerializer {
    private SceneSerializer(){}

    public static String write(NoirScene scene){
        StringBuilder out=new StringBuilder(8192);
        out.append("scene ").append(scene.name==null?"Untitled":scene.name).append('\n');
        for(NoirNode n:scene.root.children) writeNode(out,n,0,scene);
        return out.toString();
    }

    private static void writeNode(StringBuilder out,NoirNode n,int depth,NoirScene scene){
        indent(out,depth).append("node ").append(safe(n.name)).append(" {\n");
        indent(out,depth+1).append("type = ").append(n.kind.name()).append('\n');
        indent(out,depth+1).append(String.format(Locale.US,"position = (%.5f, %.5f, %.5f)\n",n.px,n.py,n.pz));
        indent(out,depth+1).append(String.format(Locale.US,"rotation = (%.5f, %.5f, %.5f)\n",n.rx,n.ry,n.rz));
        indent(out,depth+1).append(String.format(Locale.US,"scale = (%.5f, %.5f, %.5f)\n",n.sx,n.sy,n.sz));
        indent(out,depth+1).append("visible = ").append(n.visible).append('\n');
        indent(out,depth+1).append("locked = ").append(n.locked).append('\n');
        if(n.kind==NoirNode.Kind.WORLD_ENVIRONMENT){
            for(Map.Entry<String,String> e:scene.environment.entrySet()){
                if(e.getKey()==null||e.getValue()==null||e.getKey().isEmpty())continue;
                if(n.properties.containsKey(e.getKey()))continue;
                indent(out,depth+1).append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        for(Map.Entry<String,String> e:n.properties.entrySet()){
            if(e.getKey()==null||e.getKey().equals("type")||e.getKey().equals("position")||e.getKey().equals("rotation")||e.getKey().equals("scale"))continue;
            indent(out,depth+1).append(e.getKey()).append(" = ").append(e.getValue()==null?"":e.getValue()).append('\n');
        }
        for(NoirNode child:n.children)writeNode(out,child,depth+1,scene);
        indent(out,depth).append("}\n");
    }

    private static StringBuilder indent(StringBuilder b,int n){for(int i=0;i<n;i++)b.append("  ");return b;}
    private static String safe(String s){return s==null||s.isEmpty()?"Node":s.replaceAll("[^A-Za-z0-9_\\-]","_");}

    public static void save(File file,NoirScene scene) throws IOException{
        File parent=file.getParentFile();
        if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("Cannot create "+parent);
        try(Writer w=new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8)){w.write(write(scene));}
    }
}