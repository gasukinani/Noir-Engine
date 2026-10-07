package com.noir.game.engine;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.noir.game.engine.render.NoirRenderer;

/** Native Vulkan presentation surface. Backend is selected before Activity creation. */
public final class NoirVulkanSurface extends SurfaceView implements SurfaceHolder.Callback, NoirViewport {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NoirRenderer renderer;
    private boolean attached;
    private boolean running;
    private int uploadedSceneVersion=-1;
    private int consecutiveFrameFailures;
    private int consecutiveSuccessfulFrames;
    private float lastX,lastY,startX,startY;
    private boolean dragging,runtimeMoveTouch;
    private float pinchDistance,lastCenterX,lastCenterY;
    private final Runnable frameLoop = new Runnable() {
        @Override public void run() {
            if (!running || !attached) return;
            try {
                float[] sun=renderer.environmentSunDirection();
                if(uploadedSceneVersion!=renderer.sceneSnapshotVersion()){
                    NoirNative.vulkanSetScene(renderer.sceneSnapshot());
                    uploadedSceneVersion=renderer.sceneSnapshotVersion();
                }
                if(renderer.mode()==NoirRenderer.Mode.RUNTIME){
                    float[] rc=renderer.runtimeCameraState();
                    NoirNative.vulkanSetRuntimeCamera(rc[0],rc[1],rc[2],rc[3],rc[4]);
                }else{
                    NoirNative.vulkanSetCamera(renderer.camera().yaw,renderer.camera().pitch,renderer.camera().distance,
                            renderer.camera().targetX,renderer.camera().targetY,renderer.camera().targetZ);
                }
                NoirNative.vulkanSetEnvironment(renderer.environmentSkyMode(),renderer.environmentExposure(),
                        renderer.environmentSkyBrightness(),renderer.environmentFogDensity(),
                        sun[0],sun[1],sun[2]);
                NoirNative.vulkanSetQuality(renderer.nativeQualityTier());
                boolean ok=NoirNative.vulkanDrawFrame();
                if(!ok && ++consecutiveFrameFailures>=3){
                    running=false;attached=false;
                    try { NoirNative.vulkanDetachSurface(); } catch(Throwable ignored) {}
                    NoirGraphicsBackend.save(getContext(),NoirGraphicsBackend.Type.GLES);
                    android.app.Activity a=(android.app.Activity)getContext();
                    a.runOnUiThread(a::recreate);
                    return;
                }
                if(ok){consecutiveFrameFailures=0;
                    if(++consecutiveSuccessfulFrames>=120)NoirGraphicsBackend.confirmVulkan(getContext());
                }
            } catch (Throwable ignored) {
                if(++consecutiveFrameFailures>=3){
                    running=false;attached=false;
                    try { NoirNative.vulkanDetachSurface(); } catch(Throwable ignored2) {}
                    NoirGraphicsBackend.save(getContext(),NoirGraphicsBackend.Type.GLES);
                    ((android.app.Activity)getContext()).runOnUiThread(() -> ((android.app.Activity)getContext()).recreate());
                    return;
                }
            }
            handler.postDelayed(this, 16L);
        }
    };

    public NoirVulkanSurface(Context context, NoirRenderer renderer) {
        super(context);
        this.renderer=renderer;
        setBackgroundColor(Color.rgb(246,243,236));
        getHolder().addCallback(this);
        setFocusable(true);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        try {
            boolean ok = NoirNative.vulkanAttachSurface(holder.getSurface(), getContext().getAssets());
            attached = ok;
            running = ok;
            if (ok) { uploadedSceneVersion=-1; consecutiveFrameFailures=0; consecutiveSuccessfulFrames=0; handler.post(frameLoop); }
            else Toast.makeText(getContext(),"Vulkan surface failed; restart with GLES.",Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            attached=false; running=false;
            Toast.makeText(getContext(),"Vulkan initialization failed safely.",Toast.LENGTH_LONG).show();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { if(attached) NoirNative.vulkanResize(width,height); }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        running=false; attached=false; handler.removeCallbacks(frameLoop);
        try { NoirNative.vulkanDetachSurface(); } catch (Throwable ignored) {}
    }

    @Override public void setRuntimeMode(boolean runtime) {
        renderer.setMode(runtime ? NoirRenderer.Mode.RUNTIME : NoirRenderer.Mode.EDITOR);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int count=e.getPointerCount();
        if(count>=2){
            float dx=e.getX(0)-e.getX(1),dy=e.getY(0)-e.getY(1);
            float d=(float)Math.hypot(dx,dy);
            float cx=(e.getX(0)+e.getX(1))*0.5f,cy=(e.getY(0)+e.getY(1))*0.5f;
            if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){pinchDistance=d;lastCenterX=cx;lastCenterY=cy;}
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&pinchDistance>1){
                if(renderer.mode()==NoirRenderer.Mode.EDITOR){
                    renderer.zoom((pinchDistance-d)*0.015f);
                    renderer.pan(cx-lastCenterX,cy-lastCenterY);
                }
                pinchDistance=d;lastCenterX=cx;lastCenterY=cy;
            }
            return true;
        }
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                lastX=e.getX();lastY=e.getY();startX=lastX;startY=lastY;
                runtimeMoveTouch=renderer.mode()==NoirRenderer.Mode.RUNTIME&&e.getX()<getWidth()*0.42f;
                dragging=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(!dragging)return true;
                float dx=e.getX()-lastX,dy=e.getY()-lastY;
                if(renderer.mode()==NoirRenderer.Mode.RUNTIME){
                    if(runtimeMoveTouch){
                        float sx=Math.max(-1f,Math.min(1f,(e.getX()-startX)/220f));
                        float sy=Math.max(-1f,Math.min(1f,(startY-e.getY())/220f));
                        renderer.runtimeMove(sy,sx,0.016f);
                    }else renderer.runtimeLook(dx,dy);
                }else{
                    renderer.orbit(dx,dy);
                }
                lastX=e.getX();lastY=e.getY();return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging=false;runtimeMoveTouch=false;pinchDistance=0;return true;
            default:return true;
        }
    }
}
