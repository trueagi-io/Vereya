package io.singularitynet.MissionHandlerInterfaces;

public interface IVideoProducer {
    /** Get the requested width of the video frames returned.*/
    public int getWidth();

    /** Get the requested height of the video frames returned.*/
    public int getHeight();
}
