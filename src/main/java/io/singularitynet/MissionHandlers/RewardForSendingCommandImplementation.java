package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IRewardProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.RewardForSendingCommand;

import java.math.BigDecimal;

public class RewardForSendingCommandImplementation extends HandlerBase implements IRewardProducer {

    BigDecimal reward;
    String distribution;
    Integer dimension;
    boolean triggered;

    public RewardForSendingCommandImplementation(){
        this.reward = null;
        this.distribution = null;
        this.dimension = null;
        this.triggered = false;
    }

    public void triggerFlag(){
        this.triggered = true;
    }

    public void resetFlag(){
        this.triggered = false;
    }

    public boolean isTriggered(){
        return this.triggered;
    }

    @Override
    public boolean parseParameters(Object params){
        if (params == null || !(params instanceof RewardForSendingCommand)) {
            return false;
        }
        RewardForSendingCommand rscparams = (RewardForSendingCommand)params;
        this.reward = rscparams.getReward();
        this.distribution = rscparams.getDistribution();
        this.dimension = rscparams.getDimension();
        return true;
    }

    @Override
    public void cleanup() {

    }

    @Override
    public void prepare(MissionInit missionInit) {

    }

    @Override
    public void getReward(MultidimensionalReward reward) {
        if (this.isTriggered()){
            reward.add(this.dimension, this.reward.floatValue());
            this.resetFlag();
        }
    }

    @Override
    public void produceReward(Class<? extends IRewardProducer> clazz) {
        if (clazz.isInstance(this)){
            this.triggerFlag();
        }
    }
}
