package io.singularitynet.events;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.profiler.Profiler;

public interface ServerEntityEventsVereya {

    /**
     * Called when an Entity is about to be loaded into a ServerWorld.
     *
     * <p>Can be used to cancel entity loading.
     */
    public static final Event<ServerEntityEventsVereya> BEFORE_ENTITY_LOAD = EventFactory.createArrayBacked(ServerEntityEventsVereya.class, callbacks -> (entity, world) -> {
        if (EventFactory.isProfilingEnabled()) {
            final Profiler profiler = world.getProfiler();
            profiler.push("fabricServerEntityLoadBefore");

            for (ServerEntityEventsVereya callback : callbacks) {
                profiler.push(EventFactory.getHandlerName(callback));
                ActionResult result = callback.interact(entity, world);
                profiler.pop();
                if (result != ActionResult.PASS) {
                    return result;
                }
            }
            profiler.pop();
        } else {
            for (ServerEntityEventsVereya callback : callbacks) {
                ActionResult result = callback.interact(entity, world);
                if (result != ActionResult.PASS) {
                    return result;
                }
            }
        }
        return ActionResult.PASS;
    });

    public static final Event<ServerEntityEventsVereya> BEFORE_ENTITY_ADD = EventFactory.createArrayBacked(ServerEntityEventsVereya.class, callbacks -> (entity, world) -> {
        if (EventFactory.isProfilingEnabled()) {
            final Profiler profiler = world.getProfiler();
            profiler.push("fabricServerEntityAddBefore");

            for (ServerEntityEventsVereya callback : callbacks) {
                profiler.push(EventFactory.getHandlerName(callback));
                ActionResult result = callback.interact(entity, world);
                profiler.pop();
                if (result != ActionResult.PASS) {
                    return result;
                }
            }
            profiler.pop();
        } else {
            for (ServerEntityEventsVereya callback : callbacks) {
                ActionResult result = callback.interact(entity, world);
                if (result != ActionResult.PASS) {
                    return result;
                }
            }
        }
        return ActionResult.PASS;
    });

    ActionResult interact( Entity entity, ServerWorld world);
}
