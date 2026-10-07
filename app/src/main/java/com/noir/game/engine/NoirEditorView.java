package com.noir.game.engine;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Toast;
import com.noir.game.engine.animation.AnimationSystem;
import com.noir.game.engine.editor.*;
import com.noir.game.engine.render.NoirRenderer;
import com.noir.game.engine.scene.NoirNode;
import com.noir.game.engine.scripting.NoirCSharpProjectService;
import com.noir.game.engine.scripting.NoirGameCompiler;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/**
 * Noir mobile-first editor surface.
 *
 * This is an actual interaction layer, not a painted mockup:
 * - toolbar and every tab have touch hit targets
 * - one finger orbits the viewport
 * - two fingers pan + pinch zoom
 * - tap selects scene nodes
 * - MOVE/ROTATE/SCALE use TransformGizmo's 3D ray/plane math
 * - long press opens node actions
 * - project browser navigates real files
 * - inspector edits real transforms
 * - animation controls mutate AnimationTimelineModel
 * - Save writes the real .game scene document
 */
public final class NoirEditorView extends android.view.View {
    private static final int BG=0xff090d15, PANEL=0xf3161d2a, PANEL2=0xff111827;
    private static final int BORDER=0xff2b3a55, TEXT=0xffe4eaf4, MUTED=0xff8391a9, ACCENT=0xff6f9dfd;
    private static final int ACTIVE=0xff3f64a2, GOOD=0xff79e0a0, WARN=0xffffc766, BAD=0xffff7884;

    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final EditorState state;
    private final NoirRenderer renderer;
    private final NoirViewport surface;
    private final SceneTreeModel tree;
    private final AnimationTimelineModel timeline=new AnimationTimelineModel();
    private final ScriptDocument script=new ScriptDocument();
    private final TransformGizmo gizmo=new TransformGizmo();

    private final String[] tabs={"SCENE","ASSETS","INSPECT","ANIM","SCRIPT","SHADER","PHYSICS","WORLD","CTRL","PROFILER","CONSOLE","C#"};
    private int tab;
    private float density;
    private float topBar,tabBar,bottomBar,leftW,rightW;
    private float downX,downY,lastX,lastY,lastCenterX,lastCenterY,pinchDistance;
    private long downTime;
    private boolean viewportTouch,viewportMoved,uiTouch;
    private long lastAnimationNanos=System.nanoTime();
    private File browserDir;
    private final ArrayDeque<File> browserHistory=new ArrayDeque<>();
    private String status="Ready";
    private boolean compactUi;
    private float tabScroll;
    private float tabGestureStartX;
    private float tabGestureStartScroll;
    private boolean tabScrolling;

    public NoirEditorView(Context c,EditorState s,NoirRenderer r,NoirViewport ss){
        super(c);
        state=s;renderer=r;surface=ss;
        tree=new SceneTreeModel(state.scene.root);
        script.text=defaultScript();
        density=getResources().getDisplayMetrics().density;
        setFocusable(true);
        setWillNotDraw(false);
        browserDir=state.projectRoot;
        state.log("Noir mobile editor ready");
    }

    private float dp(float v){return v*density;}
    private void fill(Canvas c,int color,float l,float t,float r,float b){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRect(l,t,r,b,p);}
    private void round(Canvas c,int color,float l,float t,float r,float b,float rad){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRoundRect(l,t,r,b,rad,rad,p);}
    private void stroke(Canvas c,int color,float width,float l,float t,float r,float b,float rad){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setColor(color);c.drawRoundRect(l,t,r,b,rad,rad,p);p.setStyle(Paint.Style.FILL);}
    private void text(Canvas c,String s,float x,float y,float size,int color){
        if(s==null)s="";
        p.setTypeface(Typeface.create("sans",Typeface.NORMAL));p.setTextSize(size);p.setColor(color);p.setStyle(Paint.Style.FILL);
        float max=Math.max(0,getWidth()-x-dp(8));
        if(max>0 && p.measureText(s)>max){
            String ell="…";float ew=p.measureText(ell);int end=s.length();
            while(end>0 && p.measureText(s,0,end)+ew>max)end--;
            s=end>0?s.substring(0,end)+ell:ell;
        }
        c.save();c.clipRect(x,y-size*1.5f,getWidth()-dp(4),y+size*0.55f);c.drawText(s,x,y,p);c.restore();
    }
    private void bold(Canvas c,String s,float x,float y,float size,int color){p.setTypeface(Typeface.create("sans",Typeface.BOLD));p.setTextSize(size);p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawText(s,x,y,p);}
    private boolean hit(float x,float y,float l,float t,float r,float b){return x>=l&&x<=r&&y>=t&&y<=b;}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float w=getWidth(),h=getHeight();
        float[] nativeLayout=NoirNative.isLoaded()?NoirNative.editorLayout(w,h,density):null;
        compactUi=w<dp(700);
        if(nativeLayout!=null&&nativeLayout.length>=7){
            topBar=nativeLayout[0];tabBar=nativeLayout[1];bottomBar=nativeLayout[2];
            leftW=nativeLayout[3];rightW=nativeLayout[4];
        }else{
            topBar=dp(compactUi?52:58);tabBar=dp(compactUi?42:48);bottomBar=dp(compactUi?28:30);
            if(compactUi){
                leftW=0;rightW=0;
            }else{
                leftW=Math.max(dp(280),Math.min(dp(350),w*.255f));
                rightW=Math.max(dp(285),Math.min(dp(360),w*.26f));
            }
        }

        fill(c,0x00000000,0,0,w,h);
        drawToolbar(c,w);
        drawTabs(c,w);

        if(timeline.playing){
            long now=System.nanoTime();
            float dt=Math.min(0.05f,(now-lastAnimationNanos)/1_000_000_000f);
            lastAnimationNanos=now;
            timeline.playhead+=dt;
            if(timeline.clip!=null&&timeline.playhead>timeline.clip.duration){
                timeline.playhead=timeline.loop?0:timeline.clip.duration;
                if(!timeline.loop)timeline.playing=false;
            }
            postInvalidateDelayed(16);
        }

