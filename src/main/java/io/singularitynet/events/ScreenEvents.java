package io.singularitynet.events;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.jetbrains.annotations.Nullable;

public interface ScreenEvents {
    // event for setScreen
    Event<ScreenEvents> SET_SCREEN = EventFactory.createArrayBacked(ScreenEvents.class, callbacks -> (MinecraftClient client, @Nullable Screen screen) -> {
        for (ScreenEvents callback : callbacks) {
            callback.interact(client, screen);
        }
    });

    void interact(MinecraftClient client, @Nullable Screen screen);
}
