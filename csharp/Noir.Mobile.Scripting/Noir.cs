using System;
using System.Collections.Generic;

namespace Noir;

public static class Engine
{
    public const string Version="1.1.0";
    public const string GraphicsBackend="NoirGFX-GLES3";
    public const string ApiVersion="1.0";
    public static double DeltaTime{get;internal set;}
    public static double Time{get;internal set;}
    public static int FrameCount{get;internal set;}
    public static bool IsPlaying{get;internal set;}=true;
    public static bool IsEditor{get;internal set;}
    public static bool IsNativeGraphicsAvailable{get;internal set;}
    public static void SetGraphicsQuality(GraphicsQuality quality)=>Graphics.Quality=quality;
    public static void Log(string message)=>Console.WriteLine("[Noir] "+message);
}

public readonly record struct Vector2(float X,float Y)
{
    public static readonly Vector2 Zero=new(0,0);
    public static readonly Vector2 One=new(1,1);
    public float Length()=>MathF.Sqrt(X*X+Y*Y);
    public float LengthSquared()=>X*X+Y*Y;
    public Vector2 Normalized(){var l=Length();return l<=0.00001f?Zero:new(X/l,Y/l);}
    public static float Dot(Vector2 a,Vector2 b)=>a.X*b.X+a.Y*b.Y;
    public static float Distance(Vector2 a,Vector2 b)=>(a-b).Length();
    public static Vector2 Lerp(Vector2 a,Vector2 b,float t)=>a+(b-a)*Math.Clamp(t,0,1);
    public static Vector2 operator +(Vector2 a,Vector2 b)=>new(a.X+b.X,a.Y+b.Y);
    public static Vector2 operator -(Vector2 a,Vector2 b)=>new(a.X-b.X,a.Y-b.Y);
    public static Vector2 operator *(Vector2 a,float b)=>new(a.X*b,a.Y*b);
    public static Vector2 operator *(float b,Vector2 a)=>a*b;
    public static Vector2 operator /(Vector2 a,float b)=>MathF.Abs(b)<1e-8f?Zero:new(a.X/b,a.Y/b);
}

public readonly record struct Vector3(float X,float Y,float Z)
{
    public static readonly Vector3 Zero=new(0,0,0);
    public static readonly Vector3 One=new(1,1,1);
    public static readonly Vector3 Up=new(0,1,0);
    public static readonly Vector3 Down=new(0,-1,0);
    public static readonly Vector3 Forward=new(0,0,-1);
    public static readonly Vector3 Back=new(0,0,1);
    public static readonly Vector3 Right=new(1,0,0);
    public static readonly Vector3 Left=new(-1,0,0);
    public float Length()=>MathF.Sqrt(X*X+Y*Y+Z*Z);
    public float LengthSquared()=>X*X+Y*Y+Z*Z;
    public Vector3 Normalized(){var l=Length();return l<=0.00001f?Zero:new(X/l,Y/l,Z/l);}
    public static float Dot(Vector3 a,Vector3 b)=>a.X*b.X+a.Y*b.Y+a.Z*b.Z;
    public static Vector3 Cross(Vector3 a,Vector3 b)=>new(a.Y*b.Z-a.Z*b.Y,a.Z*b.X-a.X*b.Z,a.X*b.Y-a.Y*b.X);
    public static float Distance(Vector3 a,Vector3 b)=>(a-b).Length();
    public static Vector3 Lerp(Vector3 a,Vector3 b,float t)=>a+(b-a)*Math.Clamp(t,0,1);
    public static Vector3 Reflect(Vector3 v,Vector3 n)=>v-2f*Dot(v,n)*n;
    public static Vector3 Min(Vector3 a,Vector3 b)=>new(MathF.Min(a.X,b.X),MathF.Min(a.Y,b.Y),MathF.Min(a.Z,b.Z));
    public static Vector3 Max(Vector3 a,Vector3 b)=>new(MathF.Max(a.X,b.X),MathF.Max(a.Y,b.Y),MathF.Max(a.Z,b.Z));
    public static Vector3 operator +(Vector3 a,Vector3 b)=>new(a.X+b.X,a.Y+b.Y,a.Z+b.Z);
    public static Vector3 operator -(Vector3 a,Vector3 b)=>new(a.X-b.X,a.Y-b.Y,a.Z-b.Z);
    public static Vector3 operator -(Vector3 a)=>new(-a.X,-a.Y,-a.Z);
    public static Vector3 operator *(Vector3 a,float b)=>new(a.X*b,a.Y*b,a.Z*b);
    public static Vector3 operator *(float b,Vector3 a)=>a*b;
    public static Vector3 operator /(Vector3 a,float b)=>MathF.Abs(b)<1e-8f?Zero:new(a.X/b,a.Y/b,a.Z/b);
}

public readonly record struct Rect2(float X,float Y,float Width,float Height)
{
    public Vector2 Position=>new(X,Y);
    public Vector2 Size=>new(Width,Height);
    public bool Contains(Vector2 p)=>p.X>=X&&p.Y>=Y&&p.X<=X+Width&&p.Y<=Y+Height;
}

