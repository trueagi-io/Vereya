package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IWantToQuit;
import io.singularitynet.projectmalmo.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;

import java.util.HashSet;
import java.util.List;

public class AgentQuitFromTouchingBlockTypeImplementation extends HandlerBase implements IWantToQuit {

    HashSet<String> qbSet;

    @Override
    public void cleanup() {

    }


    @Override
    public String getOutcome() {
        String block = getBlockUnderPlayer();
        return "Agent touched " + block;
    }

    @Override
    public void prepare(MissionInit missionInit) {

    }

    @Override
    public boolean parseParameters(Object params){
        if (params == null || !(params instanceof AgentQuitFromTouchingBlockType)) {
            return false;
        }
        AgentQuitFromTouchingBlockType aqparams = (AgentQuitFromTouchingBlockType)params;
        List<BlockSpecWithDescription> quitBlocks = aqparams.getBlock();
        this.qbSet = new HashSet<String>();
        if(!(quitBlocks == null || quitBlocks.isEmpty())){
            for (BlockSpecWithDescription quitBlock : quitBlocks){
                String blockType = quitBlock.getType().getFirst().toString().toLowerCase();
                this.qbSet.add(blockType);
            }
        }
        return true;
    }
    @Override
    public boolean doIWantToQuit(MissionInit currentMissionInit) {
        String blockType = getBlockUnderPlayer();
        if (blockType == null || blockType.isEmpty()){
            return true;
        }
        if (this.qbSet == null || this.qbSet.isEmpty()){
            return false;
        }
        return this.qbSet.contains(blockType);
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
