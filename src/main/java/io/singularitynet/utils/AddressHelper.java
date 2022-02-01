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

package io.singularitynet.utils;

/** Class that helps to track and centralise our various IP address and port requirements.<br>
 */
public class AddressHelper
{
	static public final int MIN_MISSION_CONTROL_PORT = 10000;
	static public final int MIN_FREE_PORT = 10100;
	static public final int MAX_FREE_PORT = 11000;
	static private int missionControlPortOverride = 0;
	static private int missionControlPort = 0;
	
	static public int getMissionControlPortOverride()
	{
		return AddressHelper.missionControlPortOverride;
	}
	
	/** Get the actual port used for mission control, assuming it has been set. (Will return 0 if not.)<br>
	 * @return the port in use for mission control.
	 */
	static public int getMissionControlPort()
	{
		return AddressHelper.missionControlPort;
	}

	public static void setMissionControlPort(int mcPort) {
		missionControlPort = mcPort;
	}
}