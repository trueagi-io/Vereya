package io.singularitynet.MissionHandlers;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.data.DataProvider;
import net.minecraft.data.server.loottable.LootTableGenerator;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.RegistryEntryInfo;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Util;
import net.minecraft.util.math.random.RandomSeed;
import net.minecraft.util.math.random.RandomSequence;
import org.jetbrains.annotations.Nullable;
//import net.minecraft.loot.LootDataKey;
//import net.minecraft.loot.LootDataLookup;
import net.minecraft.loot.LootDataType;
import net.minecraft.loot.LootTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromBlocksDrop;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.data.DataOutput;
import net.minecraft.data.DataWriter;
import net.minecraft.data.server.loottable.LootTableProvider;
import net.minecraft.data.server.loottable.vanilla.VanillaLootTableProviders;
import net.minecraft.item.Item;
import net.minecraft.loot.LootTableReporter;
import net.minecraft.loot.context.LootContextType;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import io.singularitynet.mixin.LootTableProviderMixin;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static net.minecraft.registry.Registries.ITEM;

public class ObservationFromBlocksDropsImplementation extends HandlerBase implements IObservationProducer, ICommandHandler {

    private boolean sendRec;
    private static final Logger LOGGER = LogManager.getLogger(ObservationFromBlocksDropsImplementation.class);

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
            if (!comm[1].equalsIgnoreCase("off")) {
                LOGGER.debug("setting sendRec = true in ObservationFromBlocksDrops");
                this.sendRec = true;
                return true;
            }
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
        if (jelarray == null || jelarray.size() == 0) {
            addEntitiesToList(result, block_name, "");
            return result;
        }
        JsonObject pools = (JsonObject) jelarray.get(0);
        JsonArray entries = pools.getAsJsonArray("entries");
        JsonElement one_entry = entries.get(0);
        if (!pools.toString().contains("children")) // one item from block
        {
            String item_name = ((JsonObject) one_entry).get("name").toString().replaceAll("minecraft:|\"", "");
            //TODO: this is currently done since actually infested blocks spawn silverfish attacking player, but game
            // says some infested_cobblestone drops stone and doesn't need any tool to mine stone from it, which leads
            // to situation when agent tries to find infested_cobblestone to farm stone which is wrong behavior.
            if (block_name.contains("infested"))
                item_name = "";
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
                    ftool = ((JsonObject) regular_drop_var_fchild).getAsJsonArray("conditions").get(0).getAsJsonObject().getAsJsonObject("predicate").get("items").toString().replaceAll("minecraft:|\"|#", "");
                    if (ftool.equals("cluster_max_harvestables"))
                        ftool = "pickaxe";
                }
                if (regular_drop_var_schild.toString().contains("tool")) {
                    stool = ((JsonObject) regular_drop_var_schild).getAsJsonArray("conditions").get(0).getAsJsonObject().getAsJsonObject("predicate").get("items").toString().replaceAll("minecraft:|\"|#", "");
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

    private Dictionary<String, List<String>> parseLottable(List<LootTableProvider.LootTypeGenerator> lootTypeGenerators, Set<Identifier> lootTableIds, RegistryWrapper.WrapperLookup registryLookup) {
        MutableRegistry<LootTable> mutableRegistry = new SimpleRegistry(RegistryKeys.LOOT_TABLE, Lifecycle.experimental());
        Map<RandomSeed.XoroshiroSeed, Identifier> map = new Object2ObjectOpenHashMap();
        Dictionary<String, List<String>> result = new Hashtable<>();


        lootTypeGenerators.forEach((lootTypeGenerator) -> {
            (lootTypeGenerator.provider().apply(registryLookup)).accept((lootTable, builder) -> {
                Identifier identifier = lootTable.getValue();
                Identifier identifier2 = map.put(RandomSequence.createSeed(identifier), identifier);
                if (identifier2 != null) {
                    String var10000 = String.valueOf(identifier2);
                    Util.error("Loot table random sequence seed collision on " + var10000 + " and " + lootTable.getValue());
                }

                builder.randomSequenceId(identifier);
                LootTable lootTable2 = builder.type(lootTypeGenerator.paramSet()).build();
                mutableRegistry.add(lootTable, lootTable2, RegistryEntryInfo.DEFAULT);
            });
        });

        mutableRegistry.freeze();
        ErrorReporter.Impl impl = new ErrorReporter.Impl();
        RegistryEntryLookup.RegistryLookup registryLookup2 = (new DynamicRegistryManager.ImmutableImpl(List.of(mutableRegistry))).toImmutable().createRegistryLookup();
        LootTableReporter lootTableReporter = new LootTableReporter(impl, LootContextTypes.GENERIC, registryLookup2);
        Iterator var9 = Sets.difference(lootTableIds, mutableRegistry.getKeys()).iterator();
// todo: add a comment indicating where this decompiled code was adapted from
        while(var9.hasNext()) {
            RegistryKey<LootTable> registryKey = (RegistryKey)var9.next();
            impl.report("Missing built-in table: " + registryKey.getValue());
        }

        mutableRegistry.streamEntries().forEach((entry) -> {
            (entry.value()).validate(lootTableReporter.withContextType((entry.value()).getType()).makeChild("{" + String.valueOf(entry.registryKey().getValue()) + "}", entry.registryKey()));
        });
        Multimap<String, String> multimap = impl.getErrors();

        if (!multimap.isEmpty()) {
            multimap.forEach((name, message) -> {
                LOGGER.warn("Found validation problem in {}: {}", name, message);
            });
            throw new IllegalStateException("Failed to validate loot tables, see logs");
        } else {
            for (Map.Entry<RegistryKey<LootTable>, LootTable> entry: mutableRegistry.getEntrySet()) {
                RegistryKey<LootTable> registryKey = entry.getKey();
                LootTable lootTable = entry.getValue();
                if (!registryKey.getValue().toString().contains("block"))
                    continue;
                String block_name = registryKey.getValue().toString().replace("minecraft:blocks/", "");
                RegistryOps<JsonElement> registryOps = registryLookup.getOps(JsonOps.INSTANCE);
                JsonElement jel = LootTable.CODEC.encodeStart(registryOps, lootTable).getOrThrow();
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
        this.sendRec = false;
        LOGGER.debug("ObservationFromBlocksDrops -- start");
        Path pth = Path.of(System.getProperty("java.io.tmpdir"));
        DataOutput doutput = new DataOutput(pth);
        DataWriter writer = DataWriter.UNCACHED;
        CompletableFuture<RegistryWrapper.WrapperLookup> completableFuture = CompletableFuture.supplyAsync(BuiltinRegistries::createWrapperLookup, Util.getMainWorkerExecutor());
        LootTableProvider provider = VanillaLootTableProviders.createVanillaProvider(doutput, completableFuture);
        provider.run(writer);

        Set<Identifier> loottables = ((LootTableProviderMixin)provider).getlootTableIds();
        List<LootTableProvider.LootTypeGenerator> lootTypeGenerators = ((LootTableProviderMixin)provider).getlootTypeGenerators();
        Dictionary<String, List<String>> parsed_loottable = null;
        try {
            parsed_loottable = this.parseLottable(lootTypeGenerators, loottables, completableFuture.get());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        Registry<Item> str_ent =  MinecraftClient.getInstance().world.getRegistryManager().get(ITEM.getKey());
        List<Item> list_ent = str_ent.stream().toList();
        JsonArray triple_array = new JsonArray();
        Map<Block, Item> from_block = list_ent.get(0).BLOCK_ITEMS;
        for (Map.Entry<Block, Item> entry : from_block.entrySet()) {
            Block en_block = entry.getKey();
            Item en_item = entry.getValue();
            String block_name = en_block.getTranslationKey().replace("block.", "");
            if (Objects.equals(block_name, "minecraft.empty")) {
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
                    else if ((tag_string.contains("tool") && (tag_string.contains("needs"))))
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

            block_name = block_name.replace("minecraft.","");
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
        LOGGER.debug("ObservationFromBlocksDrops -- end");
    }
}
