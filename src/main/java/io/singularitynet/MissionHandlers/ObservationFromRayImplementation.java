package io.singularitynet.MissionHandlers;

import com.google.gson.JsonObject;
import io.singularitynet.Client.VereyaModClient;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromRay;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.Map;


// https://fabricmc.net/wiki/tutorial:pixel_raycast
public class ObservationFromRayImplementation extends HandlerBase implements IObservationProducer
{
    private ObservationFromRay ofrparams;

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null || !(params instanceof ObservationFromRay))
            return false;

        this.ofrparams = (ObservationFromRay)params;
        return true;
    }

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit missionInit)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        Entity cameraEntity = client.getCameraEntity();
        buildMouseOverData(cameraEntity, json, this.ofrparams.isIncludeNBT());
        if (json.has(VereyaModClient.CONTROLLABLE)){
            JsonObject controllable = json.getAsJsonObject(VereyaModClient.CONTROLLABLE);
            for(Entity entity: VereyaModClient.getControllableEntities().values()){
                String uuid = entity.getUuidAsString();
                if (!controllable.has(uuid)) controllable.add(uuid, new JsonObject());
                JsonObject entityJson = controllable.getAsJsonObject(uuid);
                ObservationFromRayImplementation.buildMouseOverData(entity, entityJson, this.ofrparams.isIncludeNBT());
                }
            }
    }

    @Override
    public void prepare(MissionInit missionInit)
    {
    }

    @Override
    public void cleanup()
    {
    }

    /** Build the json object for the object under the cursor, whether it is a block or a creature.<br>
     * If there is any data to be returned, the json will be added in a subnode called "LineOfSight".
     * @param json a JSON object into which the info for the object under the mouse will be added.
     */
    public static void buildMouseOverData(Entity cameraEntity, JsonObject json, boolean includeNBTData)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        HitResult mop = cameraEntity.raycast(96.0, 1.0F, true);
        JsonObject jsonMop = new JsonObject();
        switch(mop.getType()) {
            case MISS:
                // nothing near enough
                break;
            case BLOCK:
                BlockHitResult blockHit = (BlockHitResult) mop;
                BlockPos blockPos = blockHit.getBlockPos();
                BlockState blockState = client.world.getBlockState(blockPos);
                if (blockState != null) {
                    for (Map.Entry entry : blockState.getEntries().entrySet()) {
                        Property<?> property = (Property<?>) entry.getKey();
                        jsonMop.addProperty(property.getName(), entry.getValue().toString());
                    }
                    Identifier id = Registries.BLOCK.getId(blockState.getBlock());
                    jsonMop.addProperty("type", id.toString());
                }
                break;
            case ENTITY:
                EntityHitResult entityHit = (EntityHitResult) mop;
                Entity entity = entityHit.getEntity();
                jsonMop.addProperty("type", entity.getType().getUntranslatedName());
                break;
        }
        jsonMop.addProperty("hitType", mop.getType().name());
        Vec3d hitPoint = mop.getPos();
        if (hitPoint != null && mop.getType() != HitResult.Type.MISS) {
            jsonMop.addProperty("x", hitPoint.x);
            jsonMop.addProperty("y", hitPoint.y);
            jsonMop.addProperty("z", hitPoint.z);
            double distance = cameraEntity.getCameraPosVec(1.0f).distanceTo(hitPoint);
            jsonMop.addProperty("distance", distance);
            float reachDistance = 4.5f;
            if (client.interactionManager.hasExtendedReach()) {
                reachDistance = 6.0f;//Change this to extend the reach
            }
            boolean inRange = false;
            if (distance < reachDistance) {
                inRange = true;
            }
            jsonMop.addProperty("inRange", inRange);
        }
        json.add("LineOfSight", jsonMop);
    }
}
