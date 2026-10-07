#include "noir_world_scene.h"
#include <cmath>

namespace noir::world {

void buildDefaultWorld(std::vector<Instance>& out){
    out.clear();
    out.reserve(32);

    Instance terrain;
    terrain.x=0;terrain.y=-0.25f;terrain.z=0;
    terrain.sx=18;terrain.sy=0.35f;terrain.sz=18;terrain.kind=16;
    out.push_back(terrain);

    Instance water;
    water.x=0;water.y=-0.18f;water.z=6;
    water.sx=10;water.sy=0.08f;water.sz=8;water.kind=15;
    out.push_back(water);

    const float rocks[][4]={
        {-8,0.55f,-5,1.6f},{-4,0.45f,-7,1.1f},{7,0.75f,-6,1.8f},
        {10,0.42f,1,1.0f},{-9,0.62f,7,1.5f},{5,0.55f,8,1.4f}
    };
    for(auto &r:rocks){
        Instance n;
        n.x=r[0];n.y=r[1];n.z=r[2];
        n.sx=r[3];n.sy=r[3]*0.62f;n.sz=r[3]*0.9f;n.kind=36;
        out.push_back(n);
    }

    const float trees[][4]={
        {-11,2.7f,-10,1.4f},{-5,3.0f,-11,1.25f},{2,3.4f,-10,1.5f},
        {10,3.0f,-10,1.3f},{13,2.6f,-2,1.15f},{-12,2.9f,2,1.3f},
        {-9,3.1f,10,1.35f},{2,3.2f,11,1.4f},{11,2.9f,8,1.2f}
    };
    for(auto &t:trees){
        Instance n;
        n.x=t[0];n.y=t[1];n.z=t[2];
        n.sx=n.sz=t[3];n.sy=t[3]*1.8f;n.kind=17;
        out.push_back(n);
    }

    for(int i=0;i<12;i++){
        float a=6.28318530718f*float(i)/12.0f;
        Instance n;
        n.x=std::cos(a)*6.5f;n.y=0.12f;n.z=std::sin(a)*6.5f;
        n.sx=n.sz=0.7f+0.18f*float((i*7)%5)/4.0f;n.sy=1.2f;n.kind=17;
        out.push_back(n);
    }

    Instance statue;
    statue.x=0;statue.y=1.3f;statue.z=0;
    statue.sx=1.1f;statue.sy=1.35f;statue.sz=1.1f;statue.kind=5;
    out.push_back(statue);
}

} // namespace noir::world
