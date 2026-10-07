package com.noir.game.engine.render;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import java.nio.*;
import java.util.*;
import com.noir.game.engine.scene.NoirNode;
import com.noir.game.engine.scene.NoirScene;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Noir mobile forward renderer.
 *
 * Features:
 * - OpenGL ES 3.0 forward PBR-style lighting
 * - procedural sky + animated cloud layer
 * - directional sun
 * - depth-tested shadow map with 3x3 PCF filtering
 * - environment/specular reflections from the procedural sky
 * - MSAA is requested by NoirSurface
 * - editor orbit camera and runtime FPS/mobile look
 * - cached GPU buffers; no per-frame mesh allocation
 */
public final class NoirRenderer implements GLSurfaceView.Renderer {
    public enum Mode { EDITOR, RUNTIME }
    public enum QualityPreset { MOBILE, MEDIUM, HIGH, ULTRA, EXTREME }
    public enum GraphicsBackend { GLES, VULKAN }
    private GraphicsBackend backend=GraphicsBackend.GLES;
    private boolean gpuReady;
    private volatile boolean pendingShadowRebuild;

    public static final class Camera {
        public float yaw = -90f, pitch = 12f, distance = 18f;
        public float targetX = 0f, targetY = 1.4f, targetZ = 0f;
        public float x, y, z;
        public void updateOrbit() {
            float yr=(float)Math.toRadians(yaw), pr=(float)Math.toRadians(pitch);
            x=targetX+(float)(Math.cos(pr)*Math.cos(yr)*distance);
            y=targetY+(float)(Math.sin(pr)*distance);
            z=targetZ+(float)(Math.cos(pr)*Math.sin(yr)*distance);
        }
    }

    public static final class RuntimeCamera {
        public float x=0f,y=1.7f,z=6f;
        public float yaw=-90f,pitch=0f;
    }

    public static final class Quality {
        public int shadowSize=1024;
        public boolean shadows=true;
        public boolean reflections=true;
        public boolean clouds=true;
        public boolean fog=true;
        public boolean sunRays=true;
        public boolean godRays=true;
        public boolean bloom=true;
        public boolean toneMapping=true;
        public boolean colorGrading=true;
        public boolean ambientOcclusion=true;
        public int shadowPcfRadius=1;
        public float exposure=1.25f;
        public float renderScale=1.0f;
        public float contrast=1.04f;
        public float saturation=1.06f;
        public float godRayStrength=0.42f;
    }

    private final Camera editorCamera=new Camera();
    private final RuntimeCamera runtimeCamera=new RuntimeCamera();
    private final Quality quality=new Quality();
    private final WorldEnvironmentSettings environment=new WorldEnvironmentSettings();
    private Mode mode=Mode.EDITOR;

    private int width=1,height=1;
    private int mainProgram, skyProgram, shadowProgram;
    private int cubeVbo, groundVbo;
    private int shadowFbo, shadowTexture;
    private int uModel,uViewProj,uNormal,uCamera,uSunDir,uSunColor,uSky,uBaseColor,uRough,uMetal,uShadow,uLightVP,uExposure,uReflections,uFog,uTone,uContrast,uSaturation,uGodRays,uBloom,uPcfRadius;
    private int sModel,sLightVP;
    private int skyTime, skyForward, skyRight, skyUp, skyAspect, skyClouds, skySunRays, skyGodRays, skySunDir, skyHorizon, skyZenith, skyBrightness, skyCloudCoverage;
    private long lastNanos;
    private float frameTimeMs;

    private final float[] cubeModels=new float[64*16];
    private final float[] cubeRoughness=new float[64];
    private final float[] cubeMetallic=new float[64];
    private final float[] cubeColor=new float[64*3];
    private int cubeCount=0;
    private final float[] groundModel=new float[16];
    private float time;
    private volatile float[] sceneSnapshot=new float[0];
    private volatile int sceneSnapshotVersion;
    private int nativeSceneVersion=-1;
    private int nativeQualityTier=1;