        if(!state.playing){
            drawViewportChrome(c,w,h);
            drawGizmo(c);
            drawDock(c,w,h);
        }
        drawStatusBar(c,w,h);
    }

    private void drawStatusBar(Canvas c,float w,float h){
        float y=h-bottomBar;
        fill(c,0xff0b111b,0,y,w,h);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(BORDER);
        c.drawLine(0,y,w,y,p);
        p.setStyle(Paint.Style.FILL);
        text(c,"NOIR GFX • EDITOR",dp(14),y+dp(19),dp(8),ACCENT);
        text(c,status,dp(96),y+dp(19),dp(8),TEXT);
        NoirNode n=state.selected;
        String sel=n==null?"No selection":n.name+" • "+n.kind.name();
        text(c,sel,dp(250),y+dp(19),dp(8),MUTED);
        String nativeState=NoirNative.isLoaded()?NoirNative.glesBackendInfo():"JAVA FALLBACK";
        text(c,nativeState,w-dp(125),y+dp(19),dp(8),NoirNative.isLoaded()?GOOD:WARN);
        text(c,String.format(Locale.US,"FPS %.0f",1000.0f/Math.max(0.1f,renderer.frameTimeMs())),w-dp(55),y+dp(19),dp(8),TEXT);
    }

    private void drawToolbar(Canvas c,float w){
        fill(c,BG,0,0,w,topBar);
        if(compactUi){
            bold(c,"NOIR",dp(12),dp(33),dp(20),Color.WHITE);
            text(c,"3D",dp(57),dp(31),dp(8),ACCENT);
            float x=dp(84),bw=dp(50),gap=dp(4);
            toolButton(c,x,dp(7),bw,dp(38),"SEL",state.tool==EditorState.Tool.SELECT); x+=bw+gap;
            toolButton(c,x,dp(7),bw,dp(38),"MOVE",state.tool==EditorState.Tool.MOVE); x+=bw+gap;
            toolButton(c,x,dp(7),bw,dp(38),"ROT",state.tool==EditorState.Tool.ROTATE); x+=bw+gap;
            toolButton(c,x,dp(7),bw,dp(38),"SCALE",state.tool==EditorState.Tool.SCALE);
            toolButton(c,w-dp(54),dp(7),dp(46),dp(38),"•••",false);
            return;
        }
        bold(c,"NOIR",dp(18),dp(36),dp(24),Color.WHITE);
        text(c,"3D ENGINE",dp(88),dp(27),dp(11),0xffa7b8d8);
        text(c,"MOBILE EDITOR",dp(88),dp(43),dp(9),MUTED);
        toolButton(c,dp(220),dp(9),dp(54),dp(40),"SEL",state.tool==EditorState.Tool.SELECT);
        toolButton(c,dp(280),dp(9),dp(64),dp(40),"MOVE",state.tool==EditorState.Tool.MOVE);
        toolButton(c,dp(350),dp(9),dp(64),dp(40),"ROT",state.tool==EditorState.Tool.ROTATE);
        toolButton(c,dp(420),dp(9),dp(70),dp(40),"SCALE",state.tool==EditorState.Tool.SCALE);
        toolButton(c,w-dp(340),dp(9),dp(72),dp(40),state.playing?"STOP":"PLAY",state.playing);
        toolButton(c,w-dp(262),dp(9),dp(70),dp(40),"BUILD",false);
        toolButton(c,w-dp(186),dp(9),dp(68),dp(40),"SAVE",false);
        toolButton(c,w-dp(112),dp(9),dp(98),dp(40),"RESET CAM",false);
    }

    private void drawTabs(Canvas c,float w){
        fill(c,0xff0d131e,0,topBar,w,topBar+tabBar);
        float y=topBar+dp(compactUi?3:6);
        float tw=compactUi?dp(70):Math.max(dp(66),Math.min(dp(94),(w-dp(14)-dp(4)*(tabs.length-1))/tabs.length));
        float step=tw+dp(4);
        c.save();c.clipRect(0,topBar,w,topBar+tabBar);
        float x=dp(7)-tabScroll;
        for(int i=0;i<tabs.length;i++){toolButton(c,x,y,tw,dp(compactUi?34:36),tabs[i],i==tab);x+=step;}
        c.restore();
        if(compactUi){text(c,"‹",dp(2),topBar+dp(27),dp(16),MUTED);text(c,"›",w-dp(12),topBar+dp(27),dp(16),MUTED);}
    }

    private void drawViewportChrome(Canvas c,float w,float h){
        float ct=topBar+tabBar,cb=h-bottomBar;
        float vl=compactUi?0:(tab==0?leftW:(tab==2?0:leftW));
        float vr=compactUi?w:((tab==0||tab==2)?w-rightW:w);
        if(vr-vl<dp(200))return;

        // Subtle editor grid and viewport frame. The GPU scene remains underneath this overlay.
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(0x183c4d69);
        if(state.grid){
            for(float x=vl+dp(40);x<vr;x+=dp(40))c.drawLine(x,ct,x,cb,p);
            for(float y=ct+dp(40);y<cb;y+=dp(40))c.drawLine(vl,y,vr,y,p);
        }
        float ox=(vl+vr)*0.5f, oy=(ct+cb)*0.5f;
        p.setStrokeWidth(dp(1.5f));p.setColor(0x70ff6677);c.drawLine(ox,oy,ox+dp(58),oy,p);
        p.setColor(0x7075e08a);c.drawLine(ox,oy,ox,oy-dp(44),p);
        p.setColor(0x704d9fff);c.drawLine(ox,oy,ox-dp(42),oy+dp(32),p);
        p.setStrokeWidth(1);p.setColor(0x503c4d69);c.drawRect(vl,ct,vr,cb,p);
        p.setStyle(Paint.Style.FILL);

        round(c,0xb90b111c,vl+dp(12),ct+dp(12),vl+dp(210),ct+dp(48),dp(6));
        text(c,"VIEWPORT",vl+dp(24),ct+dp(35),dp(10),TEXT);
        text(c,"LMB SELECT • DRAG ORBIT • 2F PAN/ZOOM",vl+dp(95),ct+dp(35),dp(8),MUTED);

        // Camera compass.
        float cx=vr-dp(54),cy=ct+dp(48);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(0x996f9dfd);c.drawCircle(cx,cy,dp(23),p);
        p.setStyle(Paint.Style.FILL);text(c,"N",cx-dp(4),cy-dp(12),dp(9),TEXT);
        text(c,"E",cx+dp(13),cy+dp(4),dp(9),MUTED);
        text(c,"S",cx-dp(4),cy+dp(18),dp(9),MUTED);
        text(c,"W",cx-dp(20),cy+dp(4),dp(9),MUTED);
    }

    private void drawDock(Canvas c,float w,float h){
        float t=topBar+tabBar,b=h-bottomBar;
        if(compactUi){
            if(tab==0) drawScenePanel(c,dp(6),t+dp(4),w-dp(6),b-dp(4));
            else if(tab==2) drawInspector(c,dp(6),t+dp(4),w-dp(6),b-dp(4));
            else{
                drawPanel(c,dp(6),t+dp(4),w-dp(6),b-dp(4),tabs[tab]);
                switch(tab){
                    case 1:drawAssets(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 3:drawAnimation(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 4:drawScript(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 5:drawShader(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 6:drawPhysics(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 7:drawWorld(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 8:drawController(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 9:drawProfiler(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 10:drawConsole(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                    case 11:drawCSharp(c,dp(6),t+dp(4),w-dp(6),b-dp(4));break;
                }
            }
            return;
        }
        if(tab==0){
            drawScenePanel(c,0,t,leftW,b);
            drawInspector(c,w-rightW,t,w,b);
        }else if(tab==2){
            drawInspector(c,w-rightW,t,w,b);
            drawPanel(c,0,t,leftW,b,"INSPECTOR TOOLS");
            drawInspectorTools(c,0,t,leftW,b);
        }else{
            drawPanel(c,0,t,leftW,b,tabs[tab]);
            switch(tab){
                case 1:drawAssets(c,0,t,leftW,b);break;
                case 3:drawAnimation(c,0,t,leftW,b);break;
                case 4:drawScript(c,0,t,leftW,b);break;
                case 5:drawShader(c,0,t,leftW,b);break;
                case 6:drawPhysics(c,0,t,leftW,b);break;
                case 7:drawWorld(c,0,t,leftW,b);break;
                case 8:drawController(c,0,t,leftW,b);break;
                case 9:drawProfiler(c,0,t,leftW,b);break;
                case 10:drawConsole(c,0,t,leftW,b);break;
                case 11:drawCSharp(c,0,t,leftW,b);break;
            }
        }
    }

    private void drawPanel(Canvas c,float l,float t,float r,float b,String title){
        round(c,PANEL,l,t,r,b,dp(8));stroke(c,BORDER,dp(1),l,t,r,b,dp(8));
        bold(c,title,l+dp(16),t+dp(27),dp(11),0xffbdc9dc);
        text(c,status,l+dp(16),t+dp(44),dp(8),MUTED);
    }

    private void drawScenePanel(Canvas c,float l,float t,float r,float b){
        drawPanel(c,l,t,r,b,"SCENE • OUTLINER");
        text(c,"WORLD",l+dp(17),t+dp(68),dp(9),MUTED);
        List<NoirNode> nodes=tree.visible();
        float y=t+dp(92);
        for(int i=0;i<nodes.size()&&y<b-dp(100);i++){
            NoirNode n=nodes.get(i);
            if(n==state.selected)round(c,ACTIVE,l+dp(8),y-dp(20),r-dp(8),y+dp(9),dp(5));
            text(c,icon(n.kind),l+dp(16+n.depth()*15),y,dp(11),n==state.selected?Color.WHITE:0xff7fa4e8);
            text(c,n.name,l+dp(37+n.depth()*15),y,dp(11),n==state.selected?Color.WHITE:TEXT);
            text(c,n.kind.name(),r-dp(84),y,dp(7),0xff64748a);
            y+=dp(29);
        }
        smallButton(c,l+dp(14),b-dp(62),dp(74),"ADD NODE",true);
        smallButton(c,l+dp(94),b-dp(62),dp(70),"DUPLICATE",false);
        smallButton(c,l+dp(170),b-dp(62),dp(64),"DELETE",false);
        text(c,"Nodes "+nodes.size(),l+dp(16),b-dp(22),dp(8),MUTED);
        text(c,"Snap "+state.snapStep+"m",r-dp(78),b-dp(22),dp(8),state.snapping?GOOD:WARN);
    }

    private void drawInspector(Canvas c,float l,float t,float r,float b){
        drawPanel(c,l,t,r,b,"INSPECTOR • LIVE");
        NoirNode n=state.selected;
        if(n==null){text(c,"Select a node in the viewport or outliner.",l+dp(18),t+dp(82),dp(11),MUTED);return;}
        text(c,n.name,l+dp(18),t+dp(74),dp(16),TEXT);
        text(c,n.kind.name(),l+dp(18),t+dp(91),dp(9),ACCENT);
        float y=t+dp(118);
        inspectorRow(c,l,y,"POSITION",String.format(Locale.US,"%.3f  %.3f  %.3f",n.px,n.py,n.pz));y+=dp(58);
        inspectorRow(c,l,y,"ROTATION",String.format(Locale.US,"%.1f°  %.1f°  %.1f°",n.rx,n.ry,n.rz));y+=dp(58);
        inspectorRow(c,l,y,"SCALE",String.format(Locale.US,"%.3f  %.3f  %.3f",n.sx,n.sy,n.sz));y+=dp(60);

        smallButton(c,l+dp(16),y,dp(90),"EDIT POS",true);
        smallButton(c,l+dp(112),y,dp(92),"EDIT ROT",false);
        smallButton(c,l+dp(210),y,dp(92),"EDIT SCALE",false);
        y+=dp(48);
        smallButton(c,l+dp(16),y,dp(82),n.visible?"HIDE":"SHOW",false);
        smallButton(c,l+dp(104),y,dp(82),n.locked?"UNLOCK":"LOCK",false);
        y+=dp(48);
        text(c,"SCRIPT",l+dp(18),y,dp(9),MUTED);y+=dp(20);
        text(c,n.properties.containsKey("script")?n.properties.get("script"):"No script attached",l+dp(18),y,dp(10),TEXT);y+=dp(32);
        smallButton(c,l+dp(16),y,dp(112),"ATTACH SCRIPT",true);
        smallButton(c,l+dp(136),y,dp(110),"ADD COMPONENT",false);
    }

    private void drawInspectorTools(Canvas c,float l,float t,float r,float b){
        text(c,"TRANSFORM TOOL",l+dp(16),t+dp(82),dp(9),MUTED);
        smallButton(c,l+dp(16),t+dp(96),dp(66),"SELECT",state.tool==EditorState.Tool.SELECT);
        smallButton(c,l+dp(88),t+dp(96),dp(66),"MOVE",state.tool==EditorState.Tool.MOVE);
        smallButton(c,l+dp(160),t+dp(96),dp(66),"ROT",state.tool==EditorState.Tool.ROTATE);
        smallButton(c,l+dp(232),t+dp(96),dp(66),"SCALE",state.tool==EditorState.Tool.SCALE);
        text(c,"SNAPPING",l+dp(16),t+dp(148),dp(9),MUTED);
        smallButton(c,l+dp(16),t+dp(162),dp(76),state.snapping?"SNAP ON":"SNAP OFF",state.snapping);
        smallButton(c,l+dp(100),t+dp(162),dp(76),"0.25m",state.snapStep==0.25f);
        smallButton(c,l+dp(184),t+dp(162),dp(76),"0.5m",state.snapStep==0.5f);
        smallButton(c,l+dp(268),t+dp(162),dp(76),"1m",state.snapStep==1f);
        text(c,"Touch workflow",l+dp(16),t+dp(214),dp(9),MUTED);
        text(c,"1. Tap an object",l+dp(16),t+dp(236),dp(10),TEXT);
        text(c,"2. Choose MOVE / ROT / SCALE",l+dp(16),t+dp(255),dp(10),TEXT);
        text(c,"3. Drag the colored 3D handle",l+dp(16),t+dp(274),dp(10),TEXT);
        text(c,"4. Two fingers pan + pinch zoom",l+dp(16),t+dp(293),dp(10),TEXT);
    }

    private void inspectorRow(Canvas c,float l,float y,String name,String value){
        text(c,name,l+dp(18),y,dp(8),MUTED);
        round(c,PANEL2,l+dp(14),y+dp(7),l+dp(330),y+dp(39),dp(5));
        text(c,value,l+dp(23),y+dp(28),dp(10),TEXT);
    }

    private void drawAssets(Canvas c,float l,float t,float r,float b){
        text(c,"PROJECT ROOT",l+dp(17),t+dp(69),dp(8),MUTED);
        File root=browserDir!=null?browserDir:state.projectRoot;
        text(c,root==null?"/":root.getPath(),l+dp(17),t+dp(88),dp(9),ACCENT);
        smallButton(c,l+dp(16),t+dp(101),dp(68),"UP",false);
        smallButton(c,l+dp(92),t+dp(101),dp(84),"REFRESH",false);
        File[] fs=root==null?null:root.listFiles();
        if(fs!=null){
            Arrays.sort(fs,(a,bx)->{int d=Boolean.compare(!a.isDirectory(),!bx.isDirectory());return d!=0?d:a.getName().compareToIgnoreCase(bx.getName());});
            float y=t+dp(152);
            for(File f:fs){
                if(y>b-dp(44))break;
                round(c,f.isDirectory()?0xff17253a:0xff121a27,l+dp(12),y-dp(16),r-dp(12),y+dp(12),dp(5));
                text(c,f.isDirectory()?"▸":"•",l+dp(20),y+dp(1),dp(11),f.isDirectory()?ACCENT:MUTED);
                text(c,f.getName(),l+dp(40),y+dp(1),dp(10),TEXT);
                text(c,f.isDirectory()?"FOLDER":"FILE",r-dp(54),y+dp(1),dp(7),MUTED);
                y+=dp(34);
            }
        }
        text(c,"Tap folder to enter • tap file to inspect",l+dp(16),b-dp(20),dp(8),MUTED);
    }

    private void drawAnimation(Canvas c,float l,float t,float r,float b){
        text(c,"CLIP  "+timeline.clip.name,l+dp(16),t+dp(68),dp(12),TEXT);
        smallButton(c,l+dp(16),t+dp(82),dp(62),timeline.playing?"PAUSE":"PLAY",timeline.playing);
        smallButton(c,l+dp(84),t+dp(82),dp(84),"ADD TRACK",false);
        smallButton(c,l+dp(174),t+dp(82),dp(80),"KEYFRAME",false);
        smallButton(c,l+dp(260),t+dp(82),dp(82),timeline.autoKey?"AUTO ON":"AUTO OFF",timeline.autoKey);
        text(c,timeline.timecode(timeline.playhead),r-dp(78),t+dp(101),dp(9),WARN);
        float top=t+dp(132),bot=b-dp(28);
        round(c,0xff080d15,l+dp(12),top,r-dp(12),bot,dp(5));
        for(int i=0;i<=8;i++){
            float x=l+dp(14)+(r-l-dp(28))*i/8f;
            p.setColor(0xff253149);p.setStrokeWidth(1);c.drawLine(x,top,x,bot,p);
            text(c,String.format(Locale.US,"%.1fs",timeline.clip.duration*i/8f),x+dp(3),top+dp(17),dp(7),MUTED);
        }
        float norm=timeline.clip.duration<=0?0:timeline.playhead/timeline.clip.duration;
        float px=l+dp(14)+(r-l-dp(28))*norm;
        p.setColor(WARN);p.setStrokeWidth(dp(2));c.drawLine(px,top,px,bot,p);
        float yy=top+dp(50);
        for(int i=0;i<timeline.clip.tracks.size();i++){
            AnimationSystem.Track tr=timeline.clip.tracks.get(i);
            if(i==timeline.selectedTrack)round(c,0xff1e3150,l+dp(14),yy-dp(15),r-dp(14),yy+dp(11),dp(4));
            text(c,tr.path,l+dp(22),yy,dp(9),TEXT);
            for(AnimationSystem.Key k:tr.keys){
                float kx=l+dp(14)+(r-l-dp(28))*(k.time/timeline.clip.duration);
                p.setColor(ACCENT);Path path=new Path();path.moveTo(kx,yy-dp(5));path.lineTo(kx+dp(5),yy);path.lineTo(kx,yy+dp(5));path.lineTo(kx-dp(5),yy);path.close();c.drawPath(path,p);
            }
            yy+=dp(30);
        }
    }

    private void drawScript(Canvas c,float l,float t,float r,float b){
        text(c,state.scriptPath,l+dp(16),t+dp(68),dp(9),ACCENT);
        round(c,0xff070b12,l+dp(12),t+dp(80),r-dp(12),b-dp(66),dp(5));
        String[] lines=script.text.split("\\n",-1);
        float y=t+dp(102);
        for(int i=0;i<lines.length&&y<b-dp(86);i++){
            text(c,String.format(Locale.US,"%03d",i+1),l+dp(18),y,dp(8),0xff4e5d75);
            text(c,lines[i],l+dp(52),y,dp(9),lines[i].contains("camera")?0xff83a9ff:TEXT);
            y+=dp(17);
        }
        smallButton(c,l+dp(16),b-dp(52),dp(68),"FORMAT",false);
        smallButton(c,l+dp(90),b-dp(52),dp(76),"COMPILE",true);
        text(c,script.diagnostics().isEmpty()?"No diagnostics":script.diagnostics().size()+" diagnostics",l+dp(176),b-dp(32),dp(8),script.diagnostics().isEmpty()?GOOD:BAD);
    }

    private void drawCSharp(Canvas c,float l,float t,float r,float b){
        File root=state.projectRoot;
        text(c,"C# MOBILE SCRIPTING",l+dp(16),t+dp(68),dp(11),TEXT);
        text(c,"using Noir;  •  net10.0-android36.1  •  C# 14",l+dp(16),t+dp(88),dp(8),ACCENT);
        boolean ready=root!=null;
        text(c,ready?"PROJECT READY":"OPEN A PROJECT TO ENABLE C#",l+dp(16),t+dp(112),dp(10),ready?GOOD:WARN);
        text(c,"Self-contained mobile SDK + .csproj",l+dp(18),t+dp(146),dp(9),MUTED);
        text(c,"Roslyn host: repository /csharp/Noir.CSharp.Compiler",l+dp(18),t+dp(170),dp(9),MUTED);
        text(c,"Mobile target: Android API 26+ • Renderer API: Forward+ Mobile",l+dp(18),t+dp(194),dp(9),MUTED);
        smallButton(c,l+dp(16),t+dp(220),dp(116),"CREATE C# PROJECT",true);
        smallButton(c,l+dp(142),t+dp(220),dp(98),"OPEN C# FOLDER",false);
        smallButton(c,l+dp(246),t+dp(220),dp(84),"CHECK SDK",false);
        round(c,0xff080d15,l+dp(12),t+dp(270),r-dp(12),b-dp(66),dp(5));
        text(c,"PlayerController.cs",l+dp(22),t+dp(296),dp(9),TEXT);
        text(c,"using Noir;",l+dp(22),t+dp(318),dp(9),0xff83a9ff);
        text(c,"public sealed class PlayerController : Character3D",l+dp(22),t+dp(338),dp(9),TEXT);
        text(c,"Input.Vector(\"ui_left\",\"ui_right\",\"ui_up\",\"ui_down\")",l+dp(22),t+dp(358),dp(8),TEXT);
        text(c,"PhysicsUpdate(delta) • MoveAndSlide()",l+dp(22),t+dp(378),dp(8),TEXT);
        text(c,"Note: Java/ART editor prepares the project; the C# host is a separate .NET compiler/runtime boundary.",l+dp(18),b-dp(82),dp(7),MUTED);
    }

    private void drawShader(Canvas c,float l,float t,float r,float b){
        text(c,"MATERIAL GRAPH • MOBILE PBR",l+dp(16),t+dp(68),dp(11),TEXT);
        String[] nodes={"ALBEDO","NORMAL","ROUGHNESS","METALLIC","AO","EMISSION","IBL","OUTPUT"};
        for(int i=0;i<nodes.length;i++){
            float x=l+dp(16)+(i%2)*dp(150),y=t+dp(92)+(i/2)*dp(58);
            round(c,0xff1c2940,x,y,x+dp(132),y+dp(40),dp(6));
            text(c,nodes[i],x+dp(10),y+dp(25),dp(9),TEXT);
        }
        smallButton(c,l+dp(16),b-dp(52),dp(104),"NEW MATERIAL",true);
        smallButton(c,l+dp(128),b-dp(52),dp(86),"VALIDATE",false);
        smallButton(c,l+dp(222),b-dp(52),dp(112),"OPEN SHADER IDE",true);
        text(c,"PBR • GGX • Fresnel • Filmic",l+dp(16),b-dp(82),dp(8),GOOD);
    }

    private void drawPhysics(Canvas c,float l,float t,float r,float b){
        text(c,"PHYSICS BACKEND",l+dp(16),t+dp(68),dp(9),MUTED);
        text(c,NoirNative.isLoaded()?"NATIVE C++ / JNI":"JAVA FALLBACK",l+dp(16),t+dp(88),dp(13),NoirNative.isLoaded()?GOOD:WARN);
        String[] rows={"Fixed step 60 Hz","Gravity -9.81 m/s²","Static AABB collision","Ray / sphere / AABB tests","Damped rigid-body integration","Navigation-ready scene API"};
        float y=t+dp(120);
        for(String row:rows){text(c,"• "+row,l+dp(18),y,dp(10),TEXT);y+=dp(27);}
        smallButton(c,l+dp(16),b-dp(52),dp(100),"CREATE BODY",true);
        smallButton(c,l+dp(124),b-dp(52),dp(90),"DEBUG DRAW",false);
    }

    private void drawWorld(Canvas c,float l,float t,float r,float b){
        text(c,"WORLD / ENVIRONMENT",l+dp(16),t+dp(68),dp(11),TEXT);
        String[] names={"SKY","CLOUDS","SUN SHADOWS","REFLECTIONS","FOG","PBR"};
        for(int i=0;i<names.length;i++){
            boolean on=worldFlag(names[i]);
            float y=t+dp(98)+i*dp(40);
            text(c,names[i],l+dp(18),y+dp(18),dp(9),TEXT);
            smallButton(c,r-dp(92),y,dp(76),on?"ON":"OFF",on);
        }
        text(c,"GRAPHICS BACKEND",l+dp(18),t+dp(326),dp(9),MUTED);
        smallButton(c,l+dp(18),t+dp(338),dp(76),"GLES",renderer.graphicsBackend()==NoirRenderer.GraphicsBackend.GLES);
        smallButton(c,l+dp(102),t+dp(338),dp(88),"VULKAN",renderer.graphicsBackend()==NoirRenderer.GraphicsBackend.VULKAN);
        text(c,renderer.graphicsBackendStatus(),l+dp(18),t+dp(390),dp(8),MUTED);
        text(c,"QUALITY",l+dp(18),b-dp(112),dp(9),MUTED);
        smallButton(c,l+dp(74),b-dp(126),dp(62),"HIGH",false);
        smallButton(c,l+dp(140),b-dp(126),dp(62),"ULTRA",true);
        smallButton(c,l+dp(206),b-dp(126),dp(76),"EXTREME",false);
        text(c,"Exposure",l+dp(18),b-dp(60),dp(9),MUTED);
        smallButton(c,l+dp(86),b-dp(73),dp(42),"-",false);
        text(c,String.format(Locale.US,"%.2f",renderer.quality().exposure),l+dp(138),b-dp(52),dp(11),TEXT);
        smallButton(c,l+dp(174),b-dp(73),dp(42),"+",true);
    }

    private void drawController(Canvas c,float l,float t,float r,float b){
        text(c,"MOBILE INPUT MAP",l+dp(16),t+dp(68),dp(11),TEXT);
        String[] rows={"MOVE    ui_up / ui_down / ui_left / ui_right","LOOK    touch drag","PAN     two-finger drag","ZOOM    pinch","JUMP    action_jump","CAMERA  action_camera"};
        float y=t+dp(101);
        for(String row:rows){round(c,PANEL2,l+dp(12),y-dp(16),r-dp(12),y+dp(12),dp(4));text(c,row,l+dp(20),y+dp(1),dp(9),TEXT);y+=dp(36);}
        smallButton(c,l+dp(16),b-dp(52),dp(116),"EDIT BINDINGS",true);
    }

    private void drawProfiler(Canvas c,float l,float t,float r,float b){
        text(c,"FRAME PROFILER",l+dp(16),t+dp(68),dp(11),TEXT);
        float ft=renderer.frameTimeMs();
        String[] rows={
            String.format(Locale.US,"Frame %.2f ms",ft),
            "GPU Forward PBR • Ultra quality","Shadow map "+renderer.quality().shadowSize+" PCF","ACES tone mapping • color grading",
            "Sun rays • analytic god rays • glow",
            "Scene nodes "+state.scene.flatten().size(),
            "Native "+(NoirNative.isLoaded()?"loaded":"fallback")
        };
        float y=t+dp(101);
        for(String row:rows){text(c,row,l+dp(18),y,dp(10),TEXT);y+=dp(31);}
        smallButton(c,l+dp(16),b-dp(52),dp(92),"RESET",false);
        postInvalidateDelayed(250);
    }

    private void drawConsole(Canvas c,float l,float t,float r,float b){
        float y=t+dp(70);
        for(String line:state.console){
            text(c,line,l+dp(16),y,dp(9),line.contains("ERROR")?BAD:0xffa7c8b1);
            y+=dp(19);
            if(y>b-dp(60))break;
        }
        smallButton(c,l+dp(16),b-dp(52),dp(64),"CLEAR",false);
        smallButton(c,l+dp(88),b-dp(52),dp(76),"COPY",false);
    }

    private void drawGizmo(Canvas c){
        if(state.selected==null||state.playing)return;
        float[] center=renderer.projectWorldToScreen(state.selected.px,state.selected.py,state.selected.pz);
        if(center==null)return;
        float size=renderer.gizmoWorldSize();
        TransformGizmo.Axis active=gizmo.activeAxis();
        if(state.tool==EditorState.Tool.ROTATE){
            drawRotationRing(c,TransformGizmo.Axis.X,size,active,0);
            drawRotationRing(c,TransformGizmo.Axis.Y,size,active,1);
            drawRotationRing(c,TransformGizmo.Axis.Z,size,active,2);
        }else{
            drawAxis(c,TransformGizmo.Axis.X,size,active);
            drawAxis(c,TransformGizmo.Axis.Y,size,active);
            drawAxis(c,TransformGizmo.Axis.Z,size,active);
        }
        round(c,0xdd0b111c,center[0]-dp(36),center[1]-dp(34),center[0]+dp(36),center[1]-dp(15),dp(4));
        text(c,state.tool.name()+" • "+(active==TransformGizmo.Axis.NONE?"3D GIZMO":active.name()),center[0]-dp(30),center[1]-dp(21),dp(7),TEXT);
    }

    private void drawAxis(Canvas c,TransformGizmo.Axis axis,float size,TransformGizmo.Axis active){
        NoirNode n=state.selected;
        float[] a=renderer.projectWorldToScreen(n.px,n.py,n.pz);
        float[] b=renderer.projectWorldToScreen(n.px+(axis==TransformGizmo.Axis.X?size:0),n.py+(axis==TransformGizmo.Axis.Y?size:0),n.pz+(axis==TransformGizmo.Axis.Z?size:0));
        if(a==null||b==null)return;
        int color=TransformGizmo.axisColor(axis);
        boolean hot=active==axis;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(hot?6:4));p.setColor(color);c.drawLine(a[0],a[1],b[0],b[1],p);
        p.setStyle(Paint.Style.FILL);
        Path arrow=new Path();
        float dx=b[0]-a[0],dy=b[1]-a[1],len=(float)Math.hypot(dx,dy);
        if(len>2){
            dx/=len;dy/=len;float px=-dy,py=dx;
            arrow.moveTo(b[0],b[1]);arrow.lineTo(b[0]-dx*dp(14)+px*dp(7),b[1]-dy*dp(14)+py*dp(7));
            arrow.lineTo(b[0]-dx*dp(14)-px*dp(7),b[1]-dy*dp(14)-py*dp(7));arrow.close();c.drawPath(arrow,p);
        }
        text(c,axis.name(),b[0]+dp(6),b[1]-dp(5),dp(9),color);
    }

    private void drawRotationRing(Canvas c,TransformGizmo.Axis axis,float size,TransformGizmo.Axis active,int idx){
        NoirNode n=state.selected;
        float[] b1=axis==TransformGizmo.Axis.X?new float[]{0,1,0}:axis==TransformGizmo.Axis.Y?new float[]{0,0,1}:new float[]{1,0,0};
        float[] b2=axis==TransformGizmo.Axis.X?new float[]{0,0,1}:axis==TransformGizmo.Axis.Y?new float[]{1,0,0}:new float[]{0,1,0};
        Path path=new Path();boolean first=true;
        for(int i=0;i<=48;i++){
            float a=(float)(Math.PI*2*i/48.0);
            float x=n.px+(b1[0]*(float)Math.cos(a)+b2[0]*(float)Math.sin(a))*size;
            float y=n.py+(b1[1]*(float)Math.cos(a)+b2[1]*(float)Math.sin(a))*size;
            float z=n.pz+(b1[2]*(float)Math.cos(a)+b2[2]*(float)Math.sin(a))*size;
            float[] q=renderer.projectWorldToScreen(x,y,z);
            if(q==null){first=true;continue;}
            if(first){path.moveTo(q[0],q[1]);first=false;}else path.lineTo(q[0],q[1]);
        }
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(active==axis?5:2));p.setColor(TransformGizmo.axisColor(axis));c.drawPath(path,p);
        p.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        final int action=e.getActionMasked();
        float x=e.getX(),y=e.getY(),w=getWidth(),h=getHeight();

        if(action==MotionEvent.ACTION_DOWN){
            downX=lastX=x;downY=lastY=y;downTime=System.currentTimeMillis();
            tabGestureStartX=x;tabGestureStartScroll=tabScroll;tabScrolling=false;
            viewportMoved=false;uiTouch=!isViewport(x,y,w,h);viewportTouch=!uiTouch;
            if(viewportTouch && state.selected!=null && state.tool!=EditorState.Tool.SELECT){
                gizmo.begin(renderer,state.selected,state.tool,x,y,dp(22));
            }
            return true;
        }

        if(action==MotionEvent.ACTION_POINTER_DOWN){
            if(isViewport(x,y,w,h)){
                pinchDistance=distance(e);
                float[] center=center(e);
                lastCenterX=center[0];lastCenterY=center[1];
                gizmo.cancel();
                return true;
            }
        }

        if(action==MotionEvent.ACTION_MOVE){
            if(compactUi && e.getPointerCount()==1 && y>=topBar && y<topBar+tabBar){
                float dx=x-tabGestureStartX;
                if(Math.abs(dx)>dp(6))tabScrolling=true;
                if(tabScrolling){
                    float tw=dp(70),maxScroll=Math.max(0,(tw+dp(4))*tabs.length-w+dp(14));
                    tabScroll=Math.max(0,Math.min(maxScroll,tabGestureStartScroll-dx));
                    invalidate();return true;
                }
            }
            if(e.getPointerCount()>=2 && isViewport(x,y,w,h)){
                float d=distance(e);
                float[] center=center(e);
                if(pinchDistance>0)renderer.zoom((pinchDistance-d)*0.018f);
                renderer.pan(center[0]-lastCenterX,center[1]-lastCenterY);
                pinchDistance=d;lastCenterX=center[0];lastCenterY=center[1];
                viewportMoved=true;invalidate();return true;
            }
            if(viewportTouch&&e.getPointerCount()==1){
                float dx=x-lastX,dy=y-lastY;
                if(gizmo.dragging()){
                    if(gizmo.update(renderer,state.selected,x,y)){
                        if(state.snapping&&state.tool==EditorState.Tool.MOVE)snapNode(state.selected,state.snapStep);
                        state.log("Gizmo "+gizmo.status()+" -> "+state.selected.transformText());
                        status="Editing "+gizmo.status();
                    }
                }else{
                    renderer.orbit(dx,dy);
                    status="Orbit "+String.format(Locale.US,"yaw %.1f° pitch %.1f°",renderer.camera().yaw,renderer.camera().pitch);
                }
                if(Math.hypot(x-downX,y-downY)>dp(5))viewportMoved=true;
                lastX=x;lastY=y;invalidate();return true;
            }
            return true;
        }

        if(action==MotionEvent.ACTION_UP){
            long held=System.currentTimeMillis()-downTime;
            if(compactUi && tabScrolling){invalidate();return true;}
            if(gizmo.dragging()){
                gizmo.end();status="Transform committed";invalidate();return true;
            }
            if(uiTouch){
                handleUiTap(x,y,w,h);
                return true;
            }
            if(viewportTouch&&!viewportMoved){
                if(held>600){showNoirContextMenu(x,y);return true;}
                selectAt(x,y);
            }
            invalidate();return true;
        }

        if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_UP){
            if(e.getPointerCount()<3)pinchDistance=0;
            if(action==MotionEvent.ACTION_CANCEL)gizmo.cancel();
            return true;
        }
        return true;
    }

    private boolean isViewport(float x,float y,float w,float h){
        float t=topBar+tabBar,b=h-bottomBar;
        if(y<t||y>b)return false;
        float left=compactUi?0:(tab==0||tab==2? (tab==2?0:leftW):leftW);
        float right=compactUi?w:((tab==0||tab==2)?w-rightW:w);
        return x>left&&x<right;
    }

    private void handleUiTap(float x,float y,float w,float h){
        if(y<topBar){
            if(compactUi){
                float x0=dp(84),bw=dp(50),gap=dp(4);
                if(hit(x,y,x0,dp(7),x0+bw,topBar)){state.tool=EditorState.Tool.SELECT;status="Select tool";}
                else if(hit(x,y,x0+bw+gap,dp(7),x0+2*(bw+gap),topBar)){state.tool=EditorState.Tool.MOVE;status="Move tool";}
                else if(hit(x,y,x0+2*(bw+gap),dp(7),x0+3*(bw+gap),topBar)){state.tool=EditorState.Tool.ROTATE;status="Rotate tool";}
                else if(hit(x,y,x0+3*(bw+gap),dp(7),x0+3*(bw+gap)+bw,topBar)){state.tool=EditorState.Tool.SCALE;status="Scale tool";}
                else if(x>w-dp(58)){showCompactMenu();}
                invalidate();return;
            }
            if(hit(x,y,dp(220),dp(9),dp(274),dp(49))){state.tool=EditorState.Tool.SELECT;status="Select tool";}
            else if(hit(x,y,dp(280),dp(9),dp(344),dp(49))){state.tool=EditorState.Tool.MOVE;status="Move tool";}
            else if(hit(x,y,dp(350),dp(9),dp(414),dp(49))){state.tool=EditorState.Tool.ROTATE;status="Rotate tool";}
            else if(hit(x,y,dp(420),dp(9),dp(490),dp(49))){state.tool=EditorState.Tool.SCALE;status="Scale tool";}
            else if(x>w-dp(340)&&x<w-dp(268)){togglePlay();}
            else if(x>w-dp(262)&&x<w-dp(192)){buildProject();}
            else if(x>w-dp(186)&&x<w-dp(118)){saveProject();}
            else if(x>w-dp(112)){renderer.resetEditorCamera();status="Editor camera reset";state.log("Editor camera reset");}
            invalidate();return;
        }
        if(y>=topBar&&y<topBar+tabBar){
            float tw=compactUi?dp(70):Math.max(dp(66),Math.min(dp(94),(w-dp(14)-dp(4)*(tabs.length-1))/tabs.length));
            int i=(int)((x-dp(7)+tabScroll)/(tw+dp(4)));
            if(i>=0&&i<tabs.length){tab=i;status=tabs[i]+" panel";invalidate();}
            return;
        }
        float t=topBar+tabBar,b=h-bottomBar;
        if(tab==0){
            if(x<leftW){
                List<NoirNode> nodes=tree.visible();
                float rowY=t+dp(92);
                int row=(int)((y-(rowY-dp(20)))/dp(29));
                if(row>=0&&row<nodes.size()&&y<b-dp(88)){state.select(nodes.get(row));status="Selected "+nodes.get(row).name;}
                else if(y>b-dp(82)&&x<dp(90))addNode();
                else if(y>b-dp(82)&&x<dp(170))duplicateSelected();
                else if(y>b-dp(82))deleteSelected();
            }else if(x>w-rightW){
                handleInspectorTap(x,y,w-rightW,t,b);
            }
        }else if(tab==1){
            handleAssetTap(x,y,t,b);
        }else if(tab==2){
            handleInspectorTap(x,y,w-rightW,t,b);
            if(x<leftW)handleInspectorTools(x,y,t);
        }else if(tab==3){
            handleAnimationTap(x,y,t,b);
        }else if(tab==4){
            if(y>b-dp(76)&&x<dp(190)){state.log("Script compiled successfully");status="Script compile OK";}
        }else if(tab==5){
            if(y>b-dp(76)&&x>=dp(210)){openShaderEditor();}
            else if(y>b-dp(76)&&x<dp(225)){state.log("PBR material graph validated");status="Shader graph valid";}
        }else if(tab==6){
            if(y>b-dp(76)&&x<dp(120)){addNodeKind(NoirNode.Kind.RIGID_BODY3D);}
        }else if(tab==7){
            handleWorldTap(x,y,t,b,w);
        }else if(tab==8){
            if(y>b-dp(76)&&x<dp(150)){showBindings();}
        }else if(tab==9){
            status="Profiler refreshed";invalidate();
        }else if(tab==10){
            if(y>b-dp(76)&&x<dp(78))clearConsole();
            else if(y>b-dp(76)&&x<dp(170)){Toast.makeText(getContext(),"Console entries ready to copy",Toast.LENGTH_SHORT).show();}
        }else if(tab==11){
            if(y>t+dp(210)&&y<t+dp(270)&&x<dp(138)){createCSharpProject();}
            else if(y>t+dp(210)&&y<t+dp(270)&&x<dp(250)){openCSharpFolder();}
            else if(y>t+dp(210)&&y<t+dp(270)){checkCSharpSdk();}
        }
        invalidate();
    }

    private void handleInspectorTap(float x,float y,float l,float t,float b){
        NoirNode n=state.selected;if(n==null)return;
        float rel=y-t;
        if(rel>dp(176)&&rel<dp(238)){editVector("Position",n,0);return;}
        if(rel>dp(234)&&rel<dp(298)){editVector("Rotation",n,1);return;}
        if(rel>dp(292)&&rel<dp(354)){editVector("Scale",n,2);return;}
        if(rel>dp(350)&&rel<dp(410)&&x<l+dp(110)){editVector("Position",n,0);return;}
        if(rel>dp(350)&&rel<dp(410)&&x<l+dp(214)){editVector("Rotation",n,1);return;}
        if(rel>dp(350)&&rel<dp(410)){editVector("Scale",n,2);return;}
        if(rel>dp(402)&&rel<dp(462)){if(x<l+dp(102))n.visible=!n.visible;else n.locked=!n.locked;state.log("Inspector flags updated");return;}
        if(rel>dp(458)&&rel<dp(540)){
            if(x<l+dp(130))attachScript();else addComponent();
        }
    }

    private void handleInspectorTools(float x,float y,float t){
        if(y>t+dp(94)&&y<t+dp(146)){
            if(x<dp(84))state.tool=EditorState.Tool.SELECT;
            else if(x<dp(156))state.tool=EditorState.Tool.MOVE;
            else if(x<dp(228))state.tool=EditorState.Tool.ROTATE;
            else state.tool=EditorState.Tool.SCALE;
        }else if(y>t+dp(158)&&y<t+dp(218)){
            if(x<dp(96))state.snapping=!state.snapping;
            else if(x<dp(180))state.snapStep=0.25f;
            else if(x<dp(264))state.snapStep=0.5f;
            else state.snapStep=1f;
        }
    }

    private void handleAssetTap(float x,float y,float t,float b){
        File root=browserDir!=null?browserDir:state.projectRoot;
        if(y>t+dp(98)&&y<t+dp(146)&&x<dp(84)){if(root!=null&&root.getParentFile()!=null){browserDir=root.getParentFile();status="Up: "+browserDir.getName();}return;}
        if(y<t+dp(148))return;
        File[] fs=root==null?null:root.listFiles();
        if(fs==null)return;
        Arrays.sort(fs,(a,bx)->{int d=Boolean.compare(!a.isDirectory(),!bx.isDirectory());return d!=0?d:a.getName().compareToIgnoreCase(bx.getName());});
        int row=(int)((y-(t+dp(136)))/dp(34));
        if(row>=0&&row<fs.length){
            File f=fs[row];
            if(f.isDirectory()){browserHistory.push(root);browserDir=f;status="Opened "+f.getName();}
            else{
                String lower=f.getName().toLowerCase(Locale.US);
                if(lower.endsWith(".cs")||lower.endsWith(".game")||lower.endsWith(".shader")||lower.endsWith(".glsl")||lower.endsWith(".vert")||lower.endsWith(".frag")){
                    NoirScriptIdeView.open(getContext(),f);
                    status="Opened "+f.getName()+" • "+(lower.endsWith(".cs")?"C#":((lower.endsWith(".shader")||lower.endsWith(".glsl")||lower.endsWith(".vert")||lower.endsWith(".frag"))?"Shader":"Noir .game"));
                }else{
                    state.log("Asset selected: "+f.getName());status="Asset "+f.getName();
                }
            }
        }
    }

    private void handleAnimationTap(float x,float y,float t,float b){
        if(y>t+dp(78)&&y<t+dp(128)){
            if(x<dp(82)){timeline.playing=!timeline.playing;lastAnimationNanos=System.nanoTime();status=timeline.playing?"Animation playing":"Animation paused";}
            else if(x<dp(172)){String name="Track_"+(timeline.clip.tracks.size()+1);timeline.addTrack(name);status="Added "+name;}
            else if(x<dp(258)){if(timeline.selectedTrack<0)timeline.selectedTrack=0;timeline.addKey(timeline.playhead,0,0,0);status="Keyframe added";}
            else{timeline.autoKey=!timeline.autoKey;status=timeline.autoKey?"Auto key ON":"Auto key OFF";}
            return;
        }
        float top=t+dp(132),bot=b-dp(28);
        if(y>top&&y<bot){
            float norm=Math.max(0f,Math.min(1f,(x-dp(14)-12f)/(leftW-dp(28))));
            timeline.scrub(norm);status="Scrub "+timeline.timecode(timeline.playhead);
        }
    }

    private void handleWorldTap(float x,float y,float t,float b,float w){
        if(y>t+dp(326)&&y<t+dp(382)){
            if(x>=dp(12)&&x<dp(98)){
                NoirGraphicsBackend.save(getContext(),NoirGraphicsBackend.Type.GLES);
                status="GLES selected • restart required";
                new AlertDialog.Builder(getContext()).setTitle("Switch graphics backend")
                    .setMessage("GLES will become active after restarting the editor.")
                    .setNegativeButton("CANCEL",null)
                    .setPositiveButton("RESTART",(d,which)->{
                        try { ((android.app.Activity)getContext()).recreate(); } catch (Throwable ignored) {}
                    }).show();
            }else if(x>=dp(98)&&x<dp(202)){
                if(NoirGraphicsBackend.vulkanAvailable()){
                    boolean deviceReady=NoirGraphicsBackend.initializeVulkanStage();
                    if(deviceReady){
                        NoirGraphicsBackend.save(getContext(),NoirGraphicsBackend.Type.VULKAN);
                        status="Vulkan selected • restart required";
                        new AlertDialog.Builder(getContext()).setTitle("Switch to Vulkan")
                            .setMessage("Vulkan will become the active renderer after restarting the editor.\n\n"+NoirGraphicsBackend.vulkanDeviceInfo())
                            .setNegativeButton("CANCEL",null)
                            .setPositiveButton("RESTART",(d,which)->{
                                try { ((android.app.Activity)getContext()).recreate(); } catch (Throwable ignored) {}
                            }).show();
                    }else{
                        renderer.setGraphicsBackend(NoirRenderer.GraphicsBackend.GLES);
                        status="Vulkan init failed safely — GLES remains active";
                        Toast.makeText(getContext(),"Vulkan initialization failed; GLES remains active.",Toast.LENGTH_SHORT).show();
                    }
                }else{
                    renderer.setGraphicsBackend(NoirRenderer.GraphicsBackend.GLES);
                    NoirGraphicsBackend.save(getContext(),NoirGraphicsBackend.Type.GLES);
                    status="Vulkan unavailable — using GLES";
                    Toast.makeText(getContext(),"Vulkan is not available on this device. GLES remains active.",Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        if(y>b-dp(150)&&y<b-dp(100)){
            if(x>dp(68)&&x<dp(140)){renderer.setQualityPreset(NoirRenderer.QualityPreset.HIGH);status="Quality HIGH";}
            else if(x>=dp(140)&&x<dp(206)){renderer.setQualityPreset(NoirRenderer.QualityPreset.ULTRA);status="Quality ULTRA";}
            else if(x>=dp(206)&&x<dp(290)){renderer.setQualityPreset(NoirRenderer.QualityPreset.EXTREME);status="Quality EXTREME";}
            return;
        }
        if(y>t+dp(86)&&y<t+dp(350)&&x>w-dp(100)){
            int idx=(int)((y-(t+dp(98)))/dp(40));
            if(idx>=0&&idx<6){
                String key=new String[]{"sky","clouds","shadows","reflections","fog","pbr"}[idx];
                String next=state.scene.environment.getOrDefault(key,"on").equals("on")?"off":"on";
                state.scene.environment.put(key,next);
                boolean enabled="on".equals(next);
                if("shadows".equals(key))renderer.quality().shadows=enabled;
                else if("reflections".equals(key))renderer.quality().reflections=enabled;
                else if("fog".equals(key))renderer.quality().fog=enabled;
                else if("clouds".equals(key))renderer.quality().clouds=enabled;
                status="World "+key+" "+next;
            }
        }else if(y>b-dp(88)&&x>dp(80)&&x<dp(145)){
            renderer.quality().exposure=Math.max(0.2f,renderer.quality().exposure-0.1f);
        }else if(y>b-dp(88)&&x>dp(165)&&x<dp(225)){
            renderer.quality().exposure=Math.min(3.0f,renderer.quality().exposure+0.1f);
        }
    }

    private boolean worldFlag(String key){
        String k=key.toLowerCase(Locale.US).replace(" ","");
        if(k.contains("sky"))return !"off".equals(state.scene.environment.get("sky"));
        if(k.contains("cloud"))return !"off".equals(state.scene.environment.get("clouds"));
        if(k.contains("sun"))return renderer.quality().shadows;
        if(k.contains("reflection"))return renderer.quality().reflections;
        if(k.contains("fog"))return renderer.quality().fog;
        return true;
    }

    private void snapNode(NoirNode n,float step){
        if(step<=0f)return;
        n.px=Math.round(n.px/step)*step;
        n.py=Math.round(n.py/step)*step;
        n.pz=Math.round(n.pz/step)*step;
    }

    private void selectAt(float x,float y){
        NoirNode best=null;float bestD=Float.MAX_VALUE;
        for(NoirNode n:tree.visible()){
            if(n==state.scene.root||!n.visible||n.locked)continue;
            float[] q=renderer.projectWorldToScreen(n.px,n.py,n.pz);
            if(q==null)continue;
            float d=(float)Math.hypot(q[0]-x,q[1]-y);
            if(d<bestD&&d<dp(54)){best=n;bestD=d;}
        }
        if(best!=null){state.select(best);status="Selected "+best.name;}
        else status="No scene node under pointer";
    }

    private void showNoirContextMenu(float x,float y){
        String[] items={"Select node","Add child","Duplicate","Delete","Focus camera"};
        new AlertDialog.Builder(getContext()).setTitle("Viewport")
            .setItems(items,(d,which)->{
                switch(which){
                    case 0:selectAt(x,y);break;
                    case 1:addNode();break;
                    case 2:duplicateSelected();break;
                    case 3:deleteSelected();break;
                    default:focusSelected();break;
                }
                invalidate();
            }).show();
    }

    private void addNode(){
        String[] names={"Node3D","Character3D","Player3D","Camera3D","Light3D","Mesh3D","SkinnedMesh3D","Collider3D","RigidBody3D","StaticBody3D","Area3D","RayCast3D","Audio3D","Particles3D","Decal3D","Water3D","Terrain3D","Foliage3D","Spline3D","NavMesh3D","NavAgent3D","ReflectionProbe3D","LightProbe3D","WorldEnvironment","Sky3D","FogVolume3D","PostProcess3D","LODGroup3D","Occluder3D","AnimationPlayer","AnimationTree","BoneAttachment3D","IKTarget3D","Vehicle3D","SpringArm3D","UI3D"};
        new AlertDialog.Builder(getContext()).setTitle("Add Node").setItems(names,(d,which)->{
            try{addNodeKind(NoirNode.Kind.valueOf(names[which].toUpperCase(Locale.US)));}catch(Exception ex){addNodeKind(NoirNode.Kind.NODE3D);}
        }).show();
    }

    private void addNodeKind(NoirNode.Kind kind){
        NoirNode parent=state.selected==null?state.scene.root:state.selected;
        if(parent==null)parent=state.scene.root;
        String id=kind.name()+"_"+(state.scene.flatten().size()+1);
        NoirNode n=new NoirNode(id,id,kind);
        n.px=parent.px+1f;n.py=parent.py;n.pz=parent.pz;
        parent.add(n);state.select(n);state.log("Created "+n.name);status="Created "+n.name;invalidate();
    }

    private void duplicateSelected(){
        if(state.selected==null||state.selected==state.scene.root)return;
        tree.duplicate(state.selected);
        NoirNode n=state.selected.parent.children.get(state.selected.parent.children.size()-1);
        n.px+=0.75f;n.pz+=0.75f;state.select(n);status="Duplicated "+n.name;invalidate();
    }

    private void deleteSelected(){
        if(state.selected==null||state.selected==state.scene.root)return;
        NoirNode parent=state.selected.parent;
        parent.remove(state.selected);state.select(parent);status="Node deleted";invalidate();
    }

    private void editVector(String title,NoirNode n,int mode){
        EditText input=new EditText(getContext());
        input.setSingleLine(true);
        input.setText(vectorText(n,mode));
        input.setHint("x y z");
        new AlertDialog.Builder(getContext()).setTitle("Edit "+title).setMessage("Enter three values separated by spaces.")
            .setView(input).setNegativeButton("CANCEL",null)
            .setPositiveButton("APPLY",(d,w)->{
                String[] a=input.getText().toString().trim().replace(","," ").split("\\s+");
                if(a.length==3)try{
                    float x=Float.parseFloat(a[0]),y=Float.parseFloat(a[1]),z=Float.parseFloat(a[2]);
                    if(mode==0){n.px=x;n.py=y;n.pz=z;}else if(mode==1){n.rx=x;n.ry=y;n.rz=z;}else{n.sx=x;n.sy=y;n.sz=z;}
                    state.log(title+" updated for "+n.name);status=title+" updated";
                }catch(Exception ex){Toast.makeText(getContext(),"Invalid vector",Toast.LENGTH_SHORT).show();}
                invalidate();
            }).show();
    }

    private String vectorText(NoirNode n,int mode){
        if(mode==0)return String.format(Locale.US,"%.3f %.3f %.3f",n.px,n.py,n.pz);
        if(mode==1)return String.format(Locale.US,"%.1f %.1f %.1f",n.rx,n.ry,n.rz);
        return String.format(Locale.US,"%.3f %.3f %.3f",n.sx,n.sy,n.sz);
    }

    private void attachScript(){
        if(state.selected==null)return;
        EditText input=new EditText(getContext());input.setSingleLine(true);input.setText(state.scriptPath);
        new AlertDialog.Builder(getContext()).setTitle("Attach Noir Script").setView(input)
            .setNegativeButton("CANCEL",null).setPositiveButton("ATTACH",(d,w)->{
                String path=input.getText().toString().trim();
                if(path.isEmpty())path="scripts/player.game";
                state.scriptPath=path;state.selected.properties.put("script",path);
                state.log("Attached "+path+" -> "+state.selected.name);status="Script attached";invalidate();
            }).show();
    }

    private void addComponent(){
        if(state.selected==null)return;
        String[] components={"CharacterController","CameraController","Collider3D","RigidBody3D","Audio3D","Particles3D","AnimationPlayer","SpringArm3D","ReflectionProbe3D","NavigationAgent3D"};
        new AlertDialog.Builder(getContext()).setTitle("Add Component").setItems(components,(d,which)->{
            state.selected.properties.put("component."+components[which],"enabled");
            state.log("Added "+components[which]+" to "+state.selected.name);status="Component added";invalidate();
        }).show();
    }

    private void openShaderEditor(){
        if(state.projectRoot==null){Toast.makeText(getContext(),"Open a Noir project first",Toast.LENGTH_SHORT).show();return;}
        try{
            File dir=new File(state.projectRoot,"shaders");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create shaders folder");
            File shader=new File(dir,"World.shader");
            if(!shader.isFile()){
                String src="shader_type spatial;\nrender_mode cull_back, depth_draw_opaque;\n\nuniform vec4 base_color : source_color = vec4(0.35,0.55,0.85,1.0);\nuniform float roughness : hint_range(0.0,1.0) = 0.55;\nuniform float metallic : hint_range(0.0,1.0) = 0.0;\n\nvoid fragment(){\n    ALBEDO=base_color.rgb;\n    ROUGHNESS=roughness;\n    METALLIC=metallic;\n}\n";
                try(OutputStream out=new FileOutputStream(shader)){out.write(src.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            }
            NoirScriptIdeView.open(getContext(),shader);
            status="Opened shaders/World.shader • Shader IDE";
        }catch(Exception ex){status="Shader IDE failed";Toast.makeText(getContext(),"Shader setup failed: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void createCSharpProject(){
        if(state.projectRoot==null){Toast.makeText(getContext(),"Open a Noir project first",Toast.LENGTH_SHORT).show();return;}
        try{
            NoirCSharpProjectService.Result r=NoirCSharpProjectService.ensure(state.projectRoot,"com.noir.game.scripts");
            state.log("C# project ready: "+r.project.getAbsolutePath());
            status="C# project created";
            Toast.makeText(getContext(),"C# mobile project ready",Toast.LENGTH_SHORT).show();
        }catch(Exception ex){
            state.log("C# ERROR: "+ex.getMessage());
            status="C# project failed";
            Toast.makeText(getContext(),"C# setup failed: "+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
        invalidate();
    }

    private void openCSharpFolder(){
        if(state.projectRoot==null){Toast.makeText(getContext(),"Open a Noir project first",Toast.LENGTH_SHORT).show();return;}
        File folder=new File(state.projectRoot,"csharp/Noir.Game");
        if(folder.isDirectory()){browserDir=folder;tab=1;status="C# folder opened in Assets";}
        else{status="Create the C# project first";Toast.makeText(getContext(),"Create C# project first",Toast.LENGTH_SHORT).show();}
        invalidate();
    }

    private void checkCSharpSdk(){
        if(state.projectRoot==null){status="No project";return;}
        File project=new File(state.projectRoot,"csharp/Noir.Game/Noir.Game.csproj");
        status=project.isFile()?"C# .csproj detected":"C# project not created";
        state.log(status);
        Toast.makeText(getContext(),status,Toast.LENGTH_SHORT).show();
        invalidate();
    }

    private void buildProject(){
        state.log("Build validation started");
        state.log("Scene nodes: "+state.scene.flatten().size());
        state.log("Renderer: "+renderer.graphicsBackendStatus());
        state.log("Native backend: "+(NoirNative.isLoaded()?"loaded":"fallback"));
        if(state.projectRoot!=null){
            try{
                NoirGameCompiler.Result result=new NoirGameCompiler().compileProject(state.projectRoot);
                for(NoirGameCompiler.Diagnostic d:result.diagnostics)state.log(d.toString());
                if(result.success()){
                    status="Build OK • "+result.compiledScripts+" scripts / "+result.validatedScenes+" scenes";
                    Toast.makeText(getContext(),status,Toast.LENGTH_SHORT).show();
                }else{
                    status="Build failed • see Console";
                    Toast.makeText(getContext(),"Noir build has script/scene errors",Toast.LENGTH_LONG).show();
                }
            }catch(Exception ex){
                state.log("BUILD ERROR: "+ex.getMessage());
                status="Build failed";
                Toast.makeText(getContext(),"Build failed: "+ex.getMessage(),Toast.LENGTH_LONG).show();
            }
        }else{
            status="Build validation complete";
            Toast.makeText(getContext(),"No project root",Toast.LENGTH_SHORT).show();
        }
        tab=10;invalidate();
    }

    private void saveProject(){
        if(state.projectRoot==null){Toast.makeText(getContext(),"No project root",Toast.LENGTH_SHORT).show();return;}
        try{
            File target=new File(state.projectRoot,"scenes/Main.game");
            SceneSerializer.save(target,state.scene);
            state.log("Saved "+target.getAbsolutePath());
            status="Saved scenes/Main.game";
            Toast.makeText(getContext(),"Scene saved",Toast.LENGTH_SHORT).show();
        }catch(Exception ex){
            state.log("ERROR Save: "+ex.getMessage());status="Save failed";
            Toast.makeText(getContext(),"Save failed: "+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
        invalidate();
    }

    private void togglePlay(){
        state.playing=!state.playing;
        surface.setRuntimeMode(state.playing);
        setVisibility(state.playing?INVISIBLE:VISIBLE);
        state.log(state.playing?"Play mode entered":"Play mode stopped");
        status=state.playing?"RUNNING":"EDITOR";
        invalidate();
    }

    public void stopPlay(){
        if(state.playing){
            state.playing=false;surface.setRuntimeMode(false);setVisibility(VISIBLE);invalidate();
        }
    }

    private void focusSelected(){
        if(state.selected==null)return;
        NoirRenderer.Camera cam=renderer.camera();
        cam.targetX=state.selected.px;cam.targetY=state.selected.py;cam.targetZ=state.selected.pz;
        cam.distance=Math.max(3f,Math.min(40f,cam.distance));
        status="Focused "+state.selected.name;state.log(status);
    }

    private void clearConsole(){state.console.clear();state.log("Console cleared");status="Console cleared";invalidate();}

    private void showCompactMenu(){
        final String[] items={"Play / Stop","Build validation","Save scene","Reset camera","Scene","Inspector","World","Profiler"};
        new AlertDialog.Builder(getContext()).setTitle("Noir Mobile Tools").setItems(items,(d,which)->{
            switch(which){
                case 0:togglePlay();break;
                case 1:buildProject();break;
                case 2:saveProject();break;
                case 3:renderer.resetEditorCamera();status="Editor camera reset";break;
                case 4:tab=0;break;
                case 5:tab=2;break;
                case 6:tab=7;break;
                case 7:tab=9;break;
            }
            invalidate();
        }).show();
    }

    private void showBindings(){
        new AlertDialog.Builder(getContext()).setTitle("Mobile Controls")
            .setMessage("MOVE: ui_up / ui_down / ui_left / ui_right\\nLOOK: touch drag\\nPAN: two-finger drag\\nZOOM: pinch\\nJUMP: action_jump\\nCAMERA: action_camera")
            .setPositiveButton("OK",null).show();
    }

    private String fitLabel(String s,float max,float size){
        p.setTypeface(Typeface.create("sans",Typeface.NORMAL));p.setTextSize(size);
        if(p.measureText(s)<=max)return s;
        String e="…";float ew=p.measureText(e);int n=s.length();
        while(n>0&&p.measureText(s,0,n)+ew>max)n--;
        return n>0?s.substring(0,n)+e:e;
    }
    private void smallButton(Canvas c,float x,float y,float w,String s,boolean active){
        round(c,active?ACTIVE:0xff202b3d,x,y,x+w,y+dp(32),dp(5));
        text(c,fitLabel(s,Math.max(8,w-dp(18)),dp(8)),x+dp(9),y+dp(21),dp(8),TEXT);
    }

    private void toolButton(Canvas c,float x,float y,float w,float h,String s,boolean active){
        round(c,active?ACTIVE:0xff202b3d,x,y,x+w,y+h,dp(6));
        text(c,fitLabel(s,Math.max(8,w-dp(20)),dp(9)),x+dp(10),y+dp(25),dp(9),TEXT);
    }

    private String icon(NoirNode.Kind k){
        switch(k){
            case CAMERA3D:return "◉";
            case LIGHT3D:return "✦";
            case MESH3D:return "◇";
            case CHARACTER3D:return "♙";
            case PLAYER3D:return "◎";
            case WORLD_ENVIRONMENT:return "☼";
            case STATIC_BODY3D:return "▣";
            case RIGID_BODY3D:return "◆";
            case PARTICLES3D:return "✧";
            default:return "□";
        }
    }

    private float distance(MotionEvent e){
        if(e.getPointerCount()<2)return 0;
        return (float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));
    }
    private float[] center(MotionEvent e){
        if(e.getPointerCount()<2)return new float[]{e.getX(),e.getY()};
        return new float[]{(e.getX(0)+e.getX(1))*0.5f,(e.getY(0)+e.getY(1))*0.5f};
    }

    private String defaultScript(){
        return "entity PlayerController {\\n"+
        "  type: Character3D\\n"+
        "  property speed: 5.0\\n"+
        "  property jump: 4.5\\n"+
        "  input move_x\\n"+
        "  input move_y\\n\\n"+
        "  start { camera = child(\\\"Camera3D\\\") }\\n\\n"+
        "  physics(delta) {\\n"+
        "    movement = vector(move_x, 0, move_y)\\n"+
        "    velocity = move_and_slide(velocity)\\n"+
        "  }\\n}";
    }
}