public static class Input
{
    private static readonly HashSet<string> Pressed=new(StringComparer.Ordinal);
    private static readonly Dictionary<string,float> Axes=new(StringComparer.Ordinal);
    private static readonly Dictionary<int,Vector2> Touches=new();
    private static Vector2 _mousePosition;
    public static bool IsActionPressed(string action)=>Pressed.Contains(action);
    public static bool IsActionJustPressed(string action)=>Pressed.Contains(action);
    public static bool IsActionJustReleased(string action)=>!Pressed.Contains(action);
    public static float GetAxis(string negative,string positive)=>(IsActionPressed(positive)?1f:0f)-(IsActionPressed(negative)?1f:0f);
    public static Vector2 GetVector(string left,string right,string up,string down)=>new Vector2(GetAxis(left,right),GetAxis(down,up)).Normalized();
    public static Vector2 Vector(string left,string right,string up,string down)=>GetVector(left,right,up,down);
    public static float GetAxisValue(string action)=>Axes.TryGetValue(action,out var v)?v:0f;
    public static Vector2 MousePosition=>_mousePosition;
    public static int TouchCount=>Touches.Count;
    public static Vector2 GetTouchPosition(int index)=>Touches.TryGetValue(index,out var p)?p:Vector2.Zero;
    public static bool IsTouchPressed(int index)=>Touches.ContainsKey(index);
    public static float GetGyroX()=>GetAxisValue("gyro_x");
    public static float GetGyroY()=>GetAxisValue("gyro_y");
    public static float GetGyroZ()=>GetAxisValue("gyro_z");
    public static void SetAction(string action,bool pressed){if(pressed)Pressed.Add(action);else Pressed.Remove(action);}
    public static void SetAxis(string action,float value)=>Axes[action]=value;
    public static void SetTouch(int index,Vector2 position,bool pressed){if(pressed)Touches[index]=position;else Touches.Remove(index);}
    public static void SetMousePosition(Vector2 position)=>_mousePosition=position;
}

public static class Time
{
    public static double Delta=>Engine.DeltaTime;
    public static double Now=>Engine.Time;
    public static double PhysicsDelta=>Engine.DeltaTime;
    public static int Frame=>Engine.FrameCount;
}

public static class Mathf
{
    public const float Pi=MathF.PI;
    public const float Tau=MathF.PI*2f;
    public static float Abs(float v)=>MathF.Abs(v);
    public static float Sign(float v)=>MathF.Sign(v);
    public static float Sqrt(float v)=>MathF.Sqrt(MathF.Max(0,v));
    public static float Sin(float v)=>MathF.Sin(v);
    public static float Cos(float v)=>MathF.Cos(v);
    public static float Tan(float v)=>MathF.Tan(v);
    public static float Atan2(float y,float x)=>MathF.Atan2(y,x);
    public static float Clamp(float v,float min,float max)=>Math.Clamp(v,min,max);
    public static float Clamp01(float v)=>Clamp(v,0,1);
    public static float Lerp(float a,float b,float t)=>a+(b-a)*Clamp01(t);
    public static float InverseLerp(float a,float b,float v)=>MathF.Abs(b-a)<1e-8f?0:Clamp01((v-a)/(b-a));
    public static float MoveToward(float current,float target,float maxDelta){if(MathF.Abs(target-current)<=maxDelta)return target;return current+MathF.Sign(target-current)*maxDelta;}
    public static float DegToRad(float degrees)=>degrees*(MathF.PI/180f);
    public static float RadToDeg(float radians)=>radians*(180f/MathF.PI);
    public static float Wrap(float value,float min,float max){var range=max-min;if(range<=0)return min;var x=(value-min)%range;if(x<0)x+=range;return x+min;}
    public static float SmoothStep(float from,float to,float t){if(MathF.Abs(to-from)<1e-8f)return from;t=Clamp01((t-from)/(to-from));return t*t*(3-2*t);}
    public static float PingPong(float t,float length){if(length<=0)return 0;var x=Wrap(t,0,length*2);return length-MathF.Abs(x-length);}
}

public abstract class Object
{
    public string Name{get;set;}="Object";
    public bool IsValid{get;internal set;}=true;
    public override string ToString()=>Name;
}

public class Node:Object
{
    public Node? Parent{get;private set;}
    public IReadOnlyList<Node> Children=>_children;
    private readonly List<Node> _children=new();
    private readonly HashSet<string> _groups=new(StringComparer.Ordinal);
    public bool ProcessEnabled{get;set;}=true;
    public bool PhysicsProcessEnabled{get;set;}=true;
    public bool IsInsideTree{get;internal set;}=true;
    public void AddChild(Node child){if(child==this)return;if(child.Parent!=null)child.Parent.RemoveChild(child);child.Parent=this;child.IsInsideTree=true;_children.Add(child);}
    public void RemoveChild(Node child){if(_children.Remove(child)){child.Parent=null;child.IsInsideTree=false;}}
    public T? GetNode<T>(string name) where T:Node=>Find(name) as T;
    public Node? GetNode(string name)=>Find(name);
    public Node? Find(string name){foreach(var c in _children){if(string.Equals(c.Name,name,StringComparison.Ordinal))return c;var nested=c.Find(name);if(nested!=null)return nested;}return null;}
    public void AddToGroup(string group)=>_groups.Add(group);
    public void RemoveFromGroup(string group)=>_groups.Remove(group);
    public bool IsInGroup(string group)=>_groups.Contains(group);
    public virtual void Start(){}
    public virtual void Update(float delta){}
    public virtual void PhysicsUpdate(float delta){}
    public virtual void ExitTree(){}
    public void QueueFree(){IsValid=false;ExitTree();Parent?.RemoveChild(this);}
}

