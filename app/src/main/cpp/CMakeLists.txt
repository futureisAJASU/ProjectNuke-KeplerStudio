cmake_minimum_required(VERSION 3.22.1)
project(kepler_photocore)

add_library(
    kepler_photocore
    SHARED
    native_bridge.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

 target_link_libraries(
    kepler_photocore
    ${log-lib}
    ${jnigraphics-lib}
)
