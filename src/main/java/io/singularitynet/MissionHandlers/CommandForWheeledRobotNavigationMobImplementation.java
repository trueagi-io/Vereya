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


import io.singularitynet.Client.VereyaModClient;
import io.singularitynet.MalmoMessageType;
import io.singularitynet.SidesMessageHandler;
import io.singularitynet.projectmalmo.ContinuousMovementCommand;
import io.singularitynet.projectmalmo.ContinuousMovementCommands;
import io.singularitynet.projectmalmo.MissionInit;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;

/** Class which overrides movement of the Minecraft player and exposes control of it to external agents.<br>
 * This allows the player to act as a robot with the ability to move backwards/forwards, strafe left/right, and turn clockwise/anticlockwise,
 * with a camera that is able to pivot up/down but not turn independently of the agent's body.
 */
public class CommandForWheeledRobotNavigationMobImplementation extends CommandBase
{

    float maxAngularVelocityDegreesPerSecond = 180;
    private class InternalFields {
        MobEntity entity;
        float maxAngularVelocityDegreesPerSecond=CommandForWheeledRobotNavigationMobImplementation.this.maxAngularVelocityDegreesPerSecond;
        boolean overrideKeyboardInput=true;
        float mVelocity = 0;
        float mStrafeVelocity = 0;
        float mTargetVelocity = 0;
        int mInertiaTicks = 6;  // Number of ticks it takes to move from current velocity to target velocity.
        int mTicksSinceLastVelocityChange = 0;
        float mCameraPitch = 0;
        float pitchScale = 0;
        float mYaw = 0;
        float yawScale = 0;
        long lastAngularUpdateTime;
        boolean jumping = false;
        boolean sneaking = false;
    };

    private Map<String, InternalFields> motionParams;

    private Input overrideMovement = null;
    private Input originalMovement = null;

    public static final String ON_COMMAND_STRING = "1";
    public static final String OFF_COMMAND_STRING = "0";

    /** Custom MoveControl
     *
     */
    @Environment(value=EnvType.SERVER)
    class AIMoveControl extends MoveControl {

        public AIMoveControl(MobEntity entity) {
            super(entity);
        }
    }
    /** Small MovementInput class that calls our own movement handling code.
     * This object is used by Minecraft to decide how to move the player.
     */
    @Environment(value= EnvType.CLIENT)
    public class AIMovementInput extends KeyboardInput {
        private int idx;
        public AIMovementInput(GameOptions settings, int idx) {
            super(settings);
            idx = idx;
        }

        @Override
        public void tick(boolean slowDown, float f) {
            if (CommandForWheeledRobotNavigationMobImplementation.this.updateState()) {
                this.jumping = CommandForWheeledRobotNavigationMobImplementation.this.motionParams[idx].jumping;
                this.sneaking = CommandForWheeledRobotNavigationMobImplementation.this.motionParams[idx].sneaking;
                this.movementForward = CommandForWheeledRobotNavigationMobImplementation.this.motionParams[idx].mVelocity;
                this.movementSideways = CommandForWheeledRobotNavigationMobImplementation.this.motionParams[idx].mStrafeVelocity;
                if (slowDown) {
                    this.movementSideways = (float) ((double) this.movementSideways * 0.3);
                    this.movementForward = (float) ((double) this.movementForward * 0.3);
                }
            } else {
                super.tick(slowDown, f);
            }
        }
    }

    public CommandForWheeledRobotNavigationMobImplementation()
    {
        init();
    }

    private void init()
    {
        motionParams = new HashMap<>();
        ClientEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
        ClientEntityEvents.ENTITY_UNLOAD.register(this::onEntityUnoad);
    }

    private void onEntityUnoad(Entity entity, ClientWorld clientWorld) {
        if (entity instanceof MobEntity) {
            String uuid = entity.getUuidAsString();
            if (motionParams.containsKey(uuid)) {
                motionParams.remove(uuid);
            }
        }
    }

