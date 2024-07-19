package io.singularitynet.mixin;

import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.world.entity.EntityLike;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerEntityManager.class)
public interface ServerEntityManagerMixin<T extends EntityLike> {

    @Invoker("stopTracking")
    public void stopTracking(T entity);
}
