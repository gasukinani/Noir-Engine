package com.noir.game.engine.core;

import com.noir.game.engine.scene.*;
import java.util.*;
import java.util.regex.*;

/** Strict but forgiving parser for Noir .game documents. It validates structure,
 * produces useful diagnostics, and keeps unknown properties for forward compatibility. */
public final class GameFileParser {
    public static final class Diagnostic {
        public final int line; public final String severity; public final String message;
        Diagnostic(int line,String severity,String message){this.line=line;this.severity=severity;this.message=message;}
        @Override public String toString(){return severity+" L"+line+": "+message;}
    }
    public static final class Result {
        public NoirScene scene; public final List<Diagnostic> diagnostics=new ArrayList<>();
        public boolean ok(){for(Diagnostic d:diagnostics) if(d.severity.equals("ERROR")) return false; return scene!=null;}
    }
    private static final Pattern NODE=Pattern.compile("node\\s+(\\w+)\\s*\\{\\s*");
    private static final Pattern PROP=Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)");

    public Result parse(String source,String path){
        Result r=new Result(); String[] lines=source.replace("\r","").split("\n",-1);
        NoirScene scene=new NoirScene("Untitled"); scene.sourcePath=path;
        Deque<NoirNode> stack=new ArrayDeque<>(); stack.push(scene.root);
        boolean sawScene=false;
        for(int i=0;i<lines.length;i++){
            int ln=i+1; String raw=lines[i]; String line=raw.trim();
            if(line.isEmpty()||line.startsWith("#")||line.startsWith("//")) continue;
            if(line.startsWith("scene ")) { String[] p=line.split("\\s+",3); if(p.length<2) r.diagnostics.add(new Diagnostic(ln,"ERROR","scene requires a name")); else {scene.name=p[1]; sawScene=true;} continue; }
            Matcher nm=NODE.matcher(line);
            if(nm.find()){ String name=nm.group(1); NoirNode node=new NoirNode(name,name,NoirNode.Kind.NODE3D); stack.peek().add(node); stack.push(node); continue; }
            if(line.equals("}")){ if(stack.size()==1) r.diagnostics.add(new Diagnostic(ln,"ERROR","unexpected closing brace")); else stack.pop(); continue; }
            Matcher pm=PROP.matcher(line);
            if(pm.matches()){ String key=pm.group(1), val=pm.group(2).replaceAll("[;,]$","").trim(); NoirNode target=stack.peek();
                if(key.equals("type")){ try{target.kind=NoirNode.Kind.valueOf(val.toUpperCase(Locale.US));}catch(Exception e){r.diagnostics.add(new Diagnostic(ln,"WARN","unknown node type: "+val));} }
                else if(key.equals("position")){float[] v=vec3(val); if(v==null) r.diagnostics.add(new Diagnostic(ln,"ERROR","position expects three numbers")); else {target.px=v[0];target.py=v[1];target.pz=v[2];}}
                else if(key.equals("rotation")){float[] v=vec3(val); if(v==null) r.diagnostics.add(new Diagnostic(ln,"ERROR","rotation expects three numbers")); else {target.rx=v[0];target.ry=v[1];target.rz=v[2];}}
                else if(key.equals("scale")){float[] v=vec3(val); if(v==null) r.diagnostics.add(new Diagnostic(ln,"ERROR","scale expects three numbers")); else {target.sx=v[0];target.sy=v[1];target.sz=v[2];}}
                else if(key.equals("size") && (target.kind==NoirNode.Kind.TERRAIN3D || target.kind==NoirNode.Kind.WATER3D)){
                    target.properties.put(key,val);
                    float[] v=vec3(val);
                    if(v!=null){
                        if(target.kind==NoirNode.Kind.TERRAIN3D){
                            target.sx=Math.max(1f,Math.abs(v[0]));
                            target.sy=Math.max(0.1f,Math.abs(v[1]));
                            target.sz=Math.max(1f,Math.abs(v[2]));
                        }else{
                            target.sx=Math.max(0.5f,Math.abs(v[0]));
                            target.sy=Math.max(0.02f,Math.abs(v[1]));
                            target.sz=Math.max(0.5f,Math.abs(v[2]));
                        }
                    }
                }
                else target.properties.put(key,val);
                continue;
            }
            r.diagnostics.add(new Diagnostic(ln,"ERROR","unrecognized statement: "+line));
        }
        if(stack.size()!=1) r.diagnostics.add(new Diagnostic(lines.length,"ERROR","unclosed node block"));
        if(!sawScene) r.diagnostics.add(new Diagnostic(1,"WARN","no scene declaration; using document name"));
        r.scene=scene; return r;
    }
    private float[] vec3(String s){ String q=s.replace("("," ").replace(")"," ").replace(","," ").trim(); String[] p=q.split("\\s+"); if(p.length!=3)return null; try{return new float[]{Float.parseFloat(p[0]),Float.parseFloat(p[1]),Float.parseFloat(p[2])};}catch(Exception e){return null;} }
}
