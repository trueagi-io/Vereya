package io.singularitynet;

import net.fabricmc.fabric.api.event.Event;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class EventWrapper<T> {
    private Map<Event, Set<T>> registry = null;
    private static EventWrapper instance = new EventWrapper();

    private EventWrapper(){
        registry = new HashMap<>();
    };

    public static EventWrapper getInstance(){
        return instance;
    }

    public void register(Event event, T invoker) {
        registry.getOrDefault(event, new HashSet<T>()).add(invoker);
    }

    public void unregister(Event event, T in) {
        registry.get(event).remove(in);
    }
}