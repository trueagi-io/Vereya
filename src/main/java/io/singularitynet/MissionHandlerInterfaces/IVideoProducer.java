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

package io.singularitynet.MissionHandlerInterfaces;

import io.singularitynet.projectmalmo.MissionInit;

import java.nio.ByteBuffer;
import java.util.Map;

/** Interface for objects which are responsible for providing Minecraft video data.
 */
public interface IVideoProducer
{
    enum VideoType
    {
        VIDEO,
        DEPTH_MAP,
        LUMINANCE,
        COLOUR_MAP
    };

    /** Get the type of video frames returned.*/
    VideoType getVideoType();

    /**
     * Get a frame of video from Minecraft.
     *
     * @param missionInit the MissionInit object for the currently running mission, which may contain parameters for the video requirements.
     * @return an array of bytes representing this frame.<br>
     * (The format is unspecified; it is up to the IVideoProducer implementation and the agent to agree on how the data is formatted.)
     */
    int[] getFrame(MissionInit missionInit, ByteBuffer buffer);

    /** Get the requested width of the video frames returned.*/
    int getWidth();

    /** Get the requested height of the video frames returned.*/
    int getHeight();

    /** Called once before the mission starts - use for any necessary initialisation.*/
    void prepare(MissionInit missionInit);

    /** Called once after the mission ends - use for any necessary cleanup.*/
    void cleanup();
}