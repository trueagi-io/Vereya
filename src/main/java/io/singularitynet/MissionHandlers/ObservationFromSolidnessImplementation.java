package io.singularitynet.MissionHandlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromSolid;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registry;

import java.util.List;

import static net.minecraft.registry.Registries.BLOCK;

public class ObservationFromSolidnessImplementation extends HandlerBase implements IObservationProducer, ICommandHandler {

    private boolean sendRec;

    @Override
    public boolean isOverriding() {return false;}

    @Override
    public void setOverriding(boolean b) {}

    @Override
    public void install(MissionInit currentMissionInit) {sendRec = false;}

    @Override
    public void deinstall(MissionInit currentMissionInit) {}

    @Override
    public boolean execute(String command, MissionInit currentMissionInit) {
        String comm[] = command.split(" ", 2);
        if (comm.length == 2 && comm[0].equalsIgnoreCase(ObservationFromSolid.SOLID.value()) &&
                !comm[1].equalsIgnoreCase("off")) {
            this.sendRec = true;
            return true;
        }
        if (comm.length == 2 && comm[0].equalsIgnoreCase(ObservationFromSolid.SOLID.value()) &&
                comm[1].equalsIgnoreCase("off")
        ) {
            this.sendRec = false;
            return true;
        }
        return false;
    }

    @Override
    public void cleanup() {}

    @Override
    public void prepare(MissionInit missionInit) {}

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit currentMissionInit) {
        if (!this.sendRec){
            return;
        }
        Registry<Block> blocks = MinecraftClient.getInstance().world.getRegistryManager().get(BLOCK.getKey());
        List<Block> list_blocks = blocks.stream().toList();
        JsonArray nonsolid_blocks = new JsonArray();
        for (Block ent: list_blocks)
        {
            if (!ent.getDefaultState().blocksMovement())
            {
                String item_name = ent.toString().replaceAll("Block|minecraft:|\\{|\\}", "");
                nonsolid_blocks.add(item_name);
            }
        }
        json.add("nonsolid_blocks", nonsolid_blocks);
    }
}