    private void onEntityLoad(Entity entity, ClientWorld clientWorld) {
        if (entity instanceof MobEntity) {
            MobEntity mobEntity = (MobEntity) entity;
            if (mobEntity.isAiDisabled()){
                this.motionParams.put(mobEntity.getUuidAsString(), new InternalFields());
            }
        }
    }

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null)
            return false;

        this.updateMotionParams();
        if (params instanceof ContinuousMovementCommands) {
            ContinuousMovementCommands cmparams = (ContinuousMovementCommands) params;
            this.maxAngularVelocityDegreesPerSecond = cmparams.getTurnSpeedDegs().floatValue();
            setUpAllowAndDenyLists(cmparams.getModifierList());
        }
        return true;
    }

    /** Called by our overridden MovementInputFromOptions class.
     * @return true if we've handled the movement; false if the MovementInputFromOptions class should delegate to the default handling.
     */
    protected boolean updateState()
    {
        for (InternalFields fields: motionParams.values()) {
            if (!fields.overrideKeyboardInput) {
                continue;   // Let the class do the default thing.
            }
            // Update movement:
            fields.mTicksSinceLastVelocityChange++;
            if (fields.mTicksSinceLastVelocityChange <= fields.mInertiaTicks) {
                fields.mVelocity += (fields.mTargetVelocity - fields.mVelocity) * ((float) fields.mTicksSinceLastVelocityChange / (float) fields.mInertiaTicks);
            } else {
                fields.mVelocity = fields.mTargetVelocity;
            }
            updateYawAndPitch(fields);
        }
        return true;
    }

    /** Called to turn the robot / move the camera.
     */
    public void updateYawAndPitch(InternalFields fields)
    {
        // Work out the time that has elapsed since we last updated the values.
        // (We need to do this because we can't guarantee that this method will be
        // called at a constant frequency.)
        long timeNow = System.currentTimeMillis();
        long deltaTime = timeNow - fields.lastAngularUpdateTime;
        fields.lastAngularUpdateTime = timeNow;

        // Work out how much the yaw and pitch should have changed in that time:
        double overclockScale = 1;
        double deltaYaw = fields.yawScale * overclockScale * fields.maxAngularVelocityDegreesPerSecond * (deltaTime / 1000.0);
        double deltaPitch = fields.pitchScale * overclockScale * fields.maxAngularVelocityDegreesPerSecond * (deltaTime / 1000.0);

        // And update them:
        fields.mYaw += deltaYaw;
        fields.mCameraPitch += deltaPitch;
        fields.mCameraPitch = (fields.mCameraPitch < -90) ? -90 : (fields.mCameraPitch > 90 ? 90 : fields.mCameraPitch);    // Clamp to [-90, 90]

        // And update the player:
        MobEntity entity = fields.entity;
        if (entity != null)
        {
            entity.setPitch(fields.mCameraPitch);
            entity.setYaw(fields.mYaw);
            entity.setPitch(MathHelper.clamp(entity.getPitch(), -90.0f, 90.0f));
        }
    }

    @Override
    public boolean isOverriding()
    {
        return true;
    }

    @Override
    public void setOverriding(boolean b) {

    }

    @Override
    public boolean onExecute(String verb, String parameter, MissionInit missionInit)
    {
        if (verb == null || verb.length() == 0)
        {
            return false;
        }

        String[] params = parameter.split(" ");
        if (params.length != 2) {
            return false;
        }

        String entity_uuid = params[0];
        parameter = params[1];

        InternalFields current = motionParams.get(entity_uuid);
        if (current == null){
            return false;
        }

        // Now parse the command:
        if (verb.equalsIgnoreCase(ContinuousMovementCommand.MOVE.value()))
        {
            float targetVelocity = clamp(Float.valueOf(parameter));
            if (targetVelocity != current.mTargetVelocity)
            {
                current.mTargetVelocity = targetVelocity;
                current.mTicksSinceLastVelocityChange = 0;
            }
            return true;
        }
        else if (verb.equalsIgnoreCase(ContinuousMovementCommand.STRAFE.value()))
        {
            current.mStrafeVelocity = -clamp(Float.valueOf(parameter));  // Strafe values need to be reversed for Malmo mod.
            return true;
        }
        else if (verb.equalsIgnoreCase(ContinuousMovementCommand.PITCH.value()))
        {
            current.pitchScale = clamp(Float.valueOf(parameter));
            current.lastAngularUpdateTime = System.currentTimeMillis();
            return true;
        }
        else if (verb.equalsIgnoreCase(ContinuousMovementCommand.TURN.value()))
        {
            current.yawScale = clamp(Float.valueOf(parameter));
            current.lastAngularUpdateTime = System.currentTimeMillis();
            return true;
        }
        else
        {
            // Boolean commands - either on or off.
            boolean value = parameter.equalsIgnoreCase(ON_COMMAND_STRING);
            if (verb.equals(ContinuousMovementCommand.JUMP.value()))
            {
                current.jumping = value;
                return true;
            }
            else if (verb.equalsIgnoreCase(ContinuousMovementCommand.CROUCH.value()))
            {
                current.sneaking = value;
                return true;
            }
        }

        return false;
    }

    private float clamp(float f)
    {
        return (f < -1) ? -1 : ((f > 1) ? 1 : f);
    }

    @Override
    public void install(MissionInit missionInit)
    {
        // Create our movement hook, which allows us to override the Minecraft movement.
        this.overrideMovement = new AIMovementInput(MinecraftClient.getInstance().options, 0);
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null)
        {
            // Insert it into the player, keeping a record of the original movement object
            // so we can restore it later.
            this.originalMovement = player.input;
            player.input = this.overrideMovement;
        }
    }

    @Override
    public void deinstall(MissionInit missionInit)
    {
        // Restore the player's normal movement control:
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null)
        {
            player.input = this.originalMovement;
        }

        // MinecraftForge.EVENT_BUS.unregister(this);
    }

    /** Provide access to the MovementInput object we are using to control the player.<br>
     * This is required by the unit tests.
     * @return our MovementInput object.
     */
    public Input getMover()
    {
        return this.overrideMovement;
    }

}
