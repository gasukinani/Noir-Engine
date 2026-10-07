package com.noir.game.engine;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.View;
import android.graphics.Color;
import com.noir.game.engine.core.GameFileParser;
import com.noir.game.engine.editor.EditorState;
import com.noir.game.engine.scene.NoirNode;
import com.noir.game.engine.scene.NoirScene;
import com.noir.game.engine.render.NoirRenderer;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private NoirRenderer renderer;
    private NoirEditorView editorUi;
    private View surface;
    private FrameLayout rootContainer;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        NoirCrashReporter.install(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);

        String projectPath=normalizeProjectRoot(getIntent().getStringExtra("project_path"));
        try {
            NoirCSharpRuntime.ensureInstalled(this);
        } catch(Throwable ignored) {
            // The editor can still open for non-C# projects; the build pipeline
            // separately guarantees that the SDK DLLs are packaged in the APK.
        }

        if(projectPath!=null && !projectPath.trim().isEmpty()){
            try{ new NoirProjectWorkspace(this).ensureCSharpLayout(new File(projectPath).getCanonicalFile()); }catch(Throwable ignored){}
        }
        try {
            NoirScene scene=loadProjectScene(projectPath);
            EditorState editor=new EditorState(scene,projectPath);
            renderer=new NoirRenderer();
            renderer.applyScene(scene);

            // NoirGFX C++ is the editor viewport. Java remains the editor interaction
            // layer; Vulkan stays isolated until its full scene pipeline is ready.
            NoirGraphicsBackend.Type preferred=NoirGraphicsBackend.load(this);
            boolean useVulkan=preferred==NoirGraphicsBackend.Type.VULKAN && NoirGraphicsBackend.vulkanAvailable();
            renderer.setGraphicsBackend(useVulkan?NoirRenderer.GraphicsBackend.VULKAN:NoirRenderer.GraphicsBackend.GLES);
            if(useVulkan){
                NoirGraphicsBackend.markVulkanStarted(this);
                surface=new NoirVulkanSurface(this,renderer);
            }else{
                surface=new NoirSurface(this,renderer);
                if(preferred==NoirGraphicsBackend.Type.VULKAN)NoirGraphicsBackend.save(this,NoirGraphicsBackend.Type.GLES);
            }
            editorUi=new NoirEditorView(this,editor,renderer,(NoirViewport)surface);

            FrameLayout root=new FrameLayout(this);
            rootContainer=root;
            root.setBackgroundColor(NoirTheme.color("background",Color.rgb(246,243,236)));
            root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
            root.addView(editorUi,new FrameLayout.LayoutParams(-1,-1));
            setContentView(root);
        } catch(Throwable openError) {
            showOpenRecovery(projectPath,openError);
        }
    }

    public void switchGraphicsApi(int index){
        final int api=Math.max(0,Math.min(1,index));
        final boolean useVulkan=api==1;
        if(useVulkan && !NoirGraphicsBackend.vulkanAvailable()){
            android.widget.Toast.makeText(this,"Vulkan is unavailable on this device; keeping GLES.",android.widget.Toast.LENGTH_LONG).show();
            NoirNative.nativeSetGraphicsAPI(0);
            return;
        }
        try{ NoirNative.nativeSetGraphicsAPI(api); }catch(Throwable ignored){}
        if(renderer!=null) renderer.setGraphicsBackend(useVulkan?NoirRenderer.GraphicsBackend.VULKAN:NoirRenderer.GraphicsBackend.GLES);
        if(rootContainer==null||editorUi==null||surface==null)return;

        if(surface instanceof android.opengl.GLSurfaceView){
            try{((android.opengl.GLSurfaceView)surface).onPause();}catch(Throwable ignored){}
        }else{
            try{NoirNative.vulkanDetachSurface();}catch(Throwable ignored){}
        }
        rootContainer.removeView(surface);

        if(useVulkan) surface=new NoirVulkanSurface(this,renderer);
        else surface=new NoirSurface(this,renderer);
        editorUi.setViewport((NoirViewport)surface);
        rootContainer.addView(surface,0,new FrameLayout.LayoutParams(-1,-1));
        if(surface instanceof android.opengl.GLSurfaceView){
            try{((android.opengl.GLSurfaceView)surface).onResume();}catch(Throwable ignored){}
        }
        if(useVulkan) NoirGraphicsBackend.save(this,NoirGraphicsBackend.Type.VULKAN);
        else NoirGraphicsBackend.save(this,NoirGraphicsBackend.Type.GLES);
    }

    @Override protected void onDestroy(){
        NoirGraphicsBackend.shutdownVulkanStage();
        super.onDestroy();
    }

    private void showOpenRecovery(String projectPath, Throwable error){
        TextView view=new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16f);
        view.setPadding(48,48,48,48);
        String projectName=(projectPath==null||projectPath.trim().isEmpty())
                ? "New Project" : new File(projectPath).getName();
        view.setText("Noir Engine Recovery\n\n"
                +"Project: "+projectName+"\n"
                +"The project could not initialize the editor.\n"
                +"A safe fallback scene was prepared, but the current graphics surface failed.\n\n"
                +"A crash report may be available at Android/data/com.noir.game.engine/files/noir-crash/last_crash.txt.");
        view.setBackgroundColor(NoirTheme.color("surface",Color.rgb(255,253,248)));
        setContentView(view);
    }

    @Override public void onBackPressed(){
        if(editorUi!=null && editorUi.getVisibility()!=android.view.View.VISIBLE){
            editorUi.stopPlay();
            return;
        }
        super.onBackPressed();
    }

    private String normalizeProjectRoot(String raw){
        if(raw==null||raw.trim().isEmpty())return raw;
        try{
            File cur=new File(raw).getCanonicalFile();
            if(cur.isFile())cur=cur.getParentFile();
            for(int i=0;i<8&&cur!=null;i++){
                if(new File(cur,"project.game").isFile() && new File(cur,"scenes/Main.game").isFile())
                    return cur.getAbsolutePath();
                cur=cur.getParentFile();
            }
        }catch(Throwable ignored){}
        return raw;
    }

    private NoirScene loadProjectScene(String projectPath) {
        String fallback = "scene Main\n"
                + "node World {\n type = NODE3D\n}\n"
                + "node MainCamera {\n type = CAMERA3D\n position = (0, 2, 6)\n}\n";
        String src = fallback;

        try {
            if (projectPath != null && !projectPath.trim().isEmpty()) {
                File projectRoot = new File(projectPath);
                File canonicalRoot = projectRoot.getCanonicalFile();
                File projectFile = new File(canonicalRoot, "project.game");
                File sceneFile = new File(canonicalRoot, "scenes/Main.game");

                if (canonicalRoot.isDirectory() && projectFile.isFile() && sceneFile.isFile()) {
                    src = readUtf8(sceneFile);
                    if (src.trim().isEmpty()) src = fallback;
                }
            }
        } catch (Exception ignored) {
            src = fallback;
        }

        NoirScene scene;
        try {
            GameFileParser.Result result = new GameFileParser().parse(src, "scenes/Main.game");
            scene = result == null ? null : result.scene;
        } catch (Throwable ignored) {
            scene = null;
        }
        if (scene == null || scene.root == null) scene = new NoirScene("Main");

        NoirNode root = scene.root;
        try {
            NoirNode env = root.find("WorldEnvironment");
            if (env == null) {
                env = root.add(new NoirNode(
                        "WorldEnvironment", "WorldEnvironment", NoirNode.Kind.WORLD_ENVIRONMENT));
            }
            env.properties.putIfAbsent("sky", "procedural");
            env.properties.putIfAbsent("sky_mode", "PROCEDURAL_SKY");
            env.properties.putIfAbsent("clouds", "procedural");
            env.properties.putIfAbsent("sky_brightness", "1.0");
            env.properties.putIfAbsent("exposure", "1.0");
            env.properties.putIfAbsent("fog_density", "0.008");
            NoirNode sky = env.children.stream().filter(n -> n.kind == NoirNode.Kind.SKY3D).findFirst().orElse(null);
            if (sky == null) {
                sky = env.add(new NoirNode("Sky3D", "Sky3D", NoirNode.Kind.SKY3D));
                sky.properties.put("material", "ProceduralSkyMaterial");
            }
            if (root.find("Sun") == null) {
                NoirNode sun = root.add(new NoirNode("Sun", "Sun", NoirNode.Kind.LIGHT3D));
                sun.properties.put("type", "DIRECTIONAL");
                sun.properties.put("energy", "3.0");
                sun.properties.put("shadows", "PCF");
            }
            if (root.find("Player3D") == null) {
                NoirNode player = root.add(new NoirNode(
                        "Player3D", "Player3D", NoirNode.Kind.PLAYER3D));
                player.properties.put("script", "scripts/player.game");
                player.properties.put("speed", "5.0");
                player.properties.put("jump", "4.5");
                NoirNode cam = player.add(new NoirNode(
                        "Camera3D", "Camera3D", NoirNode.Kind.CAMERA3D));
                cam.properties.put("mobile_look", "touch+gyro");
            }
            if (root.find("MainCamera") == null) {
                root.add(new NoirNode("MainCamera", "MainCamera", NoirNode.Kind.CAMERA3D));
            }
        } catch (Throwable ignored) {}
        ensureWorldGeometry(scene, root);
        ensureRichWorld(scene, root);
        return scene;
    }

    private void ensureWorldGeometry(NoirScene scene, NoirNode root){
        boolean hasWorldGeometry=false;
        for(NoirNode n:scene.flatten()){
            switch(n.kind){
                case MESH3D: case TERRAIN3D: case FOLIAGE3D: case WATER3D:
                case STATIC_BODY3D: case RIGID_BODY3D: hasWorldGeometry=true; break;
                default: break;
            }
            if(hasWorldGeometry)break;
        }
        if(hasWorldGeometry)return;

        NoirNode terrain=root.add(new NoirNode("Terrain_Main","Terrain_Main",NoirNode.Kind.TERRAIN3D));
        terrain.properties.put("mesh","builtin/terrain");
        terrain.properties.put("material","NoirTerrainPBR");
        terrain.sx=18f; terrain.sy=0.35f; terrain.sz=18f;
        terrain.py=-0.35f;

        NoirNode water=root.add(new NoirNode("Water_Main","Water_Main",NoirNode.Kind.WATER3D));
        water.properties.put("material","NoirWaterPBR");
        water.sx=10f; water.sy=0.06f; water.sz=10f;
        water.py=-0.22f; water.pz=5f;

        float[][] rocks={
            {-8f,0.55f,-5f,1.7f,0.8f,1.4f},{-4f,0.45f,-7f,1.1f,0.7f,1.0f},
            {7f,0.75f,-6f,1.8f,1.0f,1.3f},{10f,0.42f,1f,1.0f,0.6f,1.2f},
            {-9f,0.62f,7f,1.5f,0.9f,1.5f},{5f,0.55f,8f,1.4f,0.8f,1.1f}
        };
        int id=0;
        for(float[] q:rocks){
            NoirNode rock=root.add(new NoirNode("Rock_"+(++id),"Rock_"+id,NoirNode.Kind.ROCK3D));
            rock.properties.put("mesh","environment/rock");
            rock.properties.put("material","RockPBR");
            rock.px=q[0];rock.py=q[1];rock.pz=q[2];rock.sx=q[3];rock.sy=q[4];rock.sz=q[5];
        }

        float[][] trees={
            {-11f,2.7f,-10f,1.4f},{-5f,3.0f,-11f,1.25f},{2f,3.4f,-10f,1.5f},
            {10f,3.0f,-10f,1.3f},{13f,2.6f,-2f,1.15f},{-12f,2.9f,2f,1.3f},
            {-9f,3.1f,10f,1.35f},{2f,3.2f,11f,1.4f},{11f,2.9f,8f,1.2f}
        };
        id=0;
        for(float[] q:trees){
            NoirNode tree=root.add(new NoirNode("Tree_"+(++id),"Tree_"+id,NoirNode.Kind.FOLIAGE3D));
            tree.properties.put("mesh","environment/tree_oak");
            tree.properties.put("material","FoliagePBR");
            tree.px=q[0];tree.py=q[1];tree.pz=q[2];tree.sx=tree.sz=q[3];tree.sy=q[3]*1.8f;
        }

        for(int i=0;i<8;i++){
            double a=i*Math.PI/4.0;
            float x=(float)Math.cos(a)*6.5f, z=(float)Math.sin(a)*6.5f;
            NoirNode grass=root.add(new NoirNode("Grass_"+i,"Grass_"+i,NoirNode.Kind.FOLIAGE3D));
            grass.properties.put("mesh","environment/grass");
            grass.properties.put("material","GrassPBR");
            grass.px=x;grass.py=0.15f;grass.pz=z;grass.sx=grass.sz=0.9f;grass.sy=1.3f;
        }
        NoirNode subject=root.add(new NoirNode("EnvironmentStatue","EnvironmentStatue",NoirNode.Kind.MESH3D));
        subject.properties.put("mesh","builtin/statue");
        subject.properties.put("material","StonePBR");
        subject.py=1.35f;subject.sx=1.1f;subject.sy=1.35f;subject.sz=1.1f;
    }

    /**
     * Augments authored terrain scenes with a deterministic outdoor blockout when the scene
     * only contains the core terrain/platform nodes. Rendering remains entirely native C++;
     * this method only prepares the scene graph consumed by the native renderer.
     */
    private void ensureRichWorld(NoirScene scene, NoirNode root){
        if(scene==null||root==null)return;
        boolean hasTerrain=false,hasWater=false,hasRock=false,hasFoliage=false,hasStatue=false;
        for(NoirNode n:scene.flatten()){
            switch(n.kind){
                case TERRAIN3D: hasTerrain=true; break;
                case WATER3D: hasWater=true; break;
                case ROCK3D: hasRock=true; break;
                case FOLIAGE3D: hasFoliage=true; break;
                case MESH3D:
                    if("EnvironmentStatue".equals(n.name))hasStatue=true;
                    break;
                default: break;
            }
        }
        // The authored scene may already have a large terrain. The parser normalized its
        // size property into the transform so the native renderer receives metre-scale data.
        if(!hasTerrain){
            NoirNode terrain=root.add(new NoirNode("Terrain_Main","Terrain_Main",NoirNode.Kind.TERRAIN3D));
            terrain.properties.put("mesh","builtin/terrain");
            terrain.properties.put("material","NoirTerrainPBR");
            terrain.sx=22f;terrain.sy=0.5f;terrain.sz=22f;terrain.py=-0.35f;
        }
        if(!hasWater){
            NoirNode water=root.add(new NoirNode("Water_Main","Water_Main",NoirNode.Kind.WATER3D));
            water.properties.put("material","NoirWaterPBR");
            water.sx=11f;water.sy=0.06f;water.sz=9f;water.py=-0.20f;water.pz=6f;
        }
        if(!hasRock){
            float[][] rocks={
                {-8f,0.55f,-5f,1.7f,0.8f,1.4f},{-4f,0.45f,-7f,1.1f,0.7f,1.0f},
                {7f,0.75f,-6f,1.8f,1.0f,1.3f},{10f,0.42f,1f,1.0f,0.6f,1.2f},
                {-9f,0.62f,7f,1.5f,0.9f,1.5f},{5f,0.55f,8f,1.4f,0.8f,1.1f}
            };
            int id=0;
            for(float[] q:rocks){
                NoirNode rock=root.add(new NoirNode("Rock_"+(++id),"Rock_"+id,NoirNode.Kind.ROCK3D));
                rock.properties.put("mesh","environment/rock");rock.properties.put("material","RockPBR");
                rock.px=q[0];rock.py=q[1];rock.pz=q[2];rock.sx=q[3];rock.sy=q[4];rock.sz=q[5];
            }
        }
        if(!hasFoliage){
            float[][] trees={
                {-11f,2.7f,-10f,1.4f},{-5f,3.0f,-11f,1.25f},{2f,3.4f,-10f,1.5f},
                {10f,3.0f,-10f,1.3f},{13f,2.6f,-2f,1.15f},{-12f,2.9f,2f,1.3f},
                {-9f,3.1f,10f,1.35f},{2f,3.2f,11f,1.4f},{11f,2.9f,8f,1.2f}
            };
            int id=0;
            for(float[] q:trees){
                NoirNode tree=root.add(new NoirNode("Tree_"+(++id),"Tree_"+id,NoirNode.Kind.FOLIAGE3D));
                tree.properties.put("mesh","environment/tree_oak");tree.properties.put("material","FoliagePBR");
                tree.px=q[0];tree.py=q[1];tree.pz=q[2];tree.sx=tree.sz=q[3];tree.sy=q[3]*1.8f;
            }
            for(int i=0;i<8;i++){
                double a=i*Math.PI/4.0;
                NoirNode grass=root.add(new NoirNode("Grass_"+i,"Grass_"+i,NoirNode.Kind.FOLIAGE3D));
                grass.properties.put("mesh","environment/grass");grass.properties.put("material","GrassPBR");
                grass.px=(float)Math.cos(a)*6.5f;grass.py=0.15f;grass.pz=(float)Math.sin(a)*6.5f;
                grass.sx=grass.sz=0.9f;grass.sy=1.3f;
            }
        }
        if(!hasStatue){
            NoirNode subject=root.add(new NoirNode("EnvironmentStatue","EnvironmentStatue",NoirNode.Kind.MESH3D));
            subject.properties.put("mesh","builtin/statue");subject.properties.put("material","StonePBR");
            subject.py=1.35f;subject.sx=1.1f;subject.sy=1.35f;subject.sz=1.1f;
        }
    }

    private String readUtf8(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(file.length(), 1024L * 1024L));
            byte[] buffer = new byte[8192];
            int n;
            long total = 0L;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > 8L * 1024L * 1024L) throw new IOException("Scene file is too large");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
