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
                            default -> {}
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

}