package io.singularitynet.MissionHandlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registry;

import io.singularitynet.projectmalmo.ObservationFromItem;


import java.util.List;
import static net.minecraft.registry.Registries.ITEM;

public class ObservationFromItemsImplementation extends HandlerBase implements IObservationProducer, ICommandHandler {
    private boolean sendRec;

    @Override
    public boolean isOverriding() {
        return false;
    }

    @Override
    public void setOverriding(boolean b) {}

    @Override
    public void install(MissionInit currentMissionInit) { sendRec = false; }

    @Override
    public void deinstall(MissionInit currentMissionInit) {}

    @Override
    public boolean execute(String command, MissionInit currentMissionInit) {
        String comm[] = command.split(" ", 2);
        if (comm.length == 2 && comm[0].equalsIgnoreCase(ObservationFromItem.ITEM_LIST.value())){
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
    public void cleanup() {}

    @Override
    public void prepare(MissionInit missionInit) {}

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit currentMissionInit) {
        if (!this.sendRec){
            return;
        }
        Registry<Item> str_ent =  MinecraftClient.getInstance().world.getRegistryManager().get(ITEM.getKey());
        List<Item> list_ent = str_ent.stream().toList();
        JsonArray items = new JsonArray();
        for (Item ent: list_ent)
        {
            String item_name = ent.toString();
            items.add(item_name);
        }
        json.add("item_list", items);
//
    }
}
