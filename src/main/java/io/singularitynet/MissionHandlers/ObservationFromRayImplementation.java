package io.singularitynet.MissionHandlers;

import com.google.gson.JsonObject;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromRay;
import io.singularitynet.utils.Vec3f;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.NotNull;
import java.util.Map;

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
        buildMouseOverData(json, this.ofrparams.isIncludeNBT());
    }

    @Override
    public void prepare(MissionInit missionInit)
    {
    }

    @Override
    public void cleanup()
    {
    }

    private static Vec3d map(float anglePerPixel, Vec3d center, Vec3f horizontalRotationAxis,
                             Vec3f verticalRotationAxis, int x, int y, int width, int height) {
        float horizontalRotation = (x - width/2f) * anglePerPixel;
        float verticalRotation = (y - height/2f) * anglePerPixel;

        final Vec3f temp2 = new Vec3f(center);
        temp2.rotate(verticalRotationAxis.getDegreesQuaternion(verticalRotation));
        temp2.rotate(horizontalRotationAxis.getDegreesQuaternion(horizontalRotation));
        return new Vec3d(temp2.getX(), temp2.getY(), temp2.getZ());
    }

    private static HitResult raycastInDirection(MinecraftClient client, float tickDelta, Vec3d direction) {
        Entity entity = client.getCameraEntity();
        if (entity == null || client.world == null) {
            return null;
        }

        // double reachDistance = client.interactionManager.getReachDistance();//Change this to extend the reach
        double reachDistance = 50.0f;
        HitResult target = raycast(entity, reachDistance, tickDelta, true, direction);
        double extendedReach = reachDistance;
        /*
        if (client.interactionManager.hasExtendedReach()) {
            extendedReach = 6.0D;//Change this to extend the reach
            reachDistance = extendedReach;
        } else {
            if (reachDistance > 3.0D) {
                tooFar = true;
            }
        }*/

        Vec3d cameraPos = entity.getCameraPosVec(tickDelta);

        extendedReach = extendedReach * extendedReach;
        if (target != null) {
            extendedReach = target.getPos().squaredDistanceTo(cameraPos);
        }

        Vec3d vec3d3 = cameraPos.add(direction.multiply(reachDistance));
        Box box = entity
                .getBoundingBox()
                .stretch(entity.getRotationVec(1.0F).multiply(reachDistance))
                .expand(1.0D, 1.0D, 1.0D);
        EntityHitResult entityHitResult = ProjectileUtil.raycast(
                entity,
                cameraPos,
                vec3d3,
                box,
                (entityx) -> !entityx.isSpectator() && entityx.isCollidable(),
                extendedReach
        );

        if (entityHitResult == null) {
            return target;
        }

        Entity entity2 = entityHitResult.getEntity();
        Vec3d vec3d4 = entityHitResult.getPos();
        double g = cameraPos.squaredDistanceTo(vec3d4);
        if (g < extendedReach || target == null) {
            target = entityHitResult;
            /*
            if (entity2 instanceof LivingEntity || entity2 instanceof ItemFrameEntity) {
                client.targetedEntity = entity2;
            }*/
        }

        return target;
    }

    private static HitResult raycast(
            Entity entity,
            double maxDistance,
            float tickDelta,
            boolean includeFluids,
            Vec3d direction
    ) {
        Vec3d end = entity.getCameraPosVec(tickDelta).add(direction.multiply(maxDistance));
        return entity.world.raycast(new RaycastContext(
                entity.getCameraPosVec(tickDelta),
                end,
                RaycastContext.ShapeType.OUTLINE,
                includeFluids ? RaycastContext.FluidHandling.ANY : RaycastContext.FluidHandling.NONE,
                entity
        ));
    }

    /** Build the json object for the object under the cursor, whether it is a block or a creature.<br>
     * If there is any data to be returned, the json will be added in a subnode called "LineOfSight".
     * @param json a JSON object into which the info for the object under the mouse will be added.
     */
    public static void buildMouseOverData(JsonObject json, boolean includeNBTData)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        Vec3d cameraDirection = client.cameraEntity.getRotationVec(1.0f);
        SimpleOption<Integer> option = client.options.getFov();
        double fov = option.getValue();
        double angleSize = fov/height;
        Vec3d direction = getDirection(width, height, cameraDirection, (float) angleSize);
        HitResult mop = raycastInDirection(client, 1.0f, direction);
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
            double distance = client.cameraEntity.getCameraPosVec(1.0f).distanceTo(hitPoint);
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

    @NotNull
    private static Vec3d getDirection(int width, int height, Vec3d cameraDirection, float angleSize) {
        Vec3f verticalRotationAxis = new Vec3f(cameraDirection);
        Vec3f backup = verticalRotationAxis.copy();
        verticalRotationAxis.cross(Vec3f.POSITIVE_Y);
        Vec3d direction;
        if(verticalRotationAxis.normalize()) {
            Vec3f horizontalRotationAxis = new Vec3f(cameraDirection);
            horizontalRotationAxis.cross(verticalRotationAxis);
            horizontalRotationAxis.normalize();

            verticalRotationAxis = new Vec3f(cameraDirection);
            verticalRotationAxis.cross(horizontalRotationAxis);
            direction = map(
                    angleSize,
                    cameraDirection,
                    horizontalRotationAxis,
                    verticalRotationAxis,
                    width / 2,
                    height / 2,
                    width,
                    height
            );
        } else {  // check if coincides positive or negative
            // if coincides then verticalRotationAxis equals POSITIVE_Y
            double eps = 0.0000001;
            if (Math.abs(Vec3f.POSITIVE_Y.getX() - backup.getX()) < eps &&
                    Math.abs(Vec3f.POSITIVE_Y.getY() - backup.getY()) < eps &&
                    Math.abs(Vec3f.POSITIVE_Y.getZ() - backup.getZ()) < eps ){
                // coincides
                direction = new Vec3d(Vec3f.POSITIVE_Y.getX(), Vec3f.POSITIVE_Y.getY(), Vec3f.POSITIVE_Y.getZ());
            } else {
                direction = new Vec3d(Vec3f.POSITIVE_Y.getX(), Vec3f.POSITIVE_Y.getY(), Vec3f.POSITIVE_Y.getZ());
            }
        }
        return direction;
    }
}
