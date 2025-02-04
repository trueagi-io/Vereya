package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IRewardProducer;
import io.singularitynet.projectmalmo.MissionInit;

import java.util.ArrayList;

public class RewardGroup extends HandlerBase implements IRewardProducer{

    private ArrayList<IRewardProducer> handlers;
    private boolean shareParametersWithChildren = false;

    public RewardGroup(){ this.handlers = new ArrayList<IRewardProducer>();}

    protected void setShareParametersWithChildren(boolean share)
    {
        this.shareParametersWithChildren = share;
    }

    public void addRewardProducer(IRewardProducer handler){
        this.handlers.add(handler);
    }

    public boolean isFixed(){
        return false;
    }

    @Override
    public void setParentBehaviour(MissionBehaviour mb)
    {
        super.setParentBehaviour(mb);
        for (IRewardProducer han : this.handlers)
            ((HandlerBase)han).setParentBehaviour(mb);
    }

    @Override
    public boolean parseParameters(Object params) {
        // Normal handling:
        boolean ok = super.parseParameters(params);

        // Now, pass the params to each child handler, if that was requested:
        if (this.shareParametersWithChildren) {
            // AND the results, but without short-circuit evaluation.
            for (IRewardProducer han : this.handlers) {
                if (han instanceof HandlerBase) {
                    ok &= ((HandlerBase) han).parseParameters(params);
                }
            }
        }
        return ok;
    }

    @Override
    public void cleanup() {

    }

    @Override
    public void prepare(MissionInit missionInit) {

    }

    @Override
    public void getReward(MultidimensionalReward reward) {
        for (IRewardProducer han : this.handlers){
            han.getReward(reward);
        }
    }

    @Override
    public void trigger(Class<? extends IRewardProducer> clazz) {
        for (IRewardProducer han : this.handlers){
            han.trigger(clazz);
        }
    }
}
