package com.noir.game.engine.scene;

import java.util.*;

/** Scene document used by .game files and the editor hierarchy. */
public final class NoirScene {
    public String name;
    public String sourcePath;
    public final NoirNode root;
    public final Map<String,String> environment = new LinkedHashMap<>();
    public final Map<String,String> metadata = new LinkedHashMap<>();

    public NoirScene(String name) {
        this.name = name;
        root = new NoirNode("root", name, NoirNode.Kind.NODE3D);
        environment.put("sky", "procedural");
        environment.put("sky_mode", "PROCEDURAL_SKY");
        environment.put("sky_brightness", "1.0");
        environment.put("exposure", "1.0");
        environment.put("fog_density", "0.012");
        environment.put("ambient_strength", "0.35");
    }

    public List<NoirNode> flatten() {
        List<NoirNode> result = new ArrayList<>();
        walk(root,result);
        return result;
    }

    private void walk(NoirNode node,List<NoirNode> out) {
        out.add(node);
        for (NoirNode child: node.children) walk(child,out);
    }

    public NoirNode selectedFallback() { return root.children.isEmpty() ? root : root.children.get(0); }
}
