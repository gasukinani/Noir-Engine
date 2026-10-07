package com.noir.game.engine;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
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
    private NoirSurface surface;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);

        String projectPath=getIntent().getStringExtra("project_path");
        if(projectPath!=null && !projectPath.trim().isEmpty()){
            try{ new NoirProjectWorkspace(this).ensureCSharpLayout(new File(projectPath).getCanonicalFile()); }catch(Throwable ignored){}
        }
        try {
            NoirScene scene=loadProjectScene(projectPath);
            EditorState editor=new EditorState(scene,projectPath);
            renderer=new NoirRenderer();

            // Three.js/WebGL2 is now the editor viewport. Native GLES/Vulkan
            // remains available for runtime/engine work, but the editor no
            // longer depends on the incomplete native Vulkan clear-only path.
            renderer.setGraphicsBackend(NoirRenderer.GraphicsBackend.GLES);
            surface=new NoirSurface(this,renderer);
            surface.setEditorTapListener((x,y)->{});
            editorUi=new NoirEditorView(this,editor,renderer,surface);

            FrameLayout root=new FrameLayout(this);
            root.setBackgroundColor(Color.rgb(11,18,32));
            root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
            root.addView(editorUi,new FrameLayout.LayoutParams(-1,-1));
            setContentView(root);
        } catch(Throwable openError) {
            showOpenRecovery(projectPath,openError);
        }
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
                +"Open the project again after checking the editor logs.");
        view.setBackgroundColor(Color.rgb(18,20,24));
        setContentView(view);
    }

    @Override public void onBackPressed(){
        if(editorUi!=null && editorUi.getVisibility()!=android.view.View.VISIBLE){
            editorUi.stopPlay();
            return;
        }
        super.onBackPressed();
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
            if (root.find("WorldEnvironment") == null) {
                NoirNode env = root.add(new NoirNode(
                        "WorldEnvironment", "WorldEnvironment", NoirNode.Kind.WORLD_ENVIRONMENT));
                env.properties.put("sky", "procedural");
                env.properties.put("clouds", "procedural");
                env.properties.put("exposure", "1.0");
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
        return scene;
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
