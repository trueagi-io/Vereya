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
import io.singularitynet.projectmalmo.FlatWorldGenerator;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.Difficulty;
import org.apache.logging.log4j.LogManager;

import java.util.Properties;

public class FlatWorldGeneratorImplementation extends HandlerBase implements IWorldGenerator
{
    FlatWorldGenerator fwparams;

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null || !(params instanceof FlatWorldGenerator))
            return false;

        this.fwparams = (FlatWorldGenerator)params;
        String generatorString = this.fwparams.getGeneratorString();
        generatorString = generatorString.replace("%ESC", "\"");
        this.fwparams.setGeneratorString(generatorString);
        return true;
    }

    @Override
    public boolean createWorld(MissionInit missionInit)
    {
        long seed = DefaultWorldGeneratorImplementation.getWorldSeedFromString(this.fwparams.getSeed());

        try{
            LogManager.getLogger().info("Creating flat world");
            Properties props = new Properties();
            props.setProperty("level-type", "flat");
            props.setProperty("level-seed", String.valueOf(seed));
            props.setProperty("generator-settings", this.fwparams.getGeneratorString());
            WorldUtil.createLevelFlat(false, seed, Difficulty.NORMAL, props);
            return true;
        } catch (RuntimeException e) {
            LogManager.getLogger().error(e);
            return false;
        }
    }

    @Override
    public boolean shouldCreateWorld(MissionInit missionInit, Object genProps)
    {
        if (this.fwparams != null && this.fwparams.isForceReset())
            return true;

        if (MinecraftClient.getInstance().world == null || genProps == null)
            return true;    // Definitely need to create a world if there isn't one in existence!

        if (FlatWorldGenerator.class.isInstance(genProps)){
            FlatWorldGenerator oldProps = (FlatWorldGenerator)genProps;
            if (this.fwparams.getGeneratorString() != oldProps.getGeneratorString())
                return true;
            if (this.fwparams.getSeed() != oldProps.getSeed())
                return true;
            return false;
        }
        return true;
    }

    @Override
    public String getErrorDetails()
    {
        return "";  // Currently no error exit points, so never anything to report.
    }

    @Override
    public Object getOptions() {
        return fwparams;
    }
}
