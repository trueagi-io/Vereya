package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;

import java.util.ArrayList;

public class CommandGroup {
    private ArrayList<ICommandHandler> handlers;
    private boolean isOverriding = false;

    void addCommandHandler(ICommandHandler handler)
    {
        if (handler != null)
        {
            this.handlers.add(handler);
            handler.setOverriding(this.isOverriding);
        }
    }
    public boolean isFixed()
    {
        return false;   // Return true to stop MissionBehaviour from adding new handlers to this group.
    }
}
