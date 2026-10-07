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
    private final Runnable frameLoop = new Runnable() {
        @Override public void run() {
            if (!running || !attached) return;
            try {
                float[] sun=renderer.environmentSunDirection();
                if(uploadedSceneVersion!=renderer.sceneSnapshotVersion()){
                    NoirNative.vulkanSetScene(renderer.sceneSnapshot());
                    uploadedSceneVersion=renderer.sceneSnapshotVersion();
                }
                NoirNative.vulkanSetCamera(renderer.camera().yaw,renderer.camera().pitch,renderer.camera().distance,
                        renderer.camera().targetX,renderer.camera().targetY,renderer.camera().targetZ);
                NoirNative.vulkanSetEnvironment(renderer.environmentSkyMode(),renderer.environmentExposure(),
                        renderer.environmentSkyBrightness(),renderer.environmentFogDensity(),
                        sun[0],sun[1],sun[2]);
                NoirNative.vulkanSetQuality(renderer.nativeQualityTier());
                NoirNative.vulkanDrawFrame();
            } catch (Throwable ignored) {}
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
            if (ok) { uploadedSceneVersion=-1; handler.post(frameLoop); }
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

    @Override public void setRuntimeMode(boolean runtime) {}

    @Override public boolean onTouchEvent(MotionEvent event) { return true; }
}
