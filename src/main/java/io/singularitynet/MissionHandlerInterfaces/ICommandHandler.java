package io.singularitynet.MissionHandlerInterfaces;

import io.singularitynet.projectmalmo.MissionInit;

public interface ICommandHandler {


    /**
     * @return true if this object is overriding the default Minecraft control method.
     */
    public boolean isOverriding();

    /** Switch this command handler on/off. If on, it will be overriding the default Minecraft control method.
     * @param b set this control on/off.
     */
    public void setOverriding(boolean b);

    void install(MissionInit currentMissionInit);

    void deinstall(MissionInit currentMissionInit);

    boolean execute(String command, MissionInit currentMissionInit);
}
