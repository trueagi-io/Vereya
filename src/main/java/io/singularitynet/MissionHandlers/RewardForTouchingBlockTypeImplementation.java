package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IRewardProducer;
import io.singularitynet.projectmalmo.BlockSpecWithRewardAndBehaviour;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.RewardForTouchingBlockType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class RewardForTouchingBlockTypeImplementation extends HandlerBase implements IRewardProducer {

    HashMap<String, BlockSpecWithRewardAndBehaviour> rewardBlocks;
    HashSet<String> onceOnlyBlocks;
    Integer dimension;

    public RewardForTouchingBlockTypeImplementation(){
        this.rewardBlocks = new HashMap<String, BlockSpecWithRewardAndBehaviour>();
        this.onceOnlyBlocks = new HashSet<String>();
        this.dimension = 0;
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
        this.dimension = rtbparams.getDimension();
        return true;
    }

    @Override
    public void prepare(MissionInit missionInit) {

    }

    @Override
    public void getReward(MultidimensionalReward reward) {
        String blockType = getBlockUnderPlayer();
        if (blockType.isEmpty() || !(this.rewardBlocks.containsKey(blockType))) return;
        BlockSpecWithRewardAndBehaviour rewardBlock = this.rewardBlocks.get(blockType);
        //handle onceOnly behaviour
        String behav = rewardBlock.getBehaviour().value();
        if (rewardBlock.getBehaviour().value().equals("onceOnly")){
            if (this.onceOnlyBlocks.contains(blockType)) return;
            this.onceOnlyBlocks.add(blockType);
        }
        reward.add(this.dimension, rewardBlock.getReward().floatValue());
    }

    @Override
    public void trigger(Class<?> clazz) {

    }

    private String getBlockUnderPlayer(){
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null){
            LogManager.getLogger().error("player is null");
            return "";
        }
        Vec3d playerPos = player.getPos();
        //substract 0.5 because agent can be standing on a slab
        BlockPos blockPos = BlockPos.ofFloored(playerPos.subtract(0, 0.5, 0));
        String blockType = player.getWorld().getBlockState(blockPos).getBlock().getTranslationKey().toLowerCase();
        String[] parts = blockType.split("\\.", 3);
        blockType = (parts.length > 2) ? parts[2] : "";
        return blockType;
    }
}
