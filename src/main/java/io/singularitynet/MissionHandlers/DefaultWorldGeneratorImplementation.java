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

import io.singularitynet.MissionHandlerInterfaces.IWorldGenerator;
import io.singularitynet.projectmalmo.DefaultWorldGenerator;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;

import java.util.Random;

public class DefaultWorldGeneratorImplementation extends HandlerBase implements IWorldGenerator
{
	DefaultWorldGenerator dwparams;
	
	@Override
	public boolean parseParameters(Object params)
	{
		if (params == null || !(params instanceof DefaultWorldGenerator))
			return false;
		
		this.dwparams = (DefaultWorldGenerator)params;
		return true;
	}
	
    public static long getWorldSeedFromString(String seedString)
    {
        // This seed logic mirrors the Minecraft code in GuiCreateWorld.actionPerformed:
        long seed = (new Random()).nextLong();
        if (seedString != null && !seedString.isEmpty())
        {
            try
            {
                long i = Long.parseLong(seedString);
                if (i != 0L)
                    seed = i;
            }
            catch (NumberFormatException numberformatexception)
            {
                seed = (long)seedString.hashCode();
            }
        }
        return seed;
    }

	@Override
    public boolean createWorld(MissionInit missionInit)
    {
        long seed = getWorldSeedFromString(this.dwparams.getSeed());

        // Create a filename for this map - we use the time stamp to make sure it is different from other worlds, otherwise no new world
        // will be created, it will simply load the old one.
        try{
            WorldUtil.createLevel(false, seed, Difficulty.NORMAL);
            return true;
        } catch (RuntimeException e) {
            LogManager.getLogger().error(e);
            return false;
        }
    }

    @Override
    public boolean shouldCreateWorld(MissionInit missionInit, World world)
    {
        if (this.dwparams != null && this.dwparams.isForceReset())
            return true;

    	if (MinecraftClient.getInstance().world == null || world == null) {
            return true;    // Definitely need to create a world if there isn't one in existence!
        }

        world.getLevelProperties();
        //String genOptions = world.getWorldInfo().getGeneratorOptions();
        LogManager.getLogger().warn("Can't compare worlds yet");

        /*
        if (genOptions != null && !genOptions.isEmpty()) {
            return true;    // Default world has no generator options.
        } */

        return true;
    }

    @Override
    public String getErrorDetails()
    {
        return "";  // Don't currently have any error exit points.
    }

}