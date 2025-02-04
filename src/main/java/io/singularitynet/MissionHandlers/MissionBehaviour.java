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

import io.singularitynet.Client.IMissionBehaviour;
import io.singularitynet.MissionHandlerInterfaces.*;
import io.singularitynet.projectmalmo.AgentHandlers;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ServerHandlers;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MissionBehaviour implements IMissionBehaviour {
    public List<IVideoProducer> videoProducers = new ArrayList<IVideoProducer>();
    public IAudioProducer audioProducer = null;
    public ICommandHandler commandHandler = null;
    public IObservationProducer observationProducer = null;
    public IRewardProducer rewardProducer = null;
    public IWorldDecorator worldDecorator = null;
    public IWorldGenerator worldGenerator = null;
    public IWantToQuit quitProducer = null;
    private String failedHandlers = "";

    /** Create instances of the various mission handlers, according to the specifications in the MissionInit object.<br>
     * The Mission object (inside MissionInit) contains an optional string for each type of handler, which specifies the class-name of the handler required.<br>
     * This method will attempt to instantiate all the requested objects.<br>
     * Any objects that are left unspecified by the MissionInit, or are unable to be created, are left as null.
     * @param missionInit the MissionInit object for the current Mission, containing information about which handler objects to instantiate.
     * @return a MissionBehaviour object that holds all the requested handlers (assuming they could be created).
     */
    public static MissionBehaviour createAgentHandlersFromMissionInit(MissionInit missionInit) throws Exception
    {
        MissionBehaviour behaviour = new MissionBehaviour();
        behaviour.initAgent(missionInit);
        // TODO - can't throw and return a behaviour!!
        //if (behaviour.getErrorReport() != null && behaviour.getErrorReport().length() > 0)
        //    throw new Exception(behaviour.getErrorReport());

        return behaviour;
    }

    private void initAgent(MissionInit missionInit)
    {
        reset();
        AgentHandlers handlerset = missionInit.getMission().getAgentSection().get(missionInit.getClientRole()).getAgentHandlers();

        // Instantiate the various handlers:
        for (Object handler : handlerset.getAgentMissionHandlers())
            createAndAddHandler(handler);
    }

    private void createAndAddHandler(Object xmlObj)
    {
        createAndAddHandler(xmlObj, false);
    }

    private void createAndAddHandler(Object xmlObj, boolean isServer)
    {
        if (xmlObj == null)
            return;

        Object handler = createHandlerFromParams(xmlObj, isServer);
        if (handler != null)
        {
            if (handler instanceof HandlerBase)
                ((HandlerBase)(handler)).setParentBehaviour(this);
            addHandler(handler);
        }
    }

    /** Attempt to create an instance of the specified handler class, using reflection.
     * @param xmlHandler the object which specifies both the name and the parameters of the requested handler.
     * @param isServer true if this is a server-side handler, false if it's an agent-side handler.
     * @return an instance of the requested class, if possible, or null if the class wasn't found.
     */
    private Object createHandlerFromParams(Object xmlHandler, boolean isServer)
    {
        if (xmlHandler == null)
            return null;

        Object handler = null;
        String handlerClass = xmlHandler.getClass().getSimpleName();
        if (handlerClass == null || handlerClass.length() == 0)
        {
            return null;
        }
        // To avoid name collisions, the java class will have the suffix "Implementation".
        String classname = "io.singularitynet.MissionHandlers." + handlerClass + "Implementation";
        try
        {
            if (isServer)
                classname += "Server";
            Class<?> c = Class.forName(classname);
            handler = c.getDeclaredConstructor().newInstance();
            if (!((HandlerBase)handler).parseParameters(xmlHandler))
                this.failedHandlers += handlerClass + " failed to parse parameters.\n";
            else
                LogManager.getLogger().info("created handler " + classname);
        }
        catch (ClassNotFoundException e)
        {
            LogManager.getLogger().debug("Duff MissionHandler requested: " + handlerClass);
            this.failedHandlers += "Failed to find " + handlerClass + "\n";
        }
        catch (InstantiationException e)
        {
            LogManager.getLogger().error("Could not instantiate specified MissionHandler. " + classname, e);
            this.failedHandlers += "Failed to create " + handlerClass + "\n";
        }
        catch (IllegalAccessException e)
        {
            LogManager.getLogger().error("Could not instantiate specified MissionHandler. " + classname, e);
            this.failedHandlers += "Failed to access " + handlerClass + "\n";
        } catch (InvocationTargetException e) {
            LogManager.getLogger().error("Could not instantiate specified MissionHandler. " + classname, e);
            this.failedHandlers += "Failed to access " + handlerClass + "\n";
        } catch (NoSuchMethodException e) {
            LogManager.getLogger().error("Could not instantiate specified MissionHandler. " + classname, e);
            this.failedHandlers += "Failed to access constructor in " + handlerClass + "\n";
        }
        return handler;
    }

    /** Add this handler to our set, creating containers as needs be.
     * @param handler The handler to add.
     */
    private void addHandler(Object handler)
    {
        // Would be nice to have a better way to do this,
        // but the type information isn't preserved in the XML format anymore -
        // and the number of types of handler is pretty unlikely to change, so this list
        // won't have to be added to often, if at all.
        if (handler == null)
            return;

        if (handler instanceof IVideoProducer)
            addVideoProducer((IVideoProducer)handler);
        if (handler instanceof IAudioProducer)
            addAudioProducer((IAudioProducer)handler);
        if (handler instanceof ICommandHandler)
            addCommandHandler((ICommandHandler)handler);
        if (handler instanceof IObservationProducer)
            addObservationProducer((IObservationProducer)handler);
        if (handler instanceof IRewardProducer)
            addRewardProducer((IRewardProducer)handler);
        if (handler instanceof IWorldGenerator)
            addWorldGenerator((IWorldGenerator)handler);
        if (handler instanceof IWorldDecorator)
            addWorldDecorator((IWorldDecorator)handler);
        if (handler instanceof IWantToQuit)
            addQuitProducer((IWantToQuit)handler);
        else
            this.failedHandlers += handler.getClass().getSimpleName() + " isn't of a recognised handler type.\n";
    }

    private void addVideoProducer(IVideoProducer handler)
    {
        if (this.videoProducers.size() > 0 && (this.videoProducers.get(0).getHeight() != handler.getHeight() || this.videoProducers.get(0).getWidth() != handler.getWidth()))
            this.failedHandlers += "If multiple video producers are specified, they must all share the same dimensions.\n";
        else
            this.videoProducers.add(handler);
    }

    private void addAudioProducer(IAudioProducer handler)
    {
        if (this.audioProducer != null)
            this.failedHandlers += "Too many audio producers specified - only one allowed at present.\n";
        else
            this.audioProducer = handler;
    }

    private void addWorldGenerator(IWorldGenerator handler)
    {
        if (this.worldGenerator != null)
            this.failedHandlers += "Too many world generators specified - only one allowed.\n";
        else
            this.worldGenerator = handler;
    }

    public void addRewardProducer(IRewardProducer handler)
    {
        if (this.rewardProducer == null)
            this.rewardProducer = handler;
        else
        {
            if (!(this.rewardProducer instanceof RewardGroup) || ((RewardGroup) this.rewardProducer).isFixed())
            {
                // We have multiple reward handlers - group them.
                RewardGroup group = new RewardGroup();
                group.addRewardProducer(this.rewardProducer);
                this.rewardProducer = group;
            }
            ((RewardGroup) this.rewardProducer).addRewardProducer(handler);
        }
    }

    public void addObservationProducer(IObservationProducer handler)
    {
        if (this.observationProducer == null)
            this.observationProducer = handler;
        else
        {
            if (!(this.observationProducer instanceof ObservationFromComposite) || ((ObservationFromComposite)this.observationProducer).isFixed())
            {
                ObservationFromComposite group = new ObservationFromComposite();
                group.addObservationProducer(this.observationProducer);
                this.observationProducer = group;
            }
            ((ObservationFromComposite)this.observationProducer).addObservationProducer(handler);
        }
    }

    public void addWorldDecorator(IWorldDecorator handler)
    {/*
        if (this.worldDecorator == null)
            this.worldDecorator = handler;
        else
        {
            if (!(this.worldDecorator instanceof WorldFromComposite) || ((WorldFromComposite)this.worldDecorator).isFixed())
            {
                WorldFromComposite group = new WorldFromComposite();
                group.addBuilder(this.worldDecorator);
                this.worldDecorator = group;
            }
            ((WorldFromComposite)this.worldDecorator).addBuilder(handler);
        }*/
    }

    public void addQuitProducer(IWantToQuit handler)
    {
        if (this.quitProducer == null)
            this.quitProducer = handler;
        else
        {
            if (!(this.quitProducer instanceof QuitFromComposite) || ((QuitFromComposite)this.quitProducer).isFixed())
            {
                QuitFromComposite group = new QuitFromComposite();
                group.addQuitter(this.quitProducer);
                this.quitProducer = group;
            }
            ((QuitFromComposite)this.quitProducer).addQuitter(handler);
        }
    }

    public void addCommandHandler(ICommandHandler handler)
    {
        if (this.commandHandler == null)
            this.commandHandler = handler;
        else
        {
            if (!(this.commandHandler instanceof CommandGroup) || ((CommandGroup)this.commandHandler).isFixed())
            {
                // We have multiple command handlers - group them.
                CommandGroup group = new CommandGroup();
                group.addCommandHandler(this.commandHandler);
                this.commandHandler = (ICommandHandler) group;
            }
            ((CommandGroup)this.commandHandler).addCommandHandler(handler);
        }
    }
    private void reset()
    {
        this.videoProducers = new ArrayList<IVideoProducer>();
        this.audioProducer = null;
        this.commandHandler = null;
        this.observationProducer = null;
        this.rewardProducer = null;
        this.worldDecorator = null;
        this.quitProducer = null;
    }

    public static IWorldGenerator createWorldGenerator(MissionInit missionInit)
    {
        ServerHandlers handlerset = missionInit.getMission().getServerSection().getServerHandlers();
        MissionBehaviour behaviour = new MissionBehaviour();
        Object handler = behaviour.createHandlerFromParams(handlerset.getWorldGenerator(), false);
        return IWorldGenerator.class.cast(handler);
    }

    public static MissionBehaviour createServerHandlersFromMissionInit(MissionInit missionInit) throws Exception
    {
        MissionBehaviour behaviour = new MissionBehaviour();
        behaviour.initServer(missionInit);
        // TODO - can't throw and return a behaviour!!
        //if (behaviour.getErrorReport() != null && behaviour.getErrorReport().length() > 0)
        //    throw new Exception(behaviour.getErrorReport());

        return behaviour;
    }

    private void initServer(MissionInit missionInit)
    {
        reset();
        ServerHandlers handlerset = missionInit.getMission().getServerSection().getServerHandlers();

        // Instantiate the various handlers:
        createAndAddHandler(handlerset.getWorldGenerator());
        for (Object handler : handlerset.getWorldDecorators())
            createAndAddHandler(handler);
        for (Object handler : handlerset.getServerQuitProducers())
            createAndAddHandler(handler);

        AgentHandlers agenthandlerset = missionInit.getMission().getAgentSection().get(missionInit.getClientRole()).getAgentHandlers();
        // Instantiate the various handlers:
        for (Object handler : agenthandlerset.getAgentMissionHandlers())
            createAndAddHandler(handler, true);
    }

    /** This method gives our handlers a chance to add any information to the ping message
     * which the client sends (repeatedly) to the server while the agents are assembling.
     * This message is guaranteed to get through to the server, so it is a good place to
     * communicate.
     * (NOTE this is called BEFORE addExtraHandlers - but that mechanism is provided to allow
     * the *server* to add extra handlers on the *client* - so the server should already know
     * whatever the extra handlers might want to tell it!)
     * @param map the map of data passed to the server
     */
    public void appendExtraServerInformation(HashMap<String, String> map)
    {
        List<HandlerBase> handlers = getClientHandlerList();
        for (HandlerBase handler : handlers)
            handler.appendExtraServerInformation(map);
    }
    public boolean addExtraHandlers(List<Object> handlers)
    {
        for (Object handler : handlers)
            createAndAddHandler(handler);
        return true;
    }

    protected List<HandlerBase> getClientHandlerList()
    {
        List<HandlerBase> handlers = new ArrayList<HandlerBase>();
        for (IVideoProducer vp : this.videoProducers)
        {
            if (vp != null && vp instanceof HandlerBase)
                handlers.add((HandlerBase)vp);
        }
        if (this.audioProducer != null && this.audioProducer instanceof HandlerBase)
            handlers.add((HandlerBase)this.audioProducer);
        if (this.commandHandler != null && this.commandHandler instanceof HandlerBase)
            handlers.add((HandlerBase)this.commandHandler);
        if (this.observationProducer != null && this.observationProducer instanceof HandlerBase)
            handlers.add((HandlerBase)this.observationProducer);
        if (this.rewardProducer != null && this.rewardProducer instanceof HandlerBase)
            handlers.add((HandlerBase)this.rewardProducer);
        if (this.quitProducer != null && this.quitProducer instanceof HandlerBase)
            handlers.add((HandlerBase)this.quitProducer);
        return handlers;
    }
}
