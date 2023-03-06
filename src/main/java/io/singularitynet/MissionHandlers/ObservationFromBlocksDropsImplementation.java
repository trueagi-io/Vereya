package io.singularitynet.MissionHandlers;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.gson.*;
import com.mojang.logging.LogUtils;
import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromBlocksDrop;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.data.DataOutput;
import net.minecraft.data.DataWriter;
import net.minecraft.data.server.loottable.LootTableProvider;
import net.minecraft.data.server.loottable.VanillaLootTableProviders;
import net.minecraft.item.Item;
import net.minecraft.loot.LootManager;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.LootTableReporter;
import net.minecraft.loot.context.LootContextType;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import io.singularitynet.mixin.LootTableProviderMixin;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static net.minecraft.registry.Registries.ITEM;

public class ObservationFromBlocksDropsImplementation extends HandlerBase implements IObservationProducer, ICommandHandler {
    private boolean sendRec;
    private static final Logger LOGGER = LogUtils.getLogger();

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
        if (comm.length == 2 && comm[0].equalsIgnoreCase(ObservationFromBlocksDrop.BLOCKDROPS.value())) {
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

    private void addEntitiesToList(List<String> result, String item_name, String tool_name) {
        result.add(item_name);
        result.add(tool_name);
    }

    private void parseChildren(JsonArray children, String tool_name, List<String> result) {
        List<String> temp_list = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            JsonObject one_drop = children.get(i).getAsJsonObject();
            String one_drop_name = one_drop.get("name").toString().replaceAll("minecraft:|\"", "");
            if (temp_list.contains(one_drop_name))
                continue;
            temp_list.add(one_drop_name);
            addEntitiesToList(result, one_drop_name, tool_name);
        }
    }

