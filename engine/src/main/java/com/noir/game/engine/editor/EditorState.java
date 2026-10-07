package com.noir.game.engine.editor;

import com.noir.game.engine.animation.AnimationSystem;
import com.noir.game.engine.assets.NoirAssetDatabase;
import com.noir.game.engine.scene.*;
import java.util.*;

/** Shared state for all mobile editor panels. No panel owns the scene; every panel
 * reads and mutates this model so selection, inspector, animation and script views stay synchronized. */
public final class EditorState {
    public enum Tool { SELECT,MOVE,ROTATE,SCALE,CREATE }
    public enum Panel { VIEWPORT,SCENE,ASSETS,INSPECTOR,ANIMATION,SCRIPT,CONSOLE,SHADER,PHYSICS }
    public final NoirScene scene;
    public final NoirAssetDatabase assets=new NoirAssetDatabase();
    public final String projectPath;
    public final java.io.File projectRoot;

    public final AnimationSystem animations=new AnimationSystem();
    public NoirNode selected;
    public Tool tool=Tool.SELECT;
    public Panel panel=Panel.VIEWPORT;
    public boolean playing;
    public boolean grid=false;
    public boolean snapping=true;
    public float snapStep=0.25f;
    public final List<String> console=new ArrayList<>();
    public String scriptSource="";
    public String scriptPath="scripts/player.game";
    public EditorState(NoirScene s){this(s,null);}
    public EditorState(NoirScene s,String path){
        scene=s;
        projectPath=path;
        projectRoot=normalizeProjectRoot(path);
        selected=s.selectedFallback();
        log("Noir editor state created");
        if(projectRoot!=null)log("Project root: "+projectRoot.getAbsolutePath());
    }

    private static java.io.File normalizeProjectRoot(String raw){
        if(raw==null||raw.trim().isEmpty())return null;
        try{
            java.io.File cur=new java.io.File(raw).getCanonicalFile();
            if(cur.isFile())cur=cur.getParentFile();
            for(int i=0;i<8&&cur!=null;i++){
                if(new java.io.File(cur,"project.game").isFile() &&
                   new java.io.File(cur,"scenes/Main.game").isFile()){
                    return cur;
                }
                cur=cur.getParentFile();
            }
        }catch(Exception ignored){}
        return new java.io.File(raw);
    }
    public void select(NoirNode n){if(n!=null&&!n.locked){selected=n;log("Selected "+n.name);}}
    public void log(String s){console.add("[Noir] "+s);while(console.size()>120)console.remove(0);}
}
