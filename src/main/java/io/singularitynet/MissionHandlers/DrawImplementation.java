package io.singularitynet.MissionHandlers;

import io.singularitynet.projectmalmo.*;
import jakarta.xml.bind.JAXBElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.apache.logging.log4j.LogManager;

import java.util.List;

public class DrawImplementation extends DrawingDecorator {
    public static void draw(List<Object> worldDecorator) {
        //get command manager and command source
        CommandManager commandManager = null;
        ServerCommandSource commandSource= null;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            commandManager = client.getServer().getCommandManager();
            commandSource = client.getServer().getCommandSource();
        } catch (Exception e) {
            LogManager.getLogger().error("Failed to get command manager and command source");
            LogManager.getLogger().error(e);
        }
        try {
                for (Object obj : worldDecorator){
                    List<JAXBElement<? extends DrawObjectType>> drawObjects = null;
                    if (obj instanceof DrawingDecorator){
                        drawObjects = ((DrawingDecorator)obj).getDrawObjectType();
                    }
                    for (JAXBElement<? extends DrawObjectType> drawObject : drawObjects){
                        switch (drawObject.getValue()) {
                            case DrawBlock db -> drawBlock(db, commandManager, commandSource);
                            case DrawCuboid dc -> drawCuboid(dc, commandManager, commandSource);
                            case DrawItem di -> drawItem(di, commandManager, commandSource);
                            case DrawLine dl -> drawLine(dl, commandManager, commandSource);
//                            case DrawSphere ds -> drawSphere(ds, commandManager, commandSource);
                            default -> LogManager.getLogger().warn("The type " + drawObject.getClass().getSimpleName() + " is not supported");
                        }
                    }
                }
        } catch (Exception e) {
            LogManager.getLogger().error("Failed to draw objects");
            LogManager.getLogger().error(e);
        }
    }

    public static void drawBlock(DrawBlock val, CommandManager commandManager, ServerCommandSource commandSource){
        try {
            String command = "/setblock " + val.getX() + " " +
                    val.getY() + " " +
                    val.getZ() + " " +
                    val.getType().value();
            commandManager.executeWithPrefix(commandSource, command);
        } catch (Exception e) {
            LogManager.getLogger().error("Failed to draw the block");
            LogManager.getLogger().error(e);
        }
    }

    public static void drawCuboid(DrawCuboid val, CommandManager commandManager, ServerCommandSource commandSource){
        try {
            String command = "/fill " + val.getX1() + " " +
                    val.getY1() + " " +
                    val.getZ1() + " " +
                    val.getX2() + " " +
                    val.getY2() + " " +
                    val.getZ2() + " " +
                    val.getType().value();
            commandManager.executeWithPrefix(commandSource, command);
        } catch (Exception e) {
            LogManager.getLogger().error("Failed to draw the cuboid");
            LogManager.getLogger().error(e);
        }
    }

    public static void drawItem(DrawItem val, CommandManager commandManager, ServerCommandSource commandSource){
        try {
            String command = "/summon item " + val.getX() + " " +
                    val.getY() + " " +
                    val.getZ() + " " +
                    "{Item:{id:" + val.getType() + ",Count:1}}";
            commandManager.executeWithPrefix(commandSource, command);
        } catch (Exception e) {
            LogManager.getLogger().error("Failed to draw the item");
            LogManager.getLogger().error(e);
        }
    }

    public static void drawLine(DrawLine val, CommandManager commandManager, ServerCommandSource commandSource){
        try {
            int dx = val.getX2() - val.getX1();
            int dy = val.getY2() - val.getY1();
            int dz = val.getZ2() - val.getZ1();

            int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));

            double xInc = (double)dx / steps;
            double yInc = (double)dy / steps;
            double zInc = (double)dz / steps;

            for (int i = 0; i <= steps; i++){
                int x = (int)Math.round(val.getX1() + i * xInc);
                int y = (int)Math.round(val.getY1() + i * yInc);
                int z = (int)Math.round(val.getZ1() + i * zInc);
                setBlock(x, y, z, val.getType().value(), commandManager, commandSource);
            }

        } catch (Exception e) {
            LogManager.getLogger().error("Failed to draw the line");
            LogManager.getLogger().error(e);
        }
    }

    protected static void setBlock(int x, int y, int z, String type, CommandManager commandManager, ServerCommandSource commandSource){
        try {
            String command = "/setblock " + x + " " +
                    y + " " +
                    z + " " +
                    type;
            commandManager.executeWithPrefix(commandSource, command);
        } catch (Exception e) {
            LogManager.getLogger().error("Failed to set the block");
            LogManager.getLogger().error(e);
        }
    }
}