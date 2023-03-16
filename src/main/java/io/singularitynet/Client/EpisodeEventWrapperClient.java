package io.singularitynet.Client;

import io.singularitynet.EpisodeEventWrapper;
import io.singularitynet.TitleScreenEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;

public class EpisodeEventWrapperClient extends EpisodeEventWrapper implements ClientTickEvents.EndTick,
        ClientChunkEvents.Load,
        ClientLifecycleEvents.ClientStarted,
        TitleScreenEvents.EndInit
{
    public EpisodeEventWrapperClient(){
        super();
        LogManager.getLogger().info("Setting up EpisodeEventWrapper for Client events");
        ClientTickEvents.END_CLIENT_TICK.register(client -> {this.onEndTick(client);});
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {this.onChunkLoad(world, chunk);});
        WorldRenderEvents.END.register((context) -> {this.onRenderTickEnd(context);});
        WorldRenderEvents.START.register((context) -> {this.onRenderTickStart(context);});
        ClientLifecycleEvents.CLIENT_STARTED.register((client) -> {this.onClientStarted(client);});
        TitleScreenEvents.END_TITLESCREEN_INIT.register(()-> {this.onTitleScreenEndInit();});
    }

    public void onRenderTickEnd(WorldRenderContext ev)
    {
        // Pass the event on to the active episode, if there is one:
        this.stateEpisodeLock.readLock().lock();
        if (this.stateEpisode != null && this.stateEpisode.isLive())
        {
            this.stateEpisode.onRenderTickEnd(ev);
        }
        this.stateEpisodeLock.readLock().unlock();
    }

    public void onRenderTickStart(WorldRenderContext ev)
    {
        // Pass the event on to the active episode, if there is one:
        this.stateEpisodeLock.readLock().lock();
        if (this.stateEpisode != null && this.stateEpisode.isLive())
        {
            this.stateEpisode.onRenderTickStart(ev);
        }
        this.stateEpisodeLock.readLock().unlock();
    }

    @Override
    public void onEndTick(MinecraftClient client) {
        // Now pass the event on to the active episode, if there is one:
        this.stateEpisodeLock.readLock().lock();
        if (this.stateEpisode != null && this.stateEpisode.isLive())
        {
            try
            {
                this.stateEpisode.onClientTick(client);
            }
            catch (Exception e)
            {
                LogManager.getLogger().error("Exception onClientTick", e);
            }
        }
        this.stateEpisodeLock.readLock().unlock();
    }

    @Override
    public void onChunkLoad(ClientWorld world, WorldChunk chunk) {
        // Pass the event on to the active episode, if there is one:
        this.stateEpisodeLock.readLock().lock();
        if (this.stateEpisode != null && this.stateEpisode.isLive())
        {
            this.stateEpisode.onChunkLoad(world, chunk);
        }
        this.stateEpisodeLock.readLock().unlock();
    }

    @Override
    public void onClientStarted(MinecraftClient client) {
        // Pass the event on to the active episode, if there is one:
        this.stateEpisodeLock.readLock().lock();
        if (this.stateEpisode != null && this.stateEpisode.isLive())
        {
            this.stateEpisode.onClientStarted(client);
        }
        this.stateEpisodeLock.readLock().unlock();
    }

    @Override
    public void onTitleScreenEndInit(){
        // Pass the event on to the active episode, if there is one:
        this.stateEpisodeLock.readLock().lock();
        if (this.stateEpisode != null && this.stateEpisode.isLive())
        {
            this.stateEpisode.onTitleScreen();
        }
        this.stateEpisodeLock.readLock().unlock();
    }
}
