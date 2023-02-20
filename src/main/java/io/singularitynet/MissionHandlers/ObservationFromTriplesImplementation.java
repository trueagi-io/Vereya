package io.singularitynet.MissionHandlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromTriple;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.TagKey;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static net.minecraft.registry.Registries.ITEM;

public class ObservationFromTriplesImplementation extends HandlerBase implements IObservationProducer, ICommandHandler {
    private boolean sendRec;

    @Override
    public boolean isOverriding() {
        return false;
    }

    @Override
    public void setOverriding(boolean b) {

    }

    @Override
    public void install(MissionInit currentMissionInit) {
        sendRec = false;
    }

    @Override
    public void deinstall(MissionInit currentMissionInit) {

    }

    @Override
    public boolean execute(String command, MissionInit currentMissionInit) {
        String comm[] = command.split(" ", 2);
        if (comm.length == 2 && comm[0].equalsIgnoreCase(ObservationFromTriple.TRIPLES.value())){
            if (comm[1].equalsIgnoreCase("off")) {
                this.sendRec = false;
            } else {
                this.sendRec = true;
            }
            return true;
        }
        return false;
    }

    @Override
    public void cleanup() {

    }

    @Override
    public void prepare(MissionInit missionInit) {

    }

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit currentMissionInit) {
        if (!this.sendRec){
            return;
        }
        Registry<Item> str_ent =  MinecraftClient.getInstance().world.getRegistryManager().get(ITEM.getKey());
        List<Item> list_ent = str_ent.stream().toList();
        JsonArray triple_array = new JsonArray();
        Map<Block, Item> from_block = list_ent.get(0).BLOCK_ITEMS;
        for (Map.Entry<Block, Item> entry : from_block.entrySet()) {
            Block en_block = entry.getKey();
            Item en_item = entry.getValue();
            String block_name = en_block.getLootTableId().toString();
            System.out.print("trying to split block_name "+block_name+" using '/' \n");
            if (Objects.equals(block_name, "minecraft:empty")) {
                continue;
            }
            block_name = block_name.split("/")[1];
            String item_name = en_item.toString();
            JsonObject triple = new JsonObject();
            triple.add("block_name", new JsonPrimitive(block_name));
            triple.add("item_name", new JsonPrimitive(item_name));
            boolean is_tool_required = en_block.getDefaultState().isToolRequired();
            if (is_tool_required)
            {
                boolean wooden_required = true;
                String tool_q = "";
                String tool_t = "";
                List<TagKey<Block>> block_tags = en_block.getRegistryEntry().streamTags().toList();
                for (TagKey<Block> block_tag: block_tags)
                {
                    String tag_string = block_tag.id().toString();
                    if (tag_string.contains("mineable"))
                    {
//                        System.out.print("trying to split tag "+tag_string+" using '/' \n");
                        tool_t = tag_string.split("/")[1];
                    }
                    else if (tag_string.contains("tool"))
                    {
//                        System.out.print("trying to split tag "+tag_string+" using '_' \n");
                        tool_q = tag_string.split("_")[1];
                        wooden_required = false;
                    }
                }
                if (wooden_required)
                    tool_q = "wooden";
                triple.add("tool", new JsonPrimitive(tool_q+"_"+tool_t));
            }
            else
                triple.add("tool", new JsonPrimitive("None"));
            triple_array.add(triple);
        }
        json.add("block_item_tool_triple", triple_array);
    }
}