    private List<String> parseOneTable(JsonElement jel, String block_name) {
        List<String> result = new ArrayList<>();
        if (((JsonObject) jel).size() == 1) {
            addEntitiesToList(result, block_name, "");
            return result;
        }
        JsonArray jelarray = ((JsonObject) jel).getAsJsonArray("pools");
        JsonObject pools = (JsonObject) jelarray.get(0);
        JsonArray entries = pools.getAsJsonArray("entries");
        JsonElement one_entry = entries.get(0);
        if (!pools.toString().contains("children")) // one item from block
        {
            String item_name = ((JsonObject) one_entry).get("name").toString().replaceAll("minecraft:|\"", "");
            addEntitiesToList(result, item_name, "");
            return result;
        }
        JsonArray children = ((JsonObject) one_entry).getAsJsonArray("children");
        JsonElement regular_drop = children.get(1);
        if (regular_drop.toString().contains("children")) //means different random drop from same block
        {
            JsonArray regular_drop_vars = ((JsonObject) regular_drop).getAsJsonArray("children");
            if (regular_drop_vars.size() != 2) { //TODO: Only snow gets there. And snow drops relies on its level (depth). Do we need that info?
                parseChildren(regular_drop_vars, "", result);
            } else {
                JsonElement regular_drop_var_fchild = regular_drop_vars.get(0);
                JsonElement regular_drop_var_schild = regular_drop_vars.get(1);
                String first_regular_drop = ((JsonObject) regular_drop_var_fchild).get("name").toString().replaceAll("minecraft:|\"", "");
                String second_regular_drop = ((JsonObject) regular_drop_var_schild).get("name").toString().replaceAll("minecraft:|\"", "");
                String ftool = "";
                String stool = "";
                if (regular_drop_var_fchild.toString().contains("tool")) // cluster_max_harvestables = all pickaxes
                {
                    ftool = ((JsonObject) regular_drop_var_fchild).getAsJsonArray("conditions").get(0).getAsJsonObject().getAsJsonObject("predicate").get("tag").toString().replaceAll("minecraft:|\"", "");
                    if (ftool.equals("cluster_max_harvestables"))
                        ftool = "pickaxe";
                }
                if (regular_drop_var_schild.toString().contains("tool")) {
                    stool = ((JsonObject) regular_drop_var_schild).getAsJsonArray("conditions").get(0).getAsJsonObject().getAsJsonObject("predicate").get("tag").toString().replaceAll("minecraft:|\"", "");
                    if (stool.equals("cluster_max_harvestables"))
                        stool = "pickaxe";
                }
                ftool = ftool.replace("cluster_max_harvestables", "pickaxe");
                stool = stool.replace("cluster_max_harvestables", "pickaxe");
                if (first_regular_drop.equals(second_regular_drop)) {
                    if (ftool.length() > stool.length())
                        addEntitiesToList(result, first_regular_drop, ftool);
                    else
                        addEntitiesToList(result, first_regular_drop, stool);
                } else {
                    addEntitiesToList(result, first_regular_drop, ftool);
                    addEntitiesToList(result, second_regular_drop, stool);
                }
            }
        } else {
            String regular_drop_name = ((JsonObject) regular_drop).get("name").toString().replaceAll("minecraft:|\"", "");
            addEntitiesToList(result, regular_drop_name, "");
        }

        JsonElement silk_touch_drop = children.get(0);
        String selfdrop_tool = "";
        if (((JsonObject) silk_touch_drop).has("name")) {
            String selfdrop_name = ((JsonObject) silk_touch_drop).get("name").toString().replaceAll("minecraft:|\"", "");

            JsonElement selfdrop_conditions = ((JsonObject) silk_touch_drop).get("conditions");

            if (selfdrop_conditions.getAsJsonArray().get(0).getAsJsonObject().get("condition").toString().contains("alternative"))
            {
                JsonArray alternatives = selfdrop_conditions.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("terms");
                for (int i = 0; i < alternatives.size(); i++)
                {
                    JsonObject predicate = alternatives.get(i).getAsJsonObject().getAsJsonObject("predicate");
                    if (predicate.toString().contains("silk_touch"))
                        addEntitiesToList(result, selfdrop_name, "silkt_");
                    else if (predicate.toString().contains("items"))
                        addEntitiesToList(result, selfdrop_name, predicate.get("items").toString().
                                replaceAll("minecraft:|\\[|\\]|\"", ""));
                }
            }
            else {
                if (selfdrop_conditions.toString().contains("silk_touch"))
                    selfdrop_tool = "silkt_" + selfdrop_tool;
                if (selfdrop_conditions.toString().contains("items")) {
                    if (selfdrop_conditions.getAsJsonArray().get(0).toString().contains("terms")) {
                        selfdrop_tool = selfdrop_tool + selfdrop_conditions.getAsJsonArray().get(0).getAsJsonObject().
                                getAsJsonArray("terms").get(0).getAsJsonObject().
                                getAsJsonObject("predicate").get("items").toString().
                                replaceAll("minecraft:|\\[|\\]|\"", "");
                    } else {
                        selfdrop_tool = selfdrop_tool + selfdrop_conditions.getAsJsonArray().get(0).getAsJsonObject().
                                getAsJsonObject("predicate").get("items").toString().
                                replaceAll("minecraft:|\\[|\\]|\"", "");
                    }
                } else //TODO: there is a block named "wheat" which drops relies on its age. Do we need that info?
                {
                    int stub = 0; //stub since nothing here need to be done for anything but "wheat".
                }
                addEntitiesToList(result, selfdrop_name, selfdrop_tool);
            }
        } else //TODO: only snow goes there, Its is random number of snowballs. Do we need info about random count?
        {
            if (silk_touch_drop.toString().contains("silk_touch"))
                selfdrop_tool = "silkt_" + selfdrop_tool;
            JsonArray selfdrop_children = ((JsonObject) silk_touch_drop).getAsJsonArray("children");
            parseChildren(selfdrop_children, selfdrop_tool, result);
        }
        return result;
    }

    private Dictionary<String, List<String>> parseLottable(List<LootTableProvider.LootTypeGenerator> lootTypeGenerators, Set<Identifier> lootTableIds) {
        Dictionary<String, List<String>> result = new Hashtable<>();
        Map<Identifier, LootTable> map = Maps.newHashMap();
        lootTypeGenerators.forEach((lootTypeGenerator) -> lootTypeGenerator.provider().get().accept((id, builder) -> {
            if (map.put(id, builder.type(lootTypeGenerator.paramSet()).build()) != null) {
                throw new IllegalStateException("Duplicate loot table " + id);
            }
        }));
        LootContextType var10002 = LootContextTypes.GENERIC;
        Function var10003 = (id) -> null;
        Objects.requireNonNull(map);
        LootTableReporter lootTableReporter = new LootTableReporter(var10002, var10003, map::get);
        Set<Identifier> set = Sets.difference(lootTableIds, map.keySet());
        Iterator var5 = set.iterator();

        while (var5.hasNext()) {
            Identifier identifier = (Identifier) var5.next();
            lootTableReporter.report("Missing built-in table: " + identifier);
        }

        map.forEach((id, table) -> LootManager.validate(lootTableReporter, id, table));
        Multimap<String, String> multimap = lootTableReporter.getMessages();
        if (!multimap.isEmpty()) {
            multimap.forEach((name, message) -> {
                LOGGER.warn("Found validation problem in {}: {}", name, message);
            });
            throw new IllegalStateException("Failed to validate loot tables, see logs");
        } else {
            for (Map.Entry<Identifier, LootTable> entry : map.entrySet()) {
                Identifier identifier = entry.getKey();
                if (!identifier.toString().contains("block"))
                    continue;
                String block_name = identifier.toString().replace("minecraft:blocks/", "");
                System.out.print(block_name + "\n");
                LootTable loottable = entry.getValue();
                JsonElement jel = LootManager.toJson(loottable);
                List<String> parsed_one_table = this.parseOneTable(jel, block_name);
                result.put(block_name, parsed_one_table);
            }
        }
        return result;
    }

