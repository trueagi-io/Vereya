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
              


