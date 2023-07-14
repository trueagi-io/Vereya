package io.singularitynet.MissionHandlers;


import io.singularitynet.IVereyaMessageListener;
import io.singularitynet.Server.VereyaModServer;
import io.singularitynet.VereyaMessage;
import io.singularitynet.VereyaMessageType;
import io.singularitynet.SidesMessageHandler;
import io.singularitynet.mixin.MobEntityAccessorMixin;
import io.singularitynet.projectmalmo.ContinuousMovementCommand;
import io.singularitynet.projectmalmo.ContinuousMovementCommands;
import io.singularitynet.projectmalmo.MissionInit;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.control.JumpControl;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/** Class which overrides movement of the Minecraft player and exposes control of it to external agents.<br>
 * This allows the player to act as a robot with the ability to move backwards/forwards, strafe left/right, and turn clockwise/anticlockwise,
 * with a camera that is able to pivot up/down but not turn independently of the agent's body.
 */
public class CommandForWheeledRobotNavigationMobImplementationServer extends CommandBase implements IVereyaMessageListener
{
    @Override
    public void onMessage(VereyaMessageType messageType, Map<String, String> data) {
        throw new RuntimeException("calling client-side message handler on server " + messageType.toString());
    }

    @Override
    public void onMessage(VereyaMessageType messageType, Map<String, String> data, ServerPlayerEntity player) {
        CommandForWheeledRobotNavigationMobImplementation.MotionMessage msg = new CommandForWheeledRobotNavigationMobImplementation.MotionMessage(data);
        LOGGER.debug("InventoryCommandsImplementationServer.onMessage: " + msg);
        runCommand(msg);
    }

    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit) {
        return false;
    }

    float maxAngularVelocityDegreesPerSecond = 180;
    private class InternalFields {
        float maxAngularVelocityDegreesPerSecond=CommandForWheeledRobotNavigationMobImplementationServer.this.maxAngularVelocityDegreesPerSecond;
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

    private static final Logger LOGGER = LogManager.getLogger(CommandForWheeledRobotNavigationMobImplementationServer.class.getName());

    public static final String ON_COMMAND_STRING = "1";
    public static final String OFF_COMMAND_STRING = "0";

    /** Custom MoveControl
     *
     */
    class AIMoveControl extends MoveControl {
        InternalFields fields;
        public AIMoveControl(MobEntity entity, InternalFields fields1) {
            super(entity);
            fields = fields1;
        }

        public void tick(){
            if (CommandForWheeledRobotNavigationMobImplementationServer.this.updateState(entity, fields)) {
                float speed = fields.mVelocity;
                float sidewaysMovement = fields.mStrafeVelocity;
                float f = (float) this.entity.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                float g = speed * f;
                this.entity.setMovementSpeed(f);
                this.entity.setForwardSpeed(g);
                this.entity.setSidewaysSpeed(sidewaysMovement * f);
            } else {
                super.tick();
            }
        }
    }

    class AIJumpControl extends JumpControl {
        InternalFields fields;
        protected MobEntity myentity;

        public AIJumpControl(MobEntity entity, InternalFields fields1) {
            super(entity);
            myentity = entity;
            fields = fields1;
        }

        public void tick() {
            this.myentity.setJumping(this.fields.jumping);
            this.active = false;  // this is used in rabbit
        }
    }

    public CommandForWheeledRobotNavigationMobImplementationServer()
    {
        init();
    }

    private void init()
    {
        motionParams = new HashMap<>();
        ServerEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(this::onEntityUnoad);
        LOGGER.info("Installing CommandForWheeledRobotNavigationMobServer");
        SidesMessageHandler.client2server.registerForMessage(this, VereyaMessageType.CLIENT_MOVE);
    }

    private void onEntityUnoad(Entity entity, ServerWorld clientWorld) {
        if (entity instanceof MobEntity) {
            String uuid = entity.getUuidAsString();
            if (motionParams.containsKey(uuid)) {
                LOGGER.info("removed controllable mob: " + entity.getUuidAsString() + " " + entity.getType().getUntranslatedName());
                motionParams.remove(uuid);
            }
        }
    }

    private void onEntityLoad(Entity entity, ServerWorld clientWorld) {
        if (entity instanceof MobEntity) {
            MobEntity mobEntity = (MobEntity) entity;
            if (mobEntity.isAiDisabled()){
                LOGGER.info("created motion params for: " + mobEntity.getUuidAsString() + " " + mobEntity.getType().getUntranslatedName());
                InternalFields fields = new InternalFields();
                this.motionParams.put(mobEntity.getUuidAsString(), fields);
                ((MobEntityAccessorMixin)mobEntity).setMoveControl(new AIMoveControl(mobEntity, fields));
                ((MobEntityAccessorMixin)mobEntity).setJumpControl(new AIJumpControl(mobEntity, fields));
            }
        }
    }

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null)
            return false;

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
    protected boolean updateState(MobEntity entity, InternalFields fields)
    {
        if (!fields.overrideKeyboardInput) {
            return false;   // Let the class do the default thing.
        }
        // Update movement:
        fields.mTicksSinceLastVelocityChange++;
        if (fields.mTicksSinceLastVelocityChange <= fields.mInertiaTicks) {
            fields.mVelocity += (fields.mTargetVelocity - fields.mVelocity) * ((float) fields.mTicksSinceLastVelocityChange / (float) fields.mInertiaTicks);
        } else {
            fields.mVelocity = fields.mTargetVelocity;
        }
        updateYawAndPitch(entity, fields);
        return true;
    }

    /** Called to turn the robot / move the camera.
     */
    public void updateYawAndPitch(MobEntity entity, InternalFields fields)
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

    public boolean runCommand(CommandForWheeledRobotNavigationMobImplementation.MotionMessage msg)
    {
        String verb = msg.getData().get("message");
        if (verb == null || verb.length() == 0)
        {
            return false;
        }

        String entity_uuid = msg.getUuid();
        String parameter = msg.getValue();
        InternalFields current = motionParams.get(entity_uuid);
        if (current == null){
            return false;
        }

        MobEntity mobEntity = VereyaModServer.getInstance().getControlledMobs().get(entity_uuid);
        LOGGER.info("processing mob " + mobEntity.getUuidAsString());
        LOGGER.info("canMove: " + mobEntity.canMoveVoluntarily());

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
    }

    @Override
    public void deinstall(MissionInit missionInit)
    {
        SidesMessageHandler.client2server.deregisterForMessage(this, VereyaMessageType.CLIENT_MOVE);
        this.motionParams.clear();
    }
}
