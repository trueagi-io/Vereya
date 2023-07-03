package io.singularitynet.entity;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.world.World;

public class MyChicken extends ChickenEntity {

    public MyChicken(EntityType<? extends ChickenEntity> entityType, World world) {
        super(entityType, world);
    }
}