    public NoirRenderer(){ setQualityPreset(QualityPreset.MEDIUM); editorCamera.updateOrbit(); }
    public GraphicsBackend graphicsBackend(){return backend;}
    public boolean isGpuReady(){return gpuReady;}
    public void setGraphicsBackend(GraphicsBackend b){backend=b==null?GraphicsBackend.GLES:b;}
    public String graphicsBackendStatus(){return backend==GraphicsBackend.VULKAN?"VULKAN • native backend / safe GLES fallback":"GLES 3.0 • Forward PBR";}

    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        gpuReady=false;
        GLES30.glClearColor(0.025f,0.04f,0.065f,1f);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA,GLES30.GL_ONE_MINUS_SRC_ALPHA);

        try {
            mainProgram=link(mainVertex(),mainFragment());
            skyProgram=link(skyVertex(),skyFragment());
            shadowProgram=link(shadowVertex(),shadowFragment());
        } catch(Throwable shaderFailure) {
            deleteProgramSafe(mainProgram);
            deleteProgramSafe(skyProgram);
            deleteProgramSafe(shadowProgram);
            mainProgram=0; skyProgram=0; shadowProgram=0;
            quality.shadows=false;
            lastNanos=System.nanoTime();
            frameTimeMs=0f;
            return;
        }

        uModel=GLES30.glGetUniformLocation(mainProgram,"uModel");
        uViewProj=GLES30.glGetUniformLocation(mainProgram,"uViewProj");
        uNormal=GLES30.glGetUniformLocation(mainProgram,"uNormal");
        uCamera=GLES30.glGetUniformLocation(mainProgram,"uCamera");
        uSunDir=GLES30.glGetUniformLocation(mainProgram,"uSunDir");
        uSunColor=GLES30.glGetUniformLocation(mainProgram,"uSunColor");
        uSky=GLES30.glGetUniformLocation(mainProgram,"uSky");
        uBaseColor=GLES30.glGetUniformLocation(mainProgram,"uBaseColor");
        uRough=GLES30.glGetUniformLocation(mainProgram,"uRough");
        uMetal=GLES30.glGetUniformLocation(mainProgram,"uMetal");
        uShadow=GLES30.glGetUniformLocation(mainProgram,"uShadow");
        uLightVP=GLES30.glGetUniformLocation(mainProgram,"uLightVP");
        uExposure=GLES30.glGetUniformLocation(mainProgram,"uExposure");
        uReflections=GLES30.glGetUniformLocation(mainProgram,"uReflections");
        uFog=GLES30.glGetUniformLocation(mainProgram,"uFog");
        uTone=GLES30.glGetUniformLocation(mainProgram,"uTone");
        uContrast=GLES30.glGetUniformLocation(mainProgram,"uContrast");
        uSaturation=GLES30.glGetUniformLocation(mainProgram,"uSaturation");
        uGodRays=GLES30.glGetUniformLocation(mainProgram,"uGodRays");
        uBloom=GLES30.glGetUniformLocation(mainProgram,"uBloom");
        uPcfRadius=GLES30.glGetUniformLocation(mainProgram,"uPcfRadius");
        sModel=GLES30.glGetUniformLocation(shadowProgram,"uModel");
        sLightVP=GLES30.glGetUniformLocation(shadowProgram,"uLightVP");
        skyTime=GLES30.glGetUniformLocation(skyProgram,"uTime");
        skyForward=GLES30.glGetUniformLocation(skyProgram,"uForward");
        skyRight=GLES30.glGetUniformLocation(skyProgram,"uRight");
        skyUp=GLES30.glGetUniformLocation(skyProgram,"uUp");
        skyAspect=GLES30.glGetUniformLocation(skyProgram,"uAspect");
        skyClouds=GLES30.glGetUniformLocation(skyProgram,"uClouds");
        skySunRays=GLES30.glGetUniformLocation(skyProgram,"uSunRays");
        skyGodRays=GLES30.glGetUniformLocation(skyProgram,"uGodRays");
        skySunDir=GLES30.glGetUniformLocation(skyProgram,"uSunDir");
        skyHorizon=GLES30.glGetUniformLocation(skyProgram,"uHorizon");
        skyZenith=GLES30.glGetUniformLocation(skyProgram,"uZenith");
        skyBrightness=GLES30.glGetUniformLocation(skyProgram,"uSkyBrightness");
        skyCloudCoverage=GLES30.glGetUniformLocation(skyProgram,"uCloudCoverage");

        createMeshes();
        createShadowMap();
        buildSceneModels();
        lastNanos=System.nanoTime();
        frameTimeMs=0f;
        gpuReady=true;
    }

    @Override public void onSurfaceChanged(GL10 gl,int w,int h){
        width=Math.max(1,w); height=Math.max(1,h);
        GLES30.glViewport(0,0,width,height);
    }

    @Override public void onDrawFrame(GL10 gl){
        long now=System.nanoTime();
        float dt=Math.min(0.05f,(now-lastNanos)/1_000_000_000f);
        lastNanos=now;
        frameTimeMs=dt*1000f;
        time+=dt;
        if(mode==Mode.EDITOR) editorCamera.updateOrbit();
        if(pendingShadowRebuild){pendingShadowRebuild=false;recreateShadowMapIfReady();}
        if(!gpuReady){
            GLES30.glClearColor(0.025f,0.04f,0.065f,1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            return;
        }

        if(quality.shadows && shadowFbo!=0) renderShadowPass();
        renderMainPass();
    }

    private void deleteProgramSafe(int id){if(id!=0)try{GLES30.glDeleteProgram(id);}catch(Throwable ignored){}}

    public Camera camera(){if(mode==Mode.EDITOR)editorCamera.updateOrbit();return editorCamera;}
    public RuntimeCamera runtimeCamera(){return runtimeCamera;}
    public Quality quality(){return quality;}
    public WorldEnvironmentSettings environment(){return environment;}

    /** Applies a scene environment profile without coupling the editor to GLES internals. */
    public void applyScene(NoirScene scene){
        if(scene==null)return;
        applyWorldEnvironmentFromMap(scene);
        setScene(scene);
    }

    private void applyWorldEnvironmentFromMap(NoirScene scene){
        if(scene==null)return;
        String mode=scene.environment.get("sky_mode");
        String brightness=scene.environment.get("sky_brightness");
        String exposure=scene.environment.get("exposure");
        String fog=scene.environment.get("fog_density");
        NoirNode envNode=scene.root.find("WorldEnvironment");
        if(envNode!=null){
            if(mode==null)mode=envNode.properties.get("sky_mode");
            if(brightness==null)brightness=envNode.properties.get("sky_brightness");
            if(exposure==null)exposure=envNode.properties.get("exposure");
            if(fog==null)fog=envNode.properties.get("fog_density");
        }
        try{ if(mode!=null)environment.setSkyMode(WorldEnvironmentSettings.SkyMode.valueOf(mode.toUpperCase(Locale.US))); }catch(Exception ignored){}
        try{ if(brightness!=null)environment.setSkyBrightness(Float.parseFloat(brightness)); }catch(Exception ignored){}
        try{ if(exposure!=null)environment.setExposure(Float.parseFloat(exposure)); }catch(Exception ignored){}
        try{ if(fog!=null)environment.setFog(Float.parseFloat(fog),environment.fogHeight); }catch(Exception ignored){}
    }

    public void setScene(NoirScene scene){
        if(scene==null){sceneSnapshot=new float[0];sceneSnapshotVersion++;return;}
        ArrayList<float[]> rows=new ArrayList<>();
        collectSceneNodes(scene.root,0f,0f,0f,1f,1f,1f,0f,0f,0f,rows);
        float[] packed=new float[rows.size()*10];
        int o=0;
        for(float[] row:rows){System.arraycopy(row,0,packed,o,10);o+=10;}
        sceneSnapshot=packed;
        sceneSnapshotVersion++;
    }

    private void collectSceneNodes(NoirNode node,float pX,float pY,float pZ,
                                    float pSx,float pSy,float pSz,float pRx,float pRy,float pRz,
                                    ArrayList<float[]> rows){
        if(node==null)return;
        // Environment resources are background/render-state, never scene geometry.
        if(node.kind==NoirNode.Kind.WORLD_ENVIRONMENT ||
                node.kind==NoirNode.Kind.SKY3D ||
                node.kind==NoirNode.Kind.FOG_VOLUME3D ||
                node.kind==NoirNode.Kind.POST_PROCESS3D){
            return;
        }
        if(node.parent!=null && node.visible){
            float wx=pX+node.px*pSx, wy=pY+node.py*pSy, wz=pZ+node.pz*pSz;
            float wsx=pSx*node.sx, wsy=pSy*node.sy, wsz=pSz*node.sz;
            if(!Float.isFinite(wsx)||Math.abs(wsx)<0.001f)wsx=node.sx;
            if(!Float.isFinite(wsy)||Math.abs(wsy)<0.001f)wsy=node.sy;
            if(!Float.isFinite(wsz)||Math.abs(wsz)<0.001f)wsz=node.sz;
            if(isRenderableSceneKind(node.kind)){
                rows.add(new float[]{wx,wy,wz,wsx,wsy,wsz,pRx+node.rx,pRy+node.ry,pRz+node.rz,node.kind.ordinal()});
            }
            pX=wx;pY=wy;pZ=wz;pSx=wsx;pSy=wsy;pSz=wsz;pRx+=node.rx;pRy+=node.ry;pRz+=node.rz;
        }
        for(NoirNode child:node.children)
            collectSceneNodes(child,pX,pY,pZ,pSx,pSy,pSz,pRx,pRy,pRz,rows);
    }

    private static boolean isRenderableSceneKind(NoirNode.Kind kind){
        switch(kind){
            case CHARACTER3D:
            case PLAYER3D:
            case MESH3D:
            case SKINNED_MESH3D:
            case COLLIDER3D:
            case RIGID_BODY3D:
            case STATIC_BODY3D:
            case AREA3D:
            case WATER3D:
            case TERRAIN3D:
            case FOLIAGE3D:
            case DECAL3D:
            case SPLINE3D:
            case VEHICLE3D:
                return true;
            default:
                return false;
        }
    }

    public float[] sceneSnapshot(){return sceneSnapshot;}
    public int sceneSnapshotVersion(){return sceneSnapshotVersion;}
    public boolean nativeSceneApplied(){return nativeSceneVersion==sceneSnapshotVersion;}
    public void markNativeSceneApplied(){nativeSceneVersion=sceneSnapshotVersion;}
    public int nativeQualityTier(){return nativeQualityTier;}

    public int environmentSkyMode(){
        switch(environment.skyMode){
            case PHYSICAL_SKY:return 1;
            case PROCEDURAL_SKY:return 2;
            case SHADER_SKY_MATERIAL:return 3;
            case GRADIENT:return 4;
            case CUBEMAP:return 5;
            case HDRI:return 6;
            default:return 0;
        }
    }

    public float environmentExposure(){return environment.exposure;}
    public float environmentSkyBrightness(){return environment.skyBrightness;}
    public float environmentFogDensity(){return environment.fogDensity;}
    public float[] environmentSunDirection(){return environment.sunDirection();}

    public void applyWorldEnvironment(WorldEnvironmentSettings settings){
        if(settings==null||!settings.valid())return;
        environment.skyMode=settings.skyMode;
        environment.skyAsset=settings.skyAsset;
        environment.skyBrightness=settings.skyBrightness;
        environment.sunEnergy=settings.sunEnergy;
        environment.ambientEnergy=settings.ambientEnergy;
        environment.fogDensity=settings.fogDensity;
        environment.fogHeight=settings.fogHeight;
        environment.exposure=settings.exposure;
        environment.whitePoint=settings.whitePoint;
        environment.cloudCoverage=settings.cloudCoverage;
        environment.cloudDensity=settings.cloudDensity;
        environment.cloudSpeed=settings.cloudSpeed;
        environment.sunYaw=settings.sunYaw;
        environment.sunPitch=settings.sunPitch;
        environment.fogEnabled=settings.fogEnabled;
        environment.volumetricEnabled=settings.volumetricEnabled;
        environment.cloudsEnabled=settings.cloudsEnabled;
        environment.sunRays=settings.sunRays;
        environment.tonemap=settings.tonemap;
        environment.autoExposure=settings.autoExposure;
    }

    public void setQualityPreset(QualityPreset preset){
        switch(preset){
            case MOBILE:
            case MEDIUM:
                nativeQualityTier=1;
                quality.shadowSize=1024; quality.shadowPcfRadius=1; quality.sunRays=true; quality.godRays=false;
                quality.clouds=true; quality.reflections=true; quality.fog=true;
                quality.bloom=false; quality.ambientOcclusion=false; quality.toneMapping=true; quality.colorGrading=false;
                quality.exposure=1.25f; quality.renderScale=0.85f; break;
            case HIGH:
                nativeQualityTier=2;
                quality.shadowSize=1536; quality.shadowPcfRadius=1; quality.sunRays=true; quality.godRays=false;
                quality.bloom=true; quality.ambientOcclusion=true; quality.toneMapping=true; quality.colorGrading=true;
                quality.exposure=1.25f; quality.renderScale=1.0f; break;
            case ULTRA:
                nativeQualityTier=3;
                quality.shadowSize=2048; quality.shadowPcfRadius=2; quality.sunRays=true; quality.godRays=true;
                quality.bloom=true; quality.ambientOcclusion=true; quality.toneMapping=true; quality.colorGrading=true;
                quality.exposure=1.05f; quality.renderScale=1.0f; break;
            case EXTREME:
                nativeQualityTier=4;
                quality.shadowSize=2048; quality.shadowPcfRadius=2; quality.sunRays=true; quality.godRays=true;
                quality.bloom=true; quality.ambientOcclusion=true; quality.toneMapping=true; quality.colorGrading=true;
                quality.exposure=1.10f; quality.renderScale=1.0f; break;
        }
        pendingShadowRebuild=true;
    }

    public void setMode(Mode m){mode=m;}
    public Mode mode(){return mode;}

    public void orbit(float dx,float dy){
        if(mode!=Mode.EDITOR)return;
        // One-finger editor orbit: drag right = rotate right, drag up = orbit upward.
        editorCamera.yaw+=dx*0.24f;
        editorCamera.pitch=Math.max(-82f,Math.min(82f,editorCamera.pitch-dy*0.24f));
        editorCamera.distance=Math.max(2.0f,Math.min(100f,editorCamera.distance));
    }

    public void pan(float dx,float dy){
        if(mode!=Mode.EDITOR)return;
        float[] forward=cameraForward();
        float[] right=normalize(cross(forward,new float[]{0,1,0}));
        float[] up=normalize(cross(right,forward));
        float scale=editorCamera.distance*0.0026f;
        editorCamera.targetX+=(-right[0]*dx+up[0]*dy)*scale;
        editorCamera.targetY+=(-right[1]*dx+up[1]*dy)*scale;
        editorCamera.targetZ+=(-right[2]*dx+up[2]*dy)*scale;
    }

    public void zoom(float amount){
        if(mode==Mode.EDITOR) editorCamera.distance=Math.max(2.0f,Math.min(100f,editorCamera.distance+amount));
    }

    public void resetEditorCamera(){
        editorCamera.yaw=-90f;
        editorCamera.pitch=12f;
        editorCamera.distance=18f;
        editorCamera.targetX=0f;editorCamera.targetY=1.4f;editorCamera.targetZ=0f;
        editorCamera.updateOrbit();
    }

    public float frameTimeMs(){return frameTimeMs;}
    public void setFrameTimeMs(float ms){frameTimeMs=Math.max(0.1f,ms);}

    public float gizmoWorldSize(){
        return Math.max(0.8f,Math.min(4.5f,editorCamera.distance*0.10f));
    }

    public float gizmoPixelSize(){return Math.max(54f,Math.min(120f,gizmoWorldSize()*42f));}

    public float[] cameraForward(){
        float yaw=mode==Mode.RUNTIME?runtimeCamera.yaw:editorCamera.yaw;
        float pitch=mode==Mode.RUNTIME?runtimeCamera.pitch:editorCamera.pitch;
        float yr=(float)Math.toRadians(yaw),pr=(float)Math.toRadians(pitch);
        return normalize(new float[]{
            (float)(Math.cos(pr)*Math.cos(yr)),
            (float)Math.sin(pr),
            (float)(Math.cos(pr)*Math.sin(yr))
        });
    }

    /** Returns origin xyz + normalized direction xyz for a viewport pixel. */
    public float[] screenRay(float sx,float sy){
        if(width<=0||height<=0)return null;
        float nx=(sx/(float)width)*2f-1f;
        float ny=1f-(sy/(float)height)*2f;
        float tan=(float)Math.tan(Math.toRadians(64f)*0.5);
        float aspect=(float)width/(float)Math.max(1,height);
        float[] forward=cameraForward();
        float[] right=normalize(cross(forward,new float[]{0,1,0}));
        float[] up=normalize(cross(right,forward));
        float[] dir=normalize(new float[]{
            forward[0]+right[0]*nx*aspect*tan+up[0]*ny*tan,
            forward[1]+right[1]*nx*aspect*tan+up[1]*ny*tan,
            forward[2]+right[2]*nx*aspect*tan+up[2]*ny*tan
        });
        float ox,oy,oz;
        if(mode==Mode.RUNTIME){ox=runtimeCamera.x;oy=runtimeCamera.y;oz=runtimeCamera.z;}
        else {ox=editorCamera.x;oy=editorCamera.y;oz=editorCamera.z;}
        return new float[]{ox,oy,oz,dir[0],dir[1],dir[2]};
    }

    public void runtimeLook(float dx,float dy){
        if(mode!=Mode.RUNTIME)return;
        runtimeCamera.yaw+=dx*0.16f;
        runtimeCamera.pitch=Math.max(-85f,Math.min(85f,runtimeCamera.pitch+dy*0.16f));
    }

    public void runtimeMove(float forward,float strafe,float dt){
        if(mode!=Mode.RUNTIME)return;
        float yr=(float)Math.toRadians(runtimeCamera.yaw);
        float fx=(float)Math.cos(yr), fz=(float)Math.sin(yr);
        runtimeCamera.x+=(fx*forward-fz*strafe)*dt*5f;
        runtimeCamera.z+=(fz*forward+fx*strafe)*dt*5f;
    }

    private void renderShadowPass(){
        int s=quality.shadowSize;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,shadowFbo);
        GLES30.glViewport(0,0,s,s);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glUseProgram(shadowProgram);

        float[] lightVP=lightViewProj();
        GLES30.glUniformMatrix4fv(sLightVP,1,false,lightVP,0);
        drawShadowModel(groundModel);
        for(int i=0;i<cubeCount;i++)drawShadowModel(cubeModels,i);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        GLES30.glViewport(0,0,width,height);
    }

    private void renderMainPass(){
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        GLES30.glViewport(0,0,width,height);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);

        drawSky();

        GLES30.glUseProgram(mainProgram);
        float[] vp=viewProj();
        float[] lightVP=lightViewProj();
        GLES30.glUniformMatrix4fv(uViewProj,1,false,vp,0);
        GLES30.glUniformMatrix4fv(uLightVP,1,false,lightVP,0);
        float[] sun=environment.sunDirection();
        GLES30.glUniform3f(uSunDir,sun[0],sun[1],sun[2]);
        float sunScale=Math.max(0f,environment.sunEnergy/2f);
        GLES30.glUniform3f(uSunColor,1.0f*sunScale,0.93f*sunScale,0.82f*sunScale);
        float skyScale=Math.max(0f,environment.skyBrightness);
        GLES30.glUniform3f(uSky,0.38f*skyScale,0.56f*skyScale,0.82f*skyScale);
        GLES30.glUniform1f(uExposure,quality.exposure*environment.exposure);
        GLES30.glUniform1f(uReflections,quality.reflections?1f:0f);
        GLES30.glUniform1f(uFog,quality.fog&&environment.fogEnabled?Math.max(0f,environment.fogDensity*12f):0f);
        GLES30.glUniform1f(uTone,quality.toneMapping?1f:0f);
        GLES30.glUniform1f(uContrast,quality.colorGrading?quality.contrast:1f);
        GLES30.glUniform1f(uSaturation,quality.colorGrading?quality.saturation:1f);
        GLES30.glUniform1f(uGodRays,quality.godRays?quality.godRayStrength:0f);
        GLES30.glUniform1f(uBloom,quality.bloom?1f:0f);
        GLES30.glUniform1f(uPcfRadius,quality.shadowPcfRadius);
        GLES30.glUniform1i(uShadow,0);
        GLES30.glUniform3f(uBaseColor,0.28f,0.31f,0.34f);

        float cx,cy,cz;
        if(mode==Mode.RUNTIME){cx=runtimeCamera.x;cy=runtimeCamera.y;cz=runtimeCamera.z;}
        else {cx=editorCamera.x;cy=editorCamera.y;cz=editorCamera.z;}
        GLES30.glUniform3f(uCamera,cx,cy,cz);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,shadowTexture);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,cubeVbo);
        enableMainAttributes();
        GLES30.glUniform1f(uRough,0.62f);
        GLES30.glUniform1f(uMetal,0.02f);
        GLES30.glUniform3f(uBaseColor,0.18f,0.21f,0.24f);
        drawModel(groundModel);
        for(int i=0;i<cubeCount;i++){
            int c=i*3;
            GLES30.glUniform3f(uBaseColor,cubeColor[c],cubeColor[c+1],cubeColor[c+2]);
            GLES30.glUniform1f(uRough,cubeRoughness[i]);
            GLES30.glUniform1f(uMetal,cubeMetallic[i]);
            drawModel(cubeModels,i);
        }
        disableMainAttributes();
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);
    }

    private void drawSky(){
        GLES30.glDepthMask(false);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glUseProgram(skyProgram);
        GLES30.glUniform1f(skyTime,time);
        float yaw=mode==Mode.RUNTIME?runtimeCamera.yaw:editorCamera.yaw;
        float pitch=mode==Mode.RUNTIME?runtimeCamera.pitch:editorCamera.pitch;
        float yr=(float)Math.toRadians(yaw),pr=(float)Math.toRadians(pitch);
        float[] forward={(float)(Math.cos(pr)*Math.cos(yr)),(float)Math.sin(pr),(float)(Math.cos(pr)*Math.sin(yr))};
        float[] right=normalize(cross(forward,new float[]{0f,1f,0f}));
        float[] up=normalize(cross(right,forward));
        GLES30.glUniform3f(skyForward,forward[0],forward[1],forward[2]);
        GLES30.glUniform3f(skyRight,right[0],right[1],right[2]);
        GLES30.glUniform3f(skyUp,up[0],up[1],up[2]);
        GLES30.glUniform1f(skyAspect,(float)width/Math.max(1,height));
        float[] sun=environment.sunDirection();
        GLES30.glUniform3f(skySunDir,sun[0],sun[1],sun[2]);
        GLES30.glUniform3f(skyHorizon,environment.horizon[0],environment.horizon[1],environment.horizon[2]);
        GLES30.glUniform3f(skyZenith,environment.zenith[0],environment.zenith[1],environment.zenith[2]);
        GLES30.glUniform1f(skyBrightness,environment.skyBrightness);
        GLES30.glUniform1f(skyCloudCoverage,environment.cloudCoverage);
        GLES30.glUniform1f(skyClouds,quality.clouds&&environment.cloudsEnabled?environment.cloudDensity:0f);
        GLES30.glUniform1f(skySunRays,quality.sunRays&&environment.sunRays?1f:0f);
        GLES30.glUniform1f(skyGodRays,quality.godRays&&environment.volumetricEnabled?1f:0f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glDepthMask(true);
    }

    private void createMeshes(){
        float[] v={
            // position, normal, uv
            -1,-1,-1, 0,0,-1, 0,0, 1,-1,-1,0,0,-1,1,0, 1,1,-1,0,0,-1,1,1,
            -1,-1,-1,0,0,-1,0,0, 1,1,-1,0,0,-1,1,1, -1,1,-1,0,0,-1,0,1,
            -1,-1,1,0,0,1,0,0, -1,1,1,0,0,1,0,1, 1,1,1,0,0,1,1,1,
            -1,-1,1,0,0,1,0,0, 1,1,1,0,0,1,1,1, 1,-1,1,0,0,1,1,0,
            -1,-1,-1,-1,0,0,0,0, -1,1,-1,-1,0,0,0,1, -1,1,1,-1,0,0,1,1,
            -1,-1,-1,-1,0,0,0,0, -1,1,1,-1,0,0,1,1, -1,-1,1,-1,0,0,1,0,
            1,-1,-1,1,0,0,0,0, 1,-1,1,1,0,0,1,0, 1,1,1,1,0,0,1,1,
            1,-1,-1,1,0,0,0,0, 1,1,1,1,0,0,1,1, 1,1,-1,1,0,0,0,1,
            -1,1,-1,0,1,0,0,0, 1,1,-1,0,1,0,1,0, 1,1,1,0,1,0,1,1,
            -1,1,-1,0,1,0,0,0, 1,1,1,0,1,0,1,1, -1,1,1,0,1,0,0,1,
            -1,-1,-1,0,-1,0,0,0, -1,-1,1,0,-1,0,0,1, 1,-1,1,0,-1,0,1,1,
            -1,-1,-1,0,-1,0,0,0, 1,-1,1,0,-1,0,1,1, 1,-1,-1,0,-1,0,1,0
        };
        FloatBuffer buf=ByteBuffer.allocateDirect(v.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(v).flip();
        int[] ids=new int[2];
        GLES30.glGenBuffers(2,ids,0);
        cubeVbo=ids[0]; groundVbo=ids[1];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,cubeVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,v.length*4,buf,GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
    }

    private void recreateShadowMapIfReady(){
        if(mainProgram==0 || !quality.shadows) return;
        if(shadowTexture!=0) GLES30.glDeleteTextures(1,new int[]{shadowTexture},0);
        if(shadowFbo!=0) GLES30.glDeleteFramebuffers(1,new int[]{shadowFbo},0);
        shadowTexture=0; shadowFbo=0; createShadowMap();
    }

    private void createShadowMap(){
        // Mobile-safe shadow allocation. Some devices expose GLES 3 but have
        // much lower texture limits or cannot allocate a 2048^2 depth target.
        int[] maxTexture=new int[1];
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE,maxTexture,0);
        int requested=Math.max(512,Math.min(quality.shadowSize,2048));
        int size=Math.min(requested,Math.max(512,maxTexture[0]));
        int[] t=new int[1],f=new int[1];

        GLES30.glGenTextures(1,t,0);
        shadowTexture=t[0];
        if(shadowTexture==0) { quality.shadows=false; return; }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,shadowTexture);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_DEPTH_COMPONENT16,size,size,0,
                GLES30.GL_DEPTH_COMPONENT,GLES30.GL_UNSIGNED_SHORT,null);
        int texError=GLES30.glGetError();
        if(texError!=GLES30.GL_NO_ERROR){
            GLES30.glDeleteTextures(1,t,0);
            shadowTexture=0;
            quality.shadows=false;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);
            return;
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);

        GLES30.glGenFramebuffers(1,f,0);
        shadowFbo=f[0];
        if(shadowFbo==0){
            GLES30.glDeleteTextures(1,t,0);
            shadowTexture=0;
            quality.shadows=false;
            return;
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,shadowFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_TEXTURE_2D,shadowTexture,0);
        GLES30.glDrawBuffers(0,new int[0],0);
        GLES30.glReadBuffer(GLES30.GL_NONE);
        int status=GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if(status!=GLES30.GL_FRAMEBUFFER_COMPLETE){
            GLES30.glDeleteFramebuffers(1,f,0);
            GLES30.glDeleteTextures(1,t,0);
            shadowFbo=0;
            shadowTexture=0;
            quality.shadows=false;
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
    }

    private void buildSceneModels(){
        setIdentity(groundModel); scale(groundModel,18,0.1f,18); translate(groundModel,0,-0.1f,0);
        cubeCount=0;
        // Deterministic procedural editor terrain: no imported asset is required
        // to make the 3D viewport useful. Multiple low-cost waves create varied
        // elevations while keeping results identical between editor launches.
        final int grid=3;
        final float spacing=2.15f;
        for(int z=-grid;z<=grid;z++){
            for(int x=-grid;x<=grid;x++){
                float nx=(x+grid)*0.73f, nz=(z+grid)*0.91f;
                float h=1.05f
                        +0.55f*(float)Math.sin(nx*0.93f+nz*0.37f)
                        +0.35f*(float)Math.cos(nx*0.31f-nz*0.77f)
                        +0.22f*(float)Math.sin((nx+nz)*1.71f);
                h=Math.max(0.6f,Math.min(4.2f,h+2.0f));
                int i=cubeCount++;
                model(cubeModels,i,x*spacing,h*0.5f,z*spacing,0,0,0,1.0f,h*0.5f,1.0f);
                cubeRoughness[i]=0.72f; cubeMetallic[i]=0.02f;
                int c=i*3, moss=Math.max(0,Math.min(100,(int)((h-1.2f)*33f)));
                float m=moss/100f;
                cubeColor[c]=0.24f+0.12f*m; cubeColor[c+1]=0.28f+0.18f*m; cubeColor[c+2]=0.23f+0.10f*m;
            }
        }
        addProceduralCube(0,3.0f,0,2.8f,3.0f,2.8f,0.24f,0.33f,0.42f,0.24f,0.26f);
        addProceduralCube(-7.5f,2.0f,-7.5f,1.6f,2.0f,1.6f,0.55f,0.42f,0.28f,0.45f,0.08f);
        addProceduralCube(7.5f,2.4f,-7.5f,1.9f,2.4f,1.9f,0.34f,0.40f,0.48f,0.42f,0.12f);
        addProceduralCube(-7.5f,1.8f,7.5f,1.5f,1.8f,1.5f,0.38f,0.29f,0.22f,0.62f,0.04f);
        addProceduralCube(7.5f,2.1f,7.5f,1.8f,2.1f,1.8f,0.27f,0.32f,0.36f,0.50f,0.18f);
    }

    private void addProceduralCube(float x,float y,float z,float sx,float sy,float sz,
                                   float r,float g,float b,float rough,float metal){
        if(cubeCount>=64)return;
        int i=cubeCount++;
        model(cubeModels,i,x,y,z,0,0,0,sx,sy,sz);
        cubeRoughness[i]=rough; cubeMetallic[i]=metal;
        int c=i*3; cubeColor[c]=r; cubeColor[c+1]=g; cubeColor[c+2]=b;
    }

    private void model(float[] dst,int i,float x,float y,float z,float rx,float ry,float rz,float sx,float sy,float sz){
        float[] m=identity(); translate(m,x,y,z); rotateXYZ(m,rx,ry,rz); scale(m,sx,sy,sz); System.arraycopy(m,0,dst,i*16,16);
    }

    private void drawShadowModel(float[] m){
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,cubeVbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,8*4,0);
        GLES30.glUniformMatrix4fv(sModel,1,false,m,0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,36);
        GLES30.glDisableVertexAttribArray(0);
    }

    private void drawShadowModel(float[] all,int i){
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,cubeVbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,8*4,0);
        GLES30.glUniformMatrix4fv(sModel,1,false,all,i*16);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,36);
        GLES30.glDisableVertexAttribArray(0);
    }

    private void enableMainAttributes(){
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,8*4,0);
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1,3,GLES30.GL_FLOAT,false,8*4,3*4);
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2,2,GLES30.GL_FLOAT,false,8*4,6*4);
    }

    private void disableMainAttributes(){GLES30.glDisableVertexAttribArray(0);GLES30.glDisableVertexAttribArray(1);GLES30.glDisableVertexAttribArray(2);}

    private void drawModel(float[] m){
        GLES30.glUniformMatrix4fv(uModel,1,false,m,0);
        float[] n=normalMatrix(m);
        GLES30.glUniformMatrix3fv(uNormal,1,false,n,0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,36);
    }
    private void drawModel(float[] all,int i){
        GLES30.glUniformMatrix4fv(uModel,1,false,all,i*16);
        float[] n=new float[]{
                all[i*16],all[i*16+1],all[i*16+2],
                all[i*16+4],all[i*16+5],all[i*16+6],
                all[i*16+8],all[i*16+9],all[i*16+10]};
        GLES30.glUniformMatrix3fv(uNormal,1,false,n,0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,36);
    }

    private float[] viewProj(){
        float[] v;
        if(mode==Mode.RUNTIME){
            float yr=(float)Math.toRadians(runtimeCamera.yaw), pr=(float)Math.toRadians(runtimeCamera.pitch);
            float fx=(float)(Math.cos(pr)*Math.cos(yr)), fy=(float)Math.sin(pr), fz=(float)(Math.cos(pr)*Math.sin(yr));
            v=lookAt(runtimeCamera.x,runtimeCamera.y,runtimeCamera.z,runtimeCamera.x+fx,runtimeCamera.y+fy,runtimeCamera.z+fz,0,1,0);
        }else v=lookAt(editorCamera.x,editorCamera.y,editorCamera.z,editorCamera.targetX,editorCamera.targetY,editorCamera.targetZ,0,1,0);
        float[] p=perspective(64f,(float)width/Math.max(1,height),0.05f,160f);
        return multiply(p,v);
    }

    private float[] lightViewProj(){
        float[] v=lookAt(18,24,16,0,0,0,0,1,0);
        float[] p=ortho(-28,28,-28,28,1,70);
        return multiply(p,v);
    }

    private int link(String vs,String fs){
        int a=compile(GLES30.GL_VERTEX_SHADER,vs),b=compile(GLES30.GL_FRAGMENT_SHADER,fs);
        int p=GLES30.glCreateProgram();GLES30.glAttachShader(p,a);GLES30.glAttachShader(p,b);GLES30.glLinkProgram(p);
        int[] ok=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);
        if(ok[0]==0) throw new RuntimeException("Noir shader link failed: "+GLES30.glGetProgramInfoLog(p));
        GLES30.glDeleteShader(a);GLES30.glDeleteShader(b);return p;
    }
    private int compile(int type,String src){
        int s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,src);GLES30.glCompileShader(s);
        int[] ok=new int[1];GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0) throw new RuntimeException("Noir shader compile failed: "+GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private String mainVertex(){return "#version 300 es\nlayout(location=0)in vec3 aPos;layout(location=1)in vec3 aNormal;layout(location=2)in vec2 aUV;uniform mat4 uModel,uViewProj,uLightVP;uniform mat3 uNormal;out vec3 vPos,vNormal;out vec4 vLight;out vec2 vUV;void main(){vec4 w=uModel*vec4(aPos,1.0);vPos=w.xyz;vNormal=normalize(uNormal*aNormal);vLight=uLightVP*w;vUV=aUV;gl_Position=uViewProj*w;}";}
    private String mainFragment(){return "#version 300 es\\nprecision highp float;in vec3 vPos,vNormal;in vec4 vLight;in vec2 vUV;uniform vec3 uCamera,uSunDir,uSunColor,uSky,uBaseColor;uniform float uRough,uMetal,uExposure,uReflections,uFog,uTone,uContrast,uSaturation,uGodRays,uBloom,uPcfRadius;uniform sampler2D uShadow;out vec4 frag;float shadow(){vec3 p=vLight.xyz/max(vLight.w,0.0001);p=p*0.5+0.5;if(p.x<0.0||p.x>1.0||p.y<0.0||p.y>1.0||p.z>1.0)return 1.0;float bias=0.0015;float s=0.0;float texel=1.0/2048.0;for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++){if(float(abs(x))>uPcfRadius||float(abs(y))>uPcfRadius)continue;float d=texture(uShadow,p.xy+vec2(x,y)*texel).r;s+=p.z-bias<=d?1.0:0.0;}return s/(uPcfRadius>1.5?25.0:9.0);}vec3 fresnel(float c,vec3 f0){return f0+(1.0-f0)*pow(1.0-c,5.0);}vec3 aces(vec3 x){return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0);}void main(){vec3 N=normalize(vNormal),V=normalize(uCamera-vPos),L=normalize(-uSunDir),H=normalize(V+L);float NoL=max(dot(N,L),0.0),NoV=max(dot(N,V),0.0),NoH=max(dot(N,H),0.0),VoH=max(dot(V,H),0.0);float a=max(0.045,uRough*uRough);float a2=a*a;float d=(NoH*NoH*(a2-1.0)+1.0);float D=a2/(3.14159265*d*d);float k=(a+1.0);k=k*k/8.0;float Gv=NoV/(NoV*(1.0-k)+k);float Gl=NoL/(NoL*(1.0-k)+k);vec3 F0=mix(vec3(0.04),vec3(0.86),uMetal);vec3 F=fresnel(VoH,F0);vec3 spec=(D*Gv*Gl*F)/max(4.0*NoV*NoL,0.001);vec3 base=max(uBaseColor,vec3(0.001));vec3 kd=(1.0-F)*(1.0-uMetal);float sh=shadow();vec3 direct=(kd*base/3.14159265+spec)*uSunColor*NoL*sh;vec3 env=mix(uSky,vec3(0.80,0.88,0.98),pow(1.0-NoV,5.0));vec3 color=(direct+kd*base*0.24+env*spec*0.45*uReflections);float fogAmount=uFog*clamp(length(uCamera-vPos)/70.0,0.0,1.0);color=mix(color,uSky,fogAmount);color+=uSunColor*pow(max(dot(V,L),0.0),32.0)*uGodRays*0.015;float hot=clamp((dot(spec,vec3(0.2126,0.7152,0.0722))-0.35)*2.4,0.0,1.0);color+=uSunColor*hot*hot*uBloom*0.055;color*=uExposure;if(uTone>0.5)color=aces(max(color,vec3(0.0)));else color=vec3(1.0)-exp(-color);float lum=dot(color,vec3(0.2126,0.7152,0.0722));color=mix(vec3(lum),color,uSaturation);color=(color-0.5)*uContrast+0.5;color=pow(clamp(color,0.0,1.0),vec3(1.0/2.2));frag=vec4(color,1.0);}";}
    private String shadowVertex(){return "#version 300 es\nlayout(location=0)in vec3 aPos;uniform mat4 uModel,uLightVP;void main(){gl_Position=uLightVP*uModel*vec4(aPos,1.0);}";}
    private String shadowFragment(){return "#version 300 es\nprecision mediump float;void main(){}";}
    private String skyVertex(){return "#version 300 es\nout vec2 vSkyUV;void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));vSkyUV=p;gl_Position=vec4(p*2.0-1.0,0.999,1.0);}";}
    private String skyFragment(){return "#version 300 es\\nprecision highp float;in vec2 vSkyUV;uniform float uTime,uAspect,uSunRays,uGodRays,uClouds,uSkyBrightness,uCloudCoverage;uniform vec3 uForward,uRight,uUp,uSunDir,uHorizon,uZenith;out vec4 frag;float hash(vec3 p){return fract(sin(dot(p,vec3(127.1,311.7,74.7)))*43758.5453);}float noise(vec3 p){vec3 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);return mix(mix(mix(hash(i),hash(i+vec3(1,0,0)),f.x),mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y),f.z);}float fbm(vec3 p){float v=0.0,a=0.55;for(int i=0;i<4;i++){v+=noise(p)*a;p=p*2.03+7.1;a*=0.5;}return v;}void main(){vec2 ndc=vSkyUV*2.0-1.0;vec3 ray=normalize(uForward+uRight*ndc.x*uAspect+uUp*ndc.y);float h=clamp(ray.y*0.5+0.5,0.0,1.0);float horizonFade=smoothstep(-0.08,0.42,ray.y);vec3 c=mix(uHorizon,uZenith,pow(horizonFade,0.72));float warm=smoothstep(0.15,0.0,abs(ray.y))*0.10;c+=vec3(0.12,0.055,0.018)*(1.0-smoothstep(0.0,0.15,abs(ray.y)));vec3 q=ray*4.2+vec3(uTime*0.006,0.0,uTime*0.004);float n=fbm(q);float cloudBand=smoothstep(0.02,0.12,ray.y)*smoothstep(0.76,0.30,ray.y);float cloudShape=smoothstep(0.50,0.72,n)*cloudBand*uCloudCoverage*uClouds;c=mix(c,vec3(0.94,0.96,0.98),cloudShape*0.62);vec3 toSun=normalize(-uSunDir);float sunDot=max(dot(ray,toSun),0.0);float disc=pow(sunDot,900.0);float halo=pow(sunDot,34.0)*0.24+pow(sunDot,7.0)*0.035;float shaft=pow(sunDot,4.0)*smoothstep(-0.18,0.35,ray.y)*0.08;c+=vec3(1.0,0.76,0.46)*(disc*uSunRays+halo*uSunRays+shaft*uGodRays);c*=max(0.0,uSkyBrightness);c=vec3(1.0)-exp(-c);frag=vec4(pow(clamp(c,0.0,1.0),vec3(0.4545)),1.0);}";}
    public float[] projectWorldToScreen(float x,float y,float z){
        float[] vp=viewProj();
        float cx=vp[0]*x+vp[4]*y+vp[8]*z+vp[12];
        float cy=vp[1]*x+vp[5]*y+vp[9]*z+vp[13];
        float cw=vp[3]*x+vp[7]*y+vp[11]*z+vp[15];
        if(cw<=0.0001f)return null;
        float nx=cx/cw,ny=cy/cw;
        return new float[]{(nx*0.5f+0.5f)*width,(1f-(ny*0.5f+0.5f))*height,cw};
    }
    private float[] cross(float[] a,float[] b){return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    private float[] normalize(float[] v){float l=(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);if(l<0.00001f)return new float[]{0,1,0};return new float[]{v[0]/l,v[1]/l,v[2]/l};}
    private float[] identity(){float[]m=new float[16];m[0]=m[5]=m[10]=m[15]=1;return m;}
    private void setIdentity(float[]m){Arrays.fill(m,0);m[0]=m[5]=m[10]=m[15]=1;}
    private void translate(float[]m,float x,float y,float z){m[12]+=x;m[13]+=y;m[14]+=z;}
    private void scale(float[]m,float x,float y,float z){m[0]*=x;m[5]*=y;m[10]*=z;}
    private void rotateXYZ(float[]m,float x,float y,float z){if(x!=0)mulInPlace(m,rotationX(x));if(y!=0)mulInPlace(m,rotationY(y));if(z!=0)mulInPlace(m,rotationZ(z));}
    private float[] rotationX(float d){float c=(float)Math.cos(d),s=(float)Math.sin(d);float[]m=identity();m[5]=c;m[6]=s;m[9]=-s;m[10]=c;return m;}
    private float[] rotationY(float d){float c=(float)Math.cos(d),s=(float)Math.sin(d);float[]m=identity();m[0]=c;m[2]=-s;m[8]=s;m[10]=c;return m;}
    private float[] rotationZ(float d){float c=(float)Math.cos(d),s=(float)Math.sin(d);float[]m=identity();m[0]=c;m[1]=s;m[4]=-s;m[5]=c;return m;}
    private void mulInPlace(float[]a,float[]b){float[]r=multiply(a,b);System.arraycopy(r,0,a,0,16);}
    private float[] normalMatrix(float[]m){return new float[]{m[0],m[1],m[2],m[4],m[5],m[6],m[8],m[9],m[10]};}
    private float[] perspective(float f,float a,float n,float fa){float t=(float)(1.0/Math.tan(Math.toRadians(f)*0.5));float[]m=new float[16];m[0]=t/a;m[5]=t;m[10]=(fa+n)/(n-fa);m[11]=-1;m[14]=(2*fa*n)/(n-fa);return m;}
    private float[] ortho(float l,float r,float b,float t,float n,float f){float[]m=identity();m[0]=2/(r-l);m[5]=2/(t-b);m[10]=-2/(f-n);m[12]=-(r+l)/(r-l);m[13]=-(t+b)/(t-b);m[14]=-(f+n)/(f-n);return m;}
    private float[] lookAt(float ex,float ey,float ez,float cx,float cy,float cz,float ux,float uy,float uz){float zx=ex-cx,zy=ey-cy,zz=ez-cz;float zl=(float)Math.sqrt(zx*zx+zy*zy+zz*zz);zx/=zl;zy/=zl;zz/=zl;float xx=uy*zz-uz*zy,xy=uz*zx-ux*zz,xz=ux*zy-uy*zx;float xl=(float)Math.sqrt(xx*xx+xy*xy+xz*xz);xx/=xl;xy/=xl;xz/=xl;float yx=zy*xz-zz*xy,yy=zz*xx-zx*xz,yz=zx*xy-zy*xx;float[]m=identity();m[0]=xx;m[1]=yx;m[2]=zx;m[4]=xy;m[5]=yy;m[6]=zy;m[8]=xz;m[9]=yz;m[10]=zz;m[12]=-(xx*ex+xy*ey+xz*ez);m[13]=-(yx*ex+yy*ey+yz*ez);m[14]=-(zx*ex+zy*ey+zz*ez);return m;}
    private float[] multiply(float[]a,float[]b){float[]r=new float[16];for(int c=0;c<4;c++)for(int row=0;row<4;row++)r[c*4+row]=a[row]*b[c*4]+a[4+row]*b[c*4+1]+a[8+row]*b[c*4+2]+a[12+row]*b[c*4+3];return r;}
}