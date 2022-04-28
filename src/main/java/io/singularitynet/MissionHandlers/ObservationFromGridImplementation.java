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

import com.google.gson.JsonObject;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.GridDefinition;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromGrid;
import io.singularitynet.utils.JSONWorldDataHelper;
import io.singularitynet.utils.JSONWorldDataHelper.GridDimensions;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/** IObservationProducer that spits out block types of the cell around the player.<br>
 * The size of the cell can be specified in the MissionInit XML.
 * The default is (3x3x4) - a one block hull around the player.
 */
public class ObservationFromGridImplementation extends HandlerBase implements IObservationProducer {
    private List<SimpleGridDef> environs = null;

    @Override
    public void cleanup() {

    }

    @Override
    public void prepare(MissionInit missionInit) {

    }

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit currentMissionInit) {
        List<SimpleGridDef> environs = this.environs;
        if (environs != null)
        {
            for (SimpleGridDef sgd : environs)
            {
                JSONWorldDataHelper.buildGridData(json, sgd.getEnvirons(), MinecraftClient.getInstance().player, sgd.name);
            }
        }
    }


    public static class SimpleGridDef	// Could use the JAXB-generated GridDefinition class, but this is safer/simpler.
    {
        int xMin;
        int yMin;
        int zMin;
        int xMax;
        int yMax;
        int zMax;
        String name;
        boolean absoluteCoords;



        SimpleGridDef(int xmin, int ymin, int zmin, int xmax, int ymax, int zmax, String name, boolean absoluteCoords)
        {
            this.xMin = xmin;
            this.yMin = ymin;
            this.zMin = zmin;
            this.xMax = xmax;
            this.yMax = ymax;
            this.zMax = zmax;
            this.name = name;
            this.absoluteCoords = absoluteCoords;
        }
        GridDimensions getEnvirons()
        {
            GridDimensions env = new GridDimensions();
            env.xMax = this.xMax;
            env.yMax = this.yMax;
            env.zMax = this.zMax;
            env.xMin = this.xMin;
            env.yMin = this.yMin;
            env.zMin = this.zMin;
            env.absoluteCoords = this.absoluteCoords;
            return env;
        }
    }

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null || !(params instanceof ObservationFromGrid))
            return false;

        ObservationFromGrid ogparams = (ObservationFromGrid)params;
        this.environs = new ArrayList<SimpleGridDef>();
        for (GridDefinition gd : ogparams.getGrid())
        {
            SimpleGridDef sgd = new SimpleGridDef(
                    gd.getMin().getX().intValue(),
                    gd.getMin().getY().intValue(),
                    gd.getMin().getZ().intValue(),
                    gd.getMax().getX().intValue(),
                    gd.getMax().getY().intValue(),
                    gd.getMax().getZ().intValue(),
                    gd.getName(),
                    gd.isAbsoluteCoords());
            this.environs.add(sgd);
        }
        return true;
    }
}
