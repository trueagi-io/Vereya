package io.singularitynet;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;


public class TitleScreenEvents {
    public static final Event<TitleScreenEvents.EndInit> END_TITLESCREEN_INIT= EventFactory.createArrayBacked(TitleScreenEvents.EndInit.class,
            (callbacks) -> () -> {

            for (TitleScreenEvents.EndInit event : callbacks) {
                event.onTitleScreenEndInit();
            }
        });

    @FunctionalInterface
    public interface EndInit {
        void onTitleScreenEndInit();
    }
}
