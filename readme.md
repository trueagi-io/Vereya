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

The library needs two environment varibles to be defined:  
1) MALMO_XSD_PATH pointing to the directory with xml schema files from Malmo project 
2) VEREYA_XSD_PATH pointing to ./src/main/resources/Schemas/


It can be place somewhere in $PYTHONPATH **or** to the directory of your script..

Vereya requires fabric loader and fabric api to installed. Please see instructions

https://fabricmc.net/use/installer/

https://www.curseforge.com/minecraft/mc-mods/fabric-api

api docs [api.md](api.md)


