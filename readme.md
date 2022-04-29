## Overview

Vereya provides api for control of agent in Minecraft. Mod is a fork of Malmo project. 
Vereya should work with Minecraft 1.18.+

## building and installation

run in project directory

`./gradlew build`

copy mod in mods directory:

`cp ./build/libs/vereya-*.jar ~/.minecraft/mods/`

now build python library

`mkdir p build && cd build && cmake .. && make`

python library is in ./build/Malmo/src/PythonWrapper/MalmoPython.so

It can be place somewhere in $PYTHONPATH **or** to the directory of your script..

Vereya requires fabric loader and fabric api to installed. Please see instructions

https://fabricmc.net/use/installer/

https://www.curseforge.com/minecraft/mc-mods/fabric-api


