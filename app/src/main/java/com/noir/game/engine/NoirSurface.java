package com.noir.game.engine;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import com.noir.game.engine.render.NoirRenderer;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/** Native NoirGFX viewport with Unity-style orbit/pan/zoom touch controls. */
public final class NoirSurface extends GLSurfaceView implements NoirViewport {
    public interface EditorTapListener { void onEditorTap(float x,float y); }
    private EditorTapListener editorTapListener;
    private final NoirRenderer renderer;
    private float lastX,lastY,startX,startY;
    private boolean dragging;
    private boolean runtimeMoveTouch;
    private float pinchDistance;
    private boolean nativeGraphics;

    public void setEditorTapListener(EditorTapListener listener){editorTapListener=listener;}

    public NoirSurface(Context c,NoirRenderer r){
        super(c);
        renderer=r;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(new MultisampleChooser());
        setRenderer(new NativeRendererAdapter());
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setPreserveEGLContextOnPause(true);
    }

    @Override public void setRuntimeMode(boolean runtime){
        queueEvent(() -> renderer.setMode(runtime ? NoirRenderer.Mode.RUNTIME : NoirRenderer.Mode.EDITOR));
    }

    private final class NativeRendererAdapter implements GLSurfaceView.Renderer {
        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,EGLConfig config){
            nativeGraphics=NoirNative.isLoaded() && NoirNative.graphicsInitialize();
            if(!nativeGraphics){
                renderer.onSurfaceCreated(gl,config);
            }
        }

        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,int width,int height){
            if(nativeGraphics) NoirNative.graphicsResize(width,height);
            else renderer.onSurfaceChanged(gl,width,height);
        }

        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){
            if(nativeGraphics){
                NoirRenderer.Camera c=renderer.camera();
                NoirNative.graphicsFrame(c.yaw,c.pitch,c.distance,c.targetX,c.targetY,c.targetZ,
                        renderer.mode()==NoirRenderer.Mode.EDITOR);
                renderer.setFrameTimeMs(NoirNative.graphicsFrameTimeMs());
            }else{
                renderer.onDrawFrame(gl);
            }
        }
    }

    private static final class MultisampleChooser implements EGLConfigChooser {
        @Override public EGLConfig chooseConfig(EGL10 egl,EGLDisplay display){
            EGLConfig[] configs=new EGLConfig[32];
            int[] count=new int[1];
            int[][] candidates={
                {EGL10.EGL_RED_SIZE,8,EGL10.EGL_GREEN_SIZE,8,EGL10.EGL_BLUE_SIZE,8,EGL10.EGL_ALPHA_SIZE,8,
                 EGL10.EGL_DEPTH_SIZE,24,EGL10.EGL_STENCIL_SIZE,8,EGL10.EGL_SAMPLE_BUFFERS,1,EGL10.EGL_SAMPLES,4,EGL10.EGL_NONE},
                {EGL10.EGL_RED_SIZE,8,EGL10.EGL_GREEN_SIZE,8,EGL10.EGL_BLUE_SIZE,8,EGL10.EGL_ALPHA_SIZE,8,
                 EGL10.EGL_DEPTH_SIZE,24,EGL10.EGL_STENCIL_SIZE,8,EGL10.EGL_NONE},
                {EGL10.EGL_RED_SIZE,8,EGL10.EGL_GREEN_SIZE,8,EGL10.EGL_BLUE_SIZE,8,EGL10.EGL_ALPHA_SIZE,8,
                 EGL10.EGL_DEPTH_SIZE,16,EGL10.EGL_NONE}
            };
            for(int[] attrs:candidates){
                count[0]=0;
                if(egl.eglChooseConfig(display,attrs,configs,configs.length,count)&&count[0]>0)return configs[0];
            }
            throw new IllegalArgumentException("No compatible OpenGL ES 3 framebuffer configuration");
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        int count=e.getPointerCount();
        if(count>=2){
            float dx=e.getX(0)-e.getX(1),dy=e.getY(0)-e.getY(1);
            float d=(float)Math.sqrt(dx*dx+dy*dy);
            if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN)pinchDistance=d;
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE && pinchDistance>1){
                renderer.zoom((pinchDistance-d)*0.015f);
                renderer.pan((e.getX(0)+e.getX(1))*0.5f-lastX,
                        (e.getY(0)+e.getY(1))*0.5f-lastY);
                pinchDistance=d;
            }
            return true;
        }
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                lastX=e.getX();lastY=e.getY();startX=e.getX();startY=e.getY();
                runtimeMoveTouch=renderer.mode()==NoirRenderer.Mode.RUNTIME && e.getX()<getWidth()*0.42f;
                dragging=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(dragging){
                    float dx=e.getX()-lastX,dy=e.getY()-lastY;
                    if(renderer.mode()==NoirRenderer.Mode.RUNTIME){
                        if(runtimeMoveTouch){
                            float sx=Math.max(-1f,Math.min(1f,(e.getX()-startX)/220f));
                            float sy=Math.max(-1f,Math.min(1f,(startY-e.getY())/220f));
                            renderer.runtimeMove(sy,sx,0.016f);
                        }else renderer.runtimeLook(dx,dy);
                    }else renderer.orbit(dx,dy);
                    lastX=e.getX();lastY=e.getY();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if(renderer.mode()==NoirRenderer.Mode.EDITOR && dragging &&
                        Math.abs(e.getX()-startX)<12f && Math.abs(e.getY()-startY)<12f &&
                        editorTapListener!=null)editorTapListener.onEditorTap(e.getX(),e.getY());
                dragging=false;runtimeMoveTouch=false;pinchDistance=0;return true;
            case MotionEvent.ACTION_CANCEL:
                dragging=false;runtimeMoveTouch=false;pinchDistance=0;return true;
            default:return true;
        }
    }

    @Override protected void onDetachedFromWindow(){
        if(nativeGraphics){
            queueEvent(NoirNative::graphicsShutdown);
            nativeGraphics=false;
        }
        super.onDetachedFromWindow();
    }
}
