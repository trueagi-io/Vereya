![CI](https://github.com/trueagi-io/Vereya/actions/workflows/build.yml/badge.svg)

## Overview  

Vereya provides API for controlling AI agents in Minecraft. This mod inherits some pieces of the Malmo project, but with various changes and additions. 
Vereya should work with Minecraft 1.21

## building and installation

*set pauseOnLostFocus:false* in options.txt

run in project directory

`./gradlew build`

copy mod in mods directory:

`cp ./build/libs/vereya-*.jar ~/.minecraft/mods/`

now install python library:

pip install git+https://github.com/trueagi-io/minecraft-demo.git

Vereya requires fabric loader and fabric api to installed. Please see instructions

https://fabricmc.net/use/installer/

https://www.curseforge.com/minecraft/mc-mods/fabric-api


