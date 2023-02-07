// --------------------------------------------------------------------------------------------------
//  Copyright (c) 2016 Microsoft Corporation
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
//  associated documentation files (the "Software"), to deal in the Software without restriction,
//  including without limitation the rights to use, copy, modify, merge, publish, distribute,
//  sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions:
//
//  The above copyright notice and this permission notice shall be included in all copies or
//  substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
//  NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
//  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
//  DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
// --------------------------------------------------------------------------------------------------


package io.singularitynet.MissionHandlers;


import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.singularitynet.Client.VereyaModClient;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.apache.logging.log4j.LogManager;

import java.util.ArrayList;
import java.util.List;


public class ObservationFromHumanImplementation extends HandlerBase implements IObservationProducer
{
    private abstract class ObservationEvent
    {
        public long timestamp = 0;
        public abstract JsonObject getJSON();

        ObservationEvent()
        {
            this.timestamp = MinecraftClient.getInstance().world.getTime();
        }
    }

    private class MouseObservationEvent extends ObservationEvent
    {
        private double deltaX;
        private double deltaY;

        public MouseObservationEvent(double deltaX, double deltaY)
        {
            super();
            this.deltaX = deltaX;
            this.deltaY = deltaY;
        }

        @Override
        public JsonObject getJSON()
        {
            JsonObject jsonEvent = new JsonObject();
            jsonEvent.addProperty("time", this.timestamp);
            jsonEvent.addProperty("type", "mouse");
            jsonEvent.addProperty("deltaX", this.deltaX);
            jsonEvent.addProperty("deltaY", this.deltaY);
            return jsonEvent;
        }
    }

    private class KeyObservationEvent extends ObservationEvent
    {
        private String commandString;
        private boolean pressed;

        KeyObservationEvent(String commandString, boolean pressed)
        {
            super();
            this.commandString = commandString;
            this.pressed = pressed;
        }

        @Override
        public JsonObject getJSON()
        {
            JsonObject jsonEvent = new JsonObject();
            jsonEvent.addProperty("time", this.timestamp);
            jsonEvent.addProperty("type", "key");
            jsonEvent.addProperty("command", this.commandString);
            jsonEvent.addProperty("pressed", this.pressed);
            return jsonEvent;
        }
    }

    private class InputObserver implements VereyaModClient.MouseEventListener, CommandForKey.KeyEventListener
    {
        @Override
        public void onXYChange(double deltaX, double deltaY)
        {
            System.out.println("Mouse observed: " + deltaX + ", " + deltaY);
            if (deltaX != 0 || deltaY != 0)
                queueEvent(new MouseObservationEvent(deltaX, deltaY));
        }

        @Override
        public void onKeyChange(String commandString, boolean pressed)
        {
            queueEvent(new KeyObservationEvent(commandString, pressed));
        }
    }

    InputObserver observer = new InputObserver();
    List<ObservationEvent> events = new ArrayList<ObservationEvent>();
    List<CommandForKey> keys = null;

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit missionInit)
    {
        synchronized(this.events)
        {
            if (this.events.size() > 0)
            {
                JsonArray jsonEvents = new JsonArray();
                for (ObservationEvent event : this.events)
                    jsonEvents.add(event.getJSON());
                this.events.clear();
                json.add("events", jsonEvents);
            }
        }
    }

    public void queueEvent(ObservationEvent event)
    {
        synchronized(this.events)
        {
            this.events.add(event);
        }
    }

    @Override
    public void prepare(MissionInit missionInit)
    {

        Mouse mhelp = MinecraftClient.getInstance().mouse;
        if (!(mhelp instanceof VereyaModClient.MyMouse))
        {
            LogManager.getLogger().error("ERROR! MouseHook not installed - Malmo won't work correctly.");
            return;
        }
        ((VereyaModClient.MyMouse)mhelp).setObserver(this.observer);
        this.keys = this.getKeyOverrides();
        for (CommandForKey k : this.keys)
        {
            k.install(missionInit);
            k.setKeyEventObserver(this.observer);
        }
    }

    @Override
    public void cleanup()
    {
        for (CommandForKey k : this.keys)
        {
            k.setKeyEventObserver(null);
        }
        Mouse mhelp = MinecraftClient.getInstance().mouse;
        if (!(mhelp instanceof VereyaModClient.MyMouse))
        {
            LogManager.getLogger().error("ERROR! MouseHook not installed - Malmo won't work correctly.");
            return;
        }
        ((VereyaModClient.MyMouse)mhelp).setObserver(null);
    }

    static public List<CommandForKey> getKeyOverrides()
    {
        List<CommandForKey> keys = new ArrayList<CommandForKey>();
        keys.add(new CommandForKey("key.forward"));
        keys.add(new CommandForKey("key.left"));
        keys.add(new CommandForKey("key.back"));
        keys.add(new CommandForKey("key.right"));
        keys.add(new CommandForKey("key.jump"));
        keys.add(new CommandForKey("key.sneak"));
        keys.add(new CommandForKey("key.sprint"));
        keys.add(new CommandForKey("key.inventory"));
        keys.add(new CommandForKey("key.swapHands"));
        keys.add(new CommandForKey("key.drop"));
        keys.add(new CommandForKey("key.use"));
        keys.add(new CommandForKey("key.attack"));
        keys.add(new CommandForKey("key.pickItem"));
        for (int i = 1; i <= 9; i++)
        {
            keys.add(new CommandForKey("key.hotbar." + i));
        }
        return keys;
    }
}