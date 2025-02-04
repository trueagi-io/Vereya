package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IRewardProducer;
import io.singularitynet.projectmalmo.BlockSpecWithDescription;
import io.singularitynet.projectmalmo.BlockSpecWithRewardAndBehaviour;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.RewardForTouchingBlockType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class RewardForTouchingBlockTypeImplementation extends HandlerBase implements IRewardProducer {

    HashMap<String, BlockSpecWithRewardAndBehaviour> rewardBlocks;
    HashSet<String> onceOnlyBlocks;

    public RewardForTouchingBlockTypeImplementation(){
        this.rewardBlocks = new HashMap<String, BlockSpecWithRewardAndBehaviour>();
        this.onceOnlyBlocks = new HashSet<String>();
    }

    @Override
    public void cleanup() {

    }

    @Override
    public boolean parseParameters(Object params){
        if (params == null || !(params instanceof RewardForTouchingBlockType)) {
            return false;
        }
        RewardForTouchingBlockType rtbparams = (RewardForTouchingBlockType)params;
        List<BlockSpecWithRewardAndBehaviour> blocks = rtbparams.getBlock();
        if (this.rewardBlocks == null) this.rewardBlocks = new HashMap<String, BlockSpecWithRewardAndBehaviour>();
        for (BlockSpecWithRewardAndBehaviour block : blocks){
            String blockType = block.getType().getFirst().toString().toLowerCase();
            this.rewardBlocks.put(blockType, block);
        }
        return true;
    }

    @Override
    public void prepare(MissionInit missionInit) {

    }

    @Override
    public void getReward(MultidimensionalReward reward) {

    }

    @Override
    public void produceReward(Class<? extends IRewardProducer> clazz) {

    }
}
