cmake_minimum_required(VERSION 3.22.1)
project(noir3d)

add_library(noir3d SHARED
    noir3d.cpp
)

target_compile_features(noir3d PRIVATE cxx_std_17)
target_compile_options(noir3d PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)

find_library(log-lib log)
target_link_libraries(noir3d PRIVATE ${log-lib})

set_target_properties(noir3d PROPERTIES
    OUTPUT_NAME "noir3d"
)