public class Node3D:Node
{
    public Vector3 Position{get;set;}
    public Vector3 RotationDegrees{get;set;}
    public Vector3 Scale{get;set;}=Vector3.One;
    public Vector3 GlobalPosition{get=>Position;set=>Position=value;}
    public Vector3 GlobalRotationDegrees{get=>RotationDegrees;set=>RotationDegrees=value;}
    public Transform3D Transform=>new(Basis.Identity,Position);
    public Vector3 Forward=>DirectionFromRotation(RotationDegrees);
    public Vector3 Right=>new Vector3(MathF.Cos(Mathf.DegToRad(RotationDegrees.Y)),0f,MathF.Sin(Mathf.DegToRad(RotationDegrees.Y)));
    public Vector3 Up=>Vector3.Up;
    public bool Visible{get;set;}=true;
    public void Translate(Vector3 amount)=>Position+=amount;
    public void RotateY(float radians)=>RotationDegrees=new(RotationDegrees.X,RotationDegrees.Y+Mathf.RadToDeg(radians),RotationDegrees.Z);
    public void LookAt(Vector3 target){var d=(target-Position).Normalized();if(d.LengthSquared()<1e-8f)return;RotationDegrees=new Vector3(Mathf.RadToDeg(MathF.Asin(Math.Clamp(d.Y,-1f,1f))),Mathf.RadToDeg(MathF.Atan2(-d.X,-d.Z)),0f);}
    public T AddComponent<T>() where T:Component,new(){var c=new T{Owner=this};return c;}
    private static Vector3 DirectionFromRotation(Vector3 r){var yaw=Mathf.DegToRad(r.Y);var pitch=Mathf.DegToRad(r.X);var cp=MathF.Cos(pitch);return new Vector3(-MathF.Sin(yaw)*cp,MathF.Sin(pitch),-MathF.Cos(yaw)*cp).Normalized();}
}

public class Node2D:Node
{
    public Vector2 Position{get;set;}
    public Vector2 Scale{get;set;}=Vector2.One;
    public float RotationDegrees{get;set;}
}

public class Character3D:Node3D
{
    public Vector3 Velocity{get;set;}
    public bool IsOnFloor{get;internal set;}
    public float Gravity{get;set;}=9.81f;
    public float FloorSnapLength{get;set;}=0.2f;
    public float MaxSlopeDegrees{get;set;}=45f;
    public void ApplyGravity(float delta){if(!IsOnFloor)Velocity+=new Vector3(0,-Gravity*delta,0);}
    public virtual void MoveAndSlide(){Position+=Velocity*(float)Time.Delta;}
    public void Jump(float velocity=4.5f){if(IsOnFloor)Velocity=new Vector3(Velocity.X,velocity,Velocity.Z);}
}

public class Camera3D:Node3D
{
    public float Fov{get;set;}=70f;
    public float Near{get;set;}=0.05f;
    public float Far{get;set;}=500f;
    public bool Current{get;set;}
    public Vector2 ViewportSize{get;set;}=new(1920,1080);
    public Vector3 ProjectRayOrigin(Vector2 screen)=>Position;
    public Vector3 ProjectRayNormal(Vector2 screen)=>Forward;
}

public class Component:Object{public Node3D? Owner{get;internal set;}}

public static class Debug
{
    public static void Log(string message)=>Engine.Log(message);
    public static void Warning(string message)=>Engine.Log("WARNING: "+message);
    public static void Error(string message)=>Console.Error.WriteLine("[Noir ERROR] "+message);
}


public enum GraphicsQuality
{
    Mobile,
    High,
    Ultra,
    Extreme
}

public static class Graphics
{
    public static GraphicsQuality Quality { get; internal set; } = GraphicsQuality.Mobile;
    public static bool PbrEnabled { get; internal set; } = true;
    public static bool ShadowsEnabled { get; internal set; } = true;
    public static bool FogEnabled { get; internal set; } = true;
    public static bool BloomEnabled { get; internal set; }
    public static float Exposure { get; internal set; } = 1.1f;
    public static float RenderScale { get; internal set; } = 1f;

    public static void Configure(GraphicsQuality quality, float exposure = 1.1f, float renderScale = 1f)
    {
        Quality = quality;
        Exposure = Math.Clamp(exposure, 0.2f, 4f);
        RenderScale = Math.Clamp(renderScale, 0.5f, 1.25f);
        Engine.Log($"Graphics: {quality} / exposure {Exposure:0.00} / scale {RenderScale:0.00}");
    }
}
