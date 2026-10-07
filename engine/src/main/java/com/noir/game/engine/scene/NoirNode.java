package com.noir.game.engine.scene;

import java.util.*;

/**
 * Core Noir scene node. The enum is intentionally broad so the mobile editor can expose
 * one consistent hierarchy for gameplay, rendering, animation and world-authoring tools.
 */
public final class NoirNode {
    public enum Kind {
        NODE3D, CHARACTER3D, PLAYER3D, CAMERA3D, LIGHT3D, MESH3D, SKINNED_MESH3D,
        COLLIDER3D, RIGID_BODY3D, STATIC_BODY3D, AREA3D, RAYCAST3D, AUDIO3D,
        PARTICLES3D, DECAL3D, WATER3D, TERRAIN3D, FOLIAGE3D, SPLINE3D,
        NAVMESH3D, NAV_AGENT3D, REFLECTION_PROBE3D, LIGHT_PROBE3D, WORLD_ENVIRONMENT,
        SKY3D, FOG_VOLUME3D, POST_PROCESS3D, LOD_GROUP3D, OCCLUDER3D,
        ANIMATION_PLAYER, ANIMATION_TREE, BONE_ATTACHMENT3D, IK_TARGET3D,
        VEHICLE3D, SPRING_ARM3D, UI3D, ROCK3D
    }

    public final String id;
    public String name;
    public Kind kind;
    public NoirNode parent;
    public final List<NoirNode> children = new ArrayList<>();
    public final Map<String,String> properties = new LinkedHashMap<>();
    public float px, py, pz;
    public float rx, ry, rz;
    public float sx = 1, sy = 1, sz = 1;
    public boolean visible = true;
    public boolean locked = false;

    public NoirNode(String id, String name, Kind kind) {
        this.id = Objects.requireNonNull(id); this.name = Objects.requireNonNull(name); this.kind = kind;
    }
    public NoirNode add(NoirNode child) {
        if (child == this) throw new IllegalArgumentException("A node cannot parent itself");
        if (child.parent != null) child.parent.children.remove(child);
        child.parent = this; children.add(child); return child;
    }
    public boolean remove(NoirNode child) { if (children.remove(child)) { child.parent = null; return true; } return false; }
    public NoirNode find(String query) {
        if (id.equals(query) || name.equals(query)) return this;
        for (NoirNode child : children) { NoirNode hit = child.find(query); if (hit != null) return hit; }
        return null;
    }
    public int depth() { int d=0; NoirNode n=parent; while(n!=null){d++;n=n.parent;} return d; }
    public int descendantCount(){int n=children.size();for(NoirNode c:children)n+=c.descendantCount();return n;}
    public void setTransform(float x,float y,float z,float pitch,float yaw,float roll,float scale){px=x;py=y;pz=z;rx=pitch;ry=yaw;rz=roll;sx=sy=sz=scale;}
    public String transformText(){return String.format(Locale.US,"pos %.2f %.2f %.2f | rot %.1f %.1f %.1f | scale %.2f %.2f %.2f",px,py,pz,rx,ry,rz,sx,sy,sz);}
}