    private void updateTripleArray(JsonArray triple_array, String block_name, String item_name, String tool_name)
    {
        JsonObject triple = new JsonObject();
        triple.add("block_name", new JsonPrimitive(block_name));
        triple.add("item_name", new JsonPrimitive(item_name));
        if (tool_name.equals(""))
            triple.add("tool", new JsonPrimitive("None"));
        else if (tool_name.equals("silkt_"))
            triple.add("tool", new JsonPrimitive("silkt_AnyTool"));
        else
            triple.add("tool", new JsonPrimitive(tool_name));
        triple_array.add(triple);
    }

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit currentMissionInit) {
        if (!this.sendRec){
            return;
        }
        Path pth = Path.of("/home/daddywesker/Untitled");
        DataOutput doutput = new DataOutput(pth);
        DataWriter writer = DataWriter.UNCACHED;
        LootTableProvider provider = VanillaLootTableProviders.createVanillaProvider(doutput);
        provider.run(writer);

        Set<Identifier> loottables = ((LootTableProviderMixin)provider).getlootTableIds();
        List<LootTableProvider.LootTypeGenerator> lootTypeGenerators = ((LootTableProviderMixin)provider).getlootTypeGenerators();
        Dictionary<String, List<String>> parsed_loottable = this.parseLottable(lootTypeGenerators, loottables);

        Registry<Item> str_ent =  MinecraftClient.getInstance().world.getRegistryManager().get(ITEM.getKey());
        List<Item> list_ent = str_ent.stream().toList();
        JsonArray triple_array = new JsonArray();
        Map<Block, Item> from_block = list_ent.get(0).BLOCK_ITEMS;
        for (Map.Entry<Block, Item> entry : from_block.entrySet()) {
            Block en_block = entry.getKey();
            Item en_item = entry.getValue();
            String block_name = en_block.getLootTableId().toString();
            if (Objects.equals(block_name, "minecraft:empty")) {
                continue;
            }

            boolean is_tool_required = en_block.getDefaultState().isToolRequired();
            String tool_name = "";
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
                        tool_t = tag_string.split("/")[1];
                    }
                    else if (tag_string.contains("tool"))
                    {
                        tool_q = tag_string.split("_")[1];
                        wooden_required = false;
                    }
                }
                if (wooden_required)
                    tool_q = "wooden";
                if (!tool_q.equals("") && tool_t.equals(""))
                    tool_name = tool_q + "_" + "AnyTool";
                else
                    tool_name = tool_q+"_"+tool_t;
            }

            block_name = block_name.split("/")[1];
            if (((Hashtable)parsed_loottable).containsKey(block_name))
            {
                List<String> p_items = parsed_loottable.get(block_name);
                for (int i = 0 ; i < p_items.size(); i+=2)
                {
                    String item_name = p_items.get(i);
                    String tool_suff = p_items.get(i+1);
                    if (tool_suff.equals("") || tool_suff.equals("silkt_"))
                        updateTripleArray(triple_array, block_name, item_name, tool_suff+tool_name);
                    else if (tool_suff.equals("pickaxe") && tool_name.equals(""))
                    {
                        updateTripleArray(triple_array, block_name, item_name, "AnyTool");
                    }
                    else
                        updateTripleArray(triple_array, block_name, item_name, tool_suff);
                }
            }
            else
            {
                String item_name = en_item.toString();
                updateTripleArray(triple_array, block_name, item_name, tool_name);
            }
        }
        json.add("block_item_tool_triple", triple_array);
    }
}
