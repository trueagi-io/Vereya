package io.singularitynet.MissionHandlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.utils.JSONWorldDataHelper;
import io.singularitynet.projectmalmo.GridDefinition;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromFindBlock;
import io.singularitynet.projectmalmo.ObservationFromFindBlck;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ObservationFromFindBlockImplementation extends HandlerBase implements IObservationProducer, ICommandHandler {
    private List<ObservationFromFindBlockImplementation.SimpleGridDef> environs = null;
    private boolean sendRec;
    private String block_name = "";

    @Override
    public void cleanup() {}

    @Override
    public void prepare(MissionInit missionInit) {}

    private void findNearestBlockInGrid(JsonObject json, JSONWorldDataHelper.GridDimensions environmentDimensions,
                                        PlayerEntity player, String jsonName, String block_name)
    {
        if (player == null || json == null)
            return;

        BlockPos pos = new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        BlockPos nearestBlock = new BlockPos(player.getBlockX() + environmentDimensions.xMax + 1,
                                             player.getBlockY() + environmentDimensions.yMax + 1,
                                             player.getBlockZ() + environmentDimensions.zMax + 1);
        boolean foundBlock = false;
        for (int y = environmentDimensions.yMin; y <= environmentDimensions.yMax; y++)
        {
            for (int z = environmentDimensions.zMin; z <= environmentDimensions.zMax; z++)
            {
                for (int x = environmentDimensions.xMin; x <= environmentDimensions.xMax; x++)
                {
                    BlockPos p;
                    if( environmentDimensions.absoluteCoords )
                        p = new BlockPos(x, y, z);
                    else
                        p = pos.add(x, y, z);
                    String name = "";
                    BlockState state = player.getWorld().getBlockState(p);
                    Identifier blockName = Registries.BLOCK.getId(state.getBlock());
                    name = blockName.getPath();
                    if (name.equals(block_name))
                    {
                        foundBlock = true;
                        double dist_cur = p.getSquaredDistance(pos);
                        double dist_nearest = p.getSquaredDistance(nearestBlock);
                        if (dist_cur < dist_nearest)
                        {
                            nearestBlock = p;
                        }
                    }
                }
            }
        }
        if (!foundBlock)
            json.add(jsonName, new JsonPrimitive("Empty"));
        else
        {
            JsonArray arr = new JsonArray(3);
            arr.add(nearestBlock.getX());
            arr.add(nearestBlock.getY());
            arr.add(nearestBlock.getZ());
            json.add(jsonName, arr);
        }
    }

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit currentMissionInit) {
        if (!this.sendRec || Objects.equals(this.block_name, "")){
            return;
        }
        this.sendRec = false;
        List<ObservationFromFindBlockImplementation.SimpleGridDef> environs = this.environs;
        if (environs != null)
        {
            for (ObservationFromFindBlockImplementation.SimpleGridDef sgd : environs)
            {
                this.findNearestBlockInGrid(json, sgd.getEnvirons(), MinecraftClient.getInstance().player, "block_pos_big_grid", this.block_name);
            }
        }
        this.block_name = "";
    }

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
        if (comm.length == 2 && comm[0].equalsIgnoreCase(ObservationFromFindBlck.FIND_BLOCK.value()) &&
                !comm[1].equalsIgnoreCase("off")) {
            this.sendRec = true;
            this.block_name = comm[1];
            return true;
        }
        return false;
    }

    public static class SimpleGridDef	// Could use the JAXB-generated GridDefinition class, but this is safer/simpler.
    {
        int xMin;
        int yMin;
        int zMin;
        int xMax;
        int yMax;
        int zMax;
        String name;
        boolean absoluteCoords;



        SimpleGridDef(int xmin, int ymin, int zmin, int xmax, int ymax, int zmax, String name, boolean absoluteCoords)
        {
            this.xMin = xmin;
            this.yMin = ymin;
            this.zMin = zmin;
            this.xMax = xmax;
            this.yMax = ymax;
            this.zMax = zmax;
            this.name = name;
            this.absoluteCoords = absoluteCoords;
        }
        JSONWorldDataHelper.GridDimensions getEnvirons()
        {
            JSONWorldDataHelper.GridDimensions env = new JSONWorldDataHelper.GridDimensions();
            env.xMax = this.xMax;
            env.yMax = this.yMax;
            env.zMax = this.zMax;
            env.xMin = this.xMin;
            env.yMin = this.yMin;
            env.zMin = this.zMin;
            env.absoluteCoords = this.absoluteCoords;
            return env;
        }
    }

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null || !(params instanceof ObservationFromFindBlock))
            return false;

        ObservationFromFindBlock ogparams = (ObservationFromFindBlock)params;
        this.environs = new ArrayList<ObservationFromFindBlockImplementation.SimpleGridDef>();
        GridDefinition gd = ogparams.getGrid();
        ObservationFromFindBlockImplementation.SimpleGridDef sgd = new ObservationFromFindBlockImplementation.SimpleGridDef(
                gd.getMin().getX().intValue(),
                gd.getMin().getY().intValue(),
                gd.getMin().getZ().intValue(),
                gd.getMax().getX().intValue(),
                gd.getMax().getY().intValue(),
                gd.getMax().getZ().intValue(),
                gd.getName(),
                gd.isAbsoluteCoords());
        this.environs.add(sgd);
        return true;
    }
}
