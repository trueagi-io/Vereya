package io.singularitynet.Client;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

public class Window {
    public static void centerOnScreen(long window) {
        // Get the size of the primary monitor
        GLFWVidMode vidmode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        int monitorWidth = vidmode.width();
        int monitorHeight = vidmode.height();

        // Get the size of the window
        int[] windowWidth = new int[1];
        int[] windowHeight = new int[1];
        GLFW.glfwGetWindowSize(window, windowWidth, windowHeight);

        // Check if the window is partially outside of the screen
        int[] xpos = new int[1];
        int[] ypos = new int[1];
        GLFW.glfwGetWindowPos(window, xpos, ypos);
        if (xpos[0] < 0 || ypos[0] < 0 || xpos[0] + windowWidth[0] > monitorWidth || ypos[0] + windowHeight[0] > monitorHeight) {
            // Center the window
            GLFW.glfwSetWindowPos(window, (monitorWidth - windowWidth[0]) / 2, (monitorHeight - windowHeight[0]) / 2);
        }
    }
}
