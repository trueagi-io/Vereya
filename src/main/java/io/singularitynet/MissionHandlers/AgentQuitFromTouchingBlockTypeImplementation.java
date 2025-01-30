package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IWantToQuit;
import io.singularitynet.projectmalmo.AgentQuitFromTouchingBlockType;
import io.singularitynet.projectmalmo.BlockSpecWithDescription;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;

import java.util.List;

public class AgentQuitFromTouchingBlockTypeImplementation extends HandlerBase implements IWantToQuit {
    @Override
    public void cleanup() {

    }

    @Override
    public boolean doIWantToQuit(MissionInit currentMissionInit) {
        String blockType = getBlockUnderPlayer();
        if (blockType == null || blockType.isEmpty()){
            return true;
        }
        List<BlockSpecWithDescription> blocks = getQuitBlocks(currentMissionInit);
        if (blocks == null || blocks.isEmpty()){
            return false;
        }
        for (BlockSpecWithDescription block : blocks){
            String quitBlock = block.getType().getFirst().toString().toLowerCase();
            if (blockType.equals(quitBlock)){
                return true;
            }
        }
        return false;

    }

    @Override
    public String getOutcome() {
        String block = getBlockUnderPlayer();
        return "Agent touched " + block;
    }

    @Override
    public void prepare(MissionInit missionInit) {

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

    private List<BlockSpecWithDescription> getQuitBlocks(MissionInit currentMissionInit) {
        AgentQuitFromTouchingBlockType agentQuit = currentMissionInit.getMission().getAgentSection().getFirst().getAgentHandlers().getAgentMissionHandlers().stream().filter(AgentQuitFromTouchingBlockType.class::isInstance).map(AgentQuitFromTouchingBlockType.class::cast).toList().getFirst();
        return agentQuit.getBlock();
    }
}
