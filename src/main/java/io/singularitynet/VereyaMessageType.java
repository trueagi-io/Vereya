package io.singularitynet;

public enum VereyaMessageType
{
    SERVER_NULLMESSASGE,
    SERVER_ALLPLAYERSJOINED,
    SERVER_GO,                  // All clients are running, server is running - GO!
    SERVER_STOPAGENTS,			// Server request for all agents to stop what they are doing (mission is over)
    SERVER_MISSIONOVER,			// Server informing that all agents have stopped, and the mission is now over.
    SERVER_OBSERVATIONSREADY,
    SERVER_TEXT,
    SERVER_STOPPED,
    SERVER_ABORT,
    SERVER_COLLECTITEM,
    SERVER_DISCARDITEM,
    SERVER_BUILDBATTLEREWARD,   // Server has detected a reward from the build battle
    SERVER_SHARE_REWARD,        // Server has received a reward from a client and is distributing it to the other agents
    SERVER_YOUR_TURN,           // Server turn scheduler is telling client that it is their go next
    SERVER_SOMEOTHERMESSAGE,
    SERVER_CONTROLLED_MOB,
    CLIENT_AGENTREADY,			// Client response to server's ready request
    CLIENT_AGENTRUNNING,		// Client has just started running
    CLIENT_AGENTSTOPPED,		// Client response to server's stop request
    CLIENT_AGENTFINISHEDMISSION,// Individual agent has finished a mission
    CLIENT_BAILED,				// Client has hit an error and been forced to enter error state
    CLIENT_SHARE_REWARD,        // Client has received a reward and needs to share it with other agents
    CLIENT_TURN_TAKEN,          // Client is telling the server turn scheduler that they have just taken their turn
    CLIENT_SOMEOTHERMESSAGE,
    CLIENT_CRAFT, // Client telling the server what to craft
    CLIENT_INVENTORY_CHANGE, // Client tells server to modify inventory
    CLIENT_MISSION_INIT, // Client tells server to start a new mission
    CLIENT_MOVE  // move command for controlled mob
}