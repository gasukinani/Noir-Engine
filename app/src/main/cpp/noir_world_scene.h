#pragma once
#include <vector>

namespace noir::world {

struct Instance {
    float x=0,y=0,z=0;
    float sx=1,sy=1,sz=1;
    float rx=0,ry=0,rz=0;
    int kind=5;
};

void buildDefaultWorld(std::vector<Instance>& out);

} // namespace noir::world
