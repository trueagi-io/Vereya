# architecture and api overview

Vereya consists of two parts: java mod that works in minecraft process
and client library VereyaPython.so which communicates with the mod.

High-level api for interaction with the client library  
https://github.com/trueagi-io/minecraft-demo/tree/main/tagilmo

These classes are used to control and query the game:

* AgentHost
* WorldState
* MissionSpec
* ClientPool
* TimestampedVideoFrame

### AgentHost
main interface to the mod, used to start and set up the game

### MissionSpec
Determines minecraft world settings, and which of commands and observations will be available during the mission.

Commands and observations are defined in __AgentHandlers__ section of Mission xml string. 
see [Mission.xsd](src/main/resources/Schemas/Mission.xsd) for xml structure

**observations:**  
ObservationFromFullStats  
ObservationFromHotBar  
ObservationFromFullInventory  
ObservationFromGrid  
ObservationFromNearbyEntities  
ObservationFromRay  

VideoProducer

**commands:**  
ContinuousMovementCommands  
InventoryCommands  
ChatCommands  
SimpleCraftCommand  
MissionQuitCommands  


### WorldState
* is_mission_running: bool  
* has_mission_begun: bool  
* observations: dict  
contains the last observations except video frame and rewards
* rewards: float  
* video_frames: TimestampedVideoFrame  
contains the last video frame


### TimestampedVideoFrame
timestamp  
width  
height        
channels      
xPos     
yPos        
zPos        
yaw       
pitch       
frametype  - int, 0 RGB, 1 depth, 2 LUMINANCE
modelViewMatrix  - opengl model-view matrix
calibrationMatrix  - opengl perspective projection matrix
pixels
              

### ObservationFromRay

Observation from ray creates LineOfSight field in the world data.

Fields:

"hitType": "MISS", "BLOCK", "ENTITY"  
"type": - entity of block id "sheep", "dirt" etc  
if hitType is "BLOCK" then json will contain some of the properties from this file
https://maven.fabricmc.net/docs/yarn-1.18+build.1/net/minecraft/state/property/Properties.html  
"x"  
"y"  
"z" - corresponding coordinates  
"distance" - distance from camera(not from the player's body!!) to the hit point  
"inRange" - boolean, true if distance < 4.5 or 6 if game is run with extended reach

###ObservationFromNearbyEntities

worldState will contain <range name>: <array of entities> pairs

Entity' properties:  
"yaw"  
"x"  
"y"  
"z"  
"pitch"  
"id"  - internal in-game id
"type"  - i.g. "cow"
"motionX"  
"motionY"  
"motionZ"  
"name"  - empty if it's a mob


###ObservationFromGrid

Returns cubic block grid centred on the player.  
Default is 3x3x3. (One cube all around the player.)  
Blocks are returned as a 1D array, in order
along the x, then z, then y axes.  
Data will be returned in an array called "Cells"
each element of array is a string = type of the block

###ObservationFromFullInventory

Returns array named "inventory".  
Each element of the array has properties:  

"type"  - item's name  
"index" - it's cell in the inventory, cells 0-9 are from the hotbar  
"quantity" -  
"inventory" - inventory's name

###ObservationFromHotBar

The same as ObservationFromFullInventory, but limited to the hotbar cells

###ObservationFromFullStats

adds player's stats to the json

"Life"  
"Score"  
"Food"  
"XP"  
"Air"  
"Name" 

"XPos"  
"YPos"  
"ZPos"  
"Pitch"  
"Yaw"  

"WorldTime"
