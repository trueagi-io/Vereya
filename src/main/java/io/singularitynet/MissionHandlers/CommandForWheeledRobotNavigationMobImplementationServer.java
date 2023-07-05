package io.singularitynet.MissionHandlers;


import io.singularitynet.IVereyaMessageListener;
import io.singularitynet.VereyaMessage;
import io.singularitynet.MalmoMessageType;
import io.singularitynet.SidesMessageHandler;
import io.singularitynet.mixin.MobEntityAccessorMixin;
import io.singularitynet.projectmalmo.ContinuousMovementCommand;
import io.singularitynet.projectmalmo.ContinuousMovementCommands;
import io.singularitynet.projectmalmo.MissionInit;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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
    public void onMessage(MalmoMessageType messageType, Map<String, String> data) {
        throw new RuntimeException("calling client-side message handler on server " + messageType.toString());
    }

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player) {
        CommandForWheeledRobotNavigationMobImplementationServer.MotionMessage msg = new CommandForWheeledRobotNavigationMobImplementationServer.MotionMessage(data);
        LOGGER.debug("InventoryCommandsImplementationServer.onMessage: " + msg);
        runCommand(msg);
    }

    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit) {
        return false;
    }

    public static class MotionMessage extends VereyaMessage {

        public MotionMessage(String parameters, String uuid, String value){
            super(MalmoMessageType.CLIENT_MOVE, parameters);
            this.getData().put("uuid", uuid);
            this.getData().put("value", value);
        }

        public MotionMessage(Map<String, String> data) {
            super(MalmoMessageType.CLIENT_MOVE, data.get("message"));
            this.getData().put("uuid", data.get("uuid"));
            this.getData().put("value", data.get("value"));
        }

        public String getUuid(){
            return this.getData().get("uuid");
        }

        public String getValue(){
            return this.getData().get("value");
        }

        public String getVerb(){
            return this.getData().get("message");
        }
    }

    float maxAngularVelocityDegreesPerSecond = 180;
    private class InternalFields {
        MobEntity entity;
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
    @Environment(value=EnvType.SERVER)
    class AIMoveControl extends MoveControl {
        InternalFields fields;
        public AIMoveControl(MobEntity entity) {
            super(entity);
            CommandForWheeledRobotNavigationMobImplementationServer.this.motionParams.get(entity.getUuidAsString());
        }

        public void tick(){
            if (CommandForWheeledRobotNavigationMobImplementationServer.this.updateState(fields)) {
                // from MoveControl.tick
                float f = (float) this.entity.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                float g = (float) this.speed * f;
                this.entity.setMovementSpeed(g);
                this.entity.setForwardSpeed(this.forwardMovement);
                this.entity.setSidewaysSpeed(this.sidewaysMovement);
            } else {
                super.tick();
            }
        }
    }

    @Environment(value=EnvType.SERVER)
    class AIJumpControl extends JumpControl {
        InternalFields fields;
        protected MobEntity myentity;

        public AIJumpControl(MobEntity entity) {
            super(entity);
            myentity = entity;
            CommandForWheeledRobotNavigationMobImplementationServer.this.motionParams.get(entity.getUuidAsString());
        }

        public void tick() {
            this.myentity.setJumping(this.active);
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
        SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_MOVE);
    }

    private void onEntityUnoad(Entity entity, ServerWorld clientWorld) {
        if (entity instanceof MobEntity) {
            String uuid = entity.getUuidAsString();
            if (motionParams.containsKey(uuid)) {
                motionParams.remove(uuid);
            }
        }
    }

    private void onEntityLoad(Entity entity, ServerWorld clientWorld) {
        if (entity instanceof MobEntity) {
            MobEntity mobEntity = (MobEntity) entity;
            if (mobEntity.isAiDisabled()){
                this.motionParams.put(mobEntity.getUuidAsString(), new InternalFields());
                ((MobEntityAccessorMixin)mobEntity).setMoveControl(new AIMoveControl(mobEntity));
                ((MobEntityAccessorMixin)mobEntity).setJumpControl(new AIJumpControl(mobEntity));
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
    protected boolean updateState(InternalFields fields)
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
        updateYawAndPitch(fields);
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

    public boolean runCommand(MotionMessage msg)
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
        SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_MOVE);
        this.motionParams.clear();
    }
}
