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

import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.apache.commons.logging.Log;
import org.apache.logging.log4j.LogManager;
import io.singularitynet.mixin.GameOptionsMixin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/** KeyBinding subclass which opens up the Minecraft keyhandling to external agents for a particular key.<br>
 * If this class is set to override control, it will prevent the KeyBinding baseclass methods from being called,
 * and will instead provide its own interpretation of the state of the keyboard. This allows it to respond to command
 * messages sent externally.<br>
 * Note that one instance of this class overrides one key.<br>
 * Note also that the attack key and use key - although bound to the mouse rather than the keyboard, by default,
 * are implemented in Minecraft using this same KeyBinding object, so this mechanism allows us to control them too.
 */
public class CommandForKey extends CommandBase
{
    public static final String DOWN_COMMAND_STRING = "1";
    public static final String UP_COMMAND_STRING = "0";

    public interface KeyEventListener
    {
        public void onKeyChange(String commandString, boolean pressed);
    }

    public class KeyHook extends KeyBinding
    {
        /**
         * Tracks whether or not this object is overriding the default Minecraft
         * keyboard handling.
         */
        private boolean isOverridingPresses = false;
        private boolean isDown = false;
        private int justPressed = 0;
        private String commandString = null;
        private KeyEventListener observer = null;

        /** Create a KeyBinding object for the specified key, keycode and category.<br>
         * @param translationKey see Minecraft KeyBinding class
         * @param type see Minecraft KeyBinding class
         * @param code
         * @param category see Minecraft KeyBinding class
         */
        public KeyHook(String translationKey, InputUtil.Type type, int code, String category)
        {
            super(translationKey, type, code, category);
        }

        /** Set our "pressed" state to true and "down" state to true.<br>
         * This provides a means to set the state externally, without anyone actually having to press a key on the keyboard.
         */
        public void press()
        {
            this.isDown = true;
            this.justPressed = 1;
        }

        /** Set our "down" state to false.<br>
         * This provides a means to set the state externally, without anyone actually having to press a key on the keyboard.
         */
        public void release()
        {
            this.isDown = false;
            this.justPressed = 0;
        }

        private boolean isJustPressed(){
            if (this.justPressed > 0){
                this.justPressed --;
                return true;
            }
            return false;
        }

        /**
         * Return true if this key is "was pressed"<br>
         * This is used for one-shot responses in Minecraft - ie wasPressed()
         * will only return true once, even if isPressed is still returning
         * true. If this object is not currently set to override, the default
         * Minecraft keyboard handling will be used.
         *
         * @return true if the key has been pressed since the last time this was
         *         called.
         */
        @Override
        public boolean wasPressed()
        {
            boolean result = super.wasPressed();
            boolean current = isJustPressed();
            if (isOverridingPresses){
                if (result != current)
                    LogManager.getLogger().trace(this.getTranslationKey() + this.hashCode() + " overriding wasPressed " + result + " to " + current);
                result = current;
            }
            LogManager.getLogger().trace(this.getTranslationKey() + this.hashCode() + " was pressed: " + result);
            return result;
        }

        /**
         * Return true if this key is "pressed"<br>
         * Unlike wasPressed this method will return true as long as the corresponding flag is not set to false,
         * i.g. the key is released. If this object is not currently set to override, the default
         * Minecraft keyboard handling will be used.
         *
         * @return true if the key has been pressed since the last time this was
         *         called.
         */
        @Override
        public boolean isPressed()
        {
            boolean result = super.isPressed();
            boolean current = this.isDown;
            if (isOverridingPresses){
                if(result != current)
                    LogManager.getLogger().trace(this.getTranslationKey() + this.hashCode() + " overriding isPressed " + result + " to " + current);
                result = current;
            }
            LogManager.getLogger().trace(this.getTranslationKey() + this.hashCode() + " is pressed: " + result);
            return result;
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            if (!this.isOverridingPresses && this.observer != null)
                this.observer.onKeyChange(this.getCommandString(), pressed);
            LogManager.getLogger().trace(this.getTranslationKey() + this.hashCode() + " setPressed: " + pressed);
        }

        /**
         * Construct a command string from our internal key description.<br>
         * This is the command that we expect to be given from outside in order
         * to control our state.<br>
         * For example, the second hotbar key ("2" on the keyboard, by default)
         * will have a description of "key.hotbar.2", which will result in a
         * command string of "hotbar.2".<br>
         * To "press" and "release" this key, the agent needs to send
         * "hotbar.2 1" followed by "hotbar.2 0".
         *
         * @return the command string, parsed from the key's description.
         */
        private String getCommandString()
        {
            if (this.commandString == null)
            {
                this.commandString = this.getTranslationKey(); // getKeyDescription();
                int splitpoint = this.commandString.indexOf("."); // Descriptions
                // are
                // "key.whatever"
                // - remove
                // the "key."
                // part.
                if (splitpoint != -1 && splitpoint != this.commandString.length())
                {
                    this.commandString = this.commandString.substring(splitpoint + 1);
                }
            }
            return this.commandString;
        }

        /**
         * Attempt to handle this command string, if relevant.
         *
         * @param verb
         *            the command to handle. eg "attack 1" means
         *            "press the attack key".
         * @param parameter
         *            "1" or "0" is expected
         * @return true if the command was relevant and was successfully
         *         handled; false otherwise.
         */
        public boolean execute(String verb, String parameter)
        {
            if (verb != null && verb.equalsIgnoreCase(getCommandString()))
            {
                if (parameter != null && parameter.equalsIgnoreCase(DOWN_COMMAND_STRING))
                {
                    press();
                }
                else if (parameter != null && parameter.equalsIgnoreCase(UP_COMMAND_STRING))
                {
                    release();
                }
                else
                {
                    return false;
                }
                return true;
            }
            return false;
        }

        public void setObserver(KeyEventListener observer)
        {
            this.observer = observer;
        }


    }

    private KeyHook keyHook = null;
    private KeyBinding originalBinding = null;
    private int originalBindingIndex;
    private String keyDescription;

    /** Helper function to create a KeyHook object for a given KeyBinding object.
     * @param key the Minecraft KeyBinding object we are wrapping
     * @return an ExternalAIKey object to replace the original Minecraft KeyBinding object
     */
    private KeyHook create(KeyBinding key)
    {
        LogManager.getLogger().debug("Creating KeyHook for " + key.getTranslationKey());
        if (key != null && key instanceof KeyHook)
        {
            return (KeyHook)key; // Don't create a KeyHook to replace this KeyBinding, since that has already been done at some point.
            // (Minecraft keeps a pointer to every KeyBinding that gets created, and they never get destroyed - so we don't want to create
            // any more than necessary.)
        }
        return new KeyHook(key.getTranslationKey(),
                key.getDefaultKey().getCategory(), key.getDefaultKey().getCode(), key.getCategory());
    }

    /** Create an ICommandHandler interface for the specified key.
     * @param key the description of the key we want to provide commands to.
     */
    public CommandForKey(String key)
    {
        this.keyDescription = key;
    }

    /** Is this object currently overriding the default Minecraft KeyBinding object?
     * @return true if this object is overriding the default keyboard handling.
     */
    @Override
    public boolean isOverriding()
    {
        return (this.keyHook != null) ? this.keyHook.isOverridingPresses : false;
    }

    /** Switch this object "on" or "off".
     * @param b true if this object is to start overriding the normal Minecraft handling.
     */
    @Override
    public void setOverriding(boolean b)
    {
        if (this.keyHook != null)
        {
            this.keyHook.isDown = false;
            this.keyHook.justPressed = 0;
            this.keyHook.isOverridingPresses = b;
            LogManager.getLogger().debug("set isOverriding for " + this.keyDescription + " " + this.keyHook + " to " + b);
        }
    }

    public void setKeyEventObserver(KeyEventListener observer)
    {
        if (this.keyHook == null){
            throw new IllegalStateException("setKeyEventObserver for " + this.keyDescription + " called before install");
        }
        this.keyHook.setObserver(observer);
    }

    @Override
    public void install(MissionInit missionInit)
    {
        LogManager.getLogger().debug("Installing CommandForKey for " + this.keyDescription);
        // Attempt to find the keybinding that matches the description we were given,
        // and replace it with our own KeyHook object:
        GameOptions settings = MinecraftClient.getInstance().options;
        boolean createdHook = false;
        // GameSettings contains both a field for each KeyBinding (eg keyBindAttack), and an array of KeyBindings with a pointer to
        // each field. We want to make sure we replace both pointers, otherwise Minecraft will end up using our object for some things, and
        // the original object for others.
        // So we need to use reflection to replace the field:
        Field[] fields = GameOptions.class.getFields();
        for (int i = 0; i < fields.length; i++)
        {
            Field f = fields[i];
            if (f.getType() == KeyBinding.class)
            {
                KeyBinding kb;
                try
                {
                    kb = (KeyBinding)(f.get(settings));
                    LogManager.getLogger().debug("checking " + kb.getTranslationKey());
                    if (kb != null && kb.getTranslationKey().equals(this.keyDescription))
                    {
                        this.originalBinding = kb;
                        this.keyHook = create(this.originalBinding);
                        createdHook = true;
                        // something like key.attack or key.hotbar.2
                        String keyString = kb.getTranslationKey();

                        // skip hotbar for now
                        if (keyString.contains("hotbar")) continue;
                        if (! keyString.startsWith("key.")) continue;
                        // generate minin method name
                        String base = keyString.split("\\.")[1];
                        // handle swapOffhand, it's a special case
                        if (base.equals("swapOffhand")) base = "swapHands";
                        base = base.substring(0, 1).toUpperCase(Locale.ROOT) + base.substring(1);
                        Class[] cArg = new Class[1];
                        cArg[0] = KeyBinding.class;
                        // now use GameOptionsMixin to override corresponding fields
                        Method setter = GameOptionsMixin.class.getDeclaredMethod("setKey" + base, cArg);
                        Method getter = GameOptionsMixin.class.getDeclaredMethod("getKey" + base);
                        setter.invoke(settings, this.keyHook);
                        assert (getter.invoke(settings).getClass().isInstance(KeyHook.class));
                    }
                }
                catch (IllegalArgumentException e)
                {
                    LogManager.getLogger().error("can't overwrite field", e);
                }
                catch (IllegalAccessException e)
                {
                    LogManager.getLogger().error("can't overwrite field", e);
                } catch (NoSuchMethodException e) {
                    LogManager.getLogger().error("can't overwrite field", e);
                } catch (InvocationTargetException e) {
                    LogManager.getLogger().error("can't overwrite field", e);
                }
            }
        }
        // And then we replace the pointer in the array:
        for (int i = 0; i < settings.allKeys.length; i++)
        {
            if (settings.allKeys[i].getTranslationKey().equals(this.keyDescription))
            {
                this.originalBindingIndex = i;
                if (!createdHook)
                {
                    this.originalBinding = settings.allKeys[i];
                    this.keyHook = create(this.originalBinding);
                    createdHook = true;
                }
                settings.allKeys[i] = this.keyHook;
            }
        }
        // And possibly in the hotbar array too:
        for (int i = 0; i < settings.hotbarKeys.length; i++)
        {
            if (settings.hotbarKeys[i].getTranslationKey().equals(this.keyDescription))
            {
                this.originalBindingIndex = i;
                if (!createdHook)
                {
                    this.originalBinding = settings.hotbarKeys[i];
                    this.keyHook = create(this.originalBinding);
                    createdHook = true;
                }
                settings.hotbarKeys[i] = this.keyHook;
            }
        }
        KeyBinding attack = settings.attackKey;
        if (createdHook) {
            LogManager.getLogger().debug("KeyHook installed for " + this.keyDescription);
        } else {
            LogManager.getLogger().debug("KeyHook not installed for " + this.keyDescription);
        }
        LogManager.getLogger().debug("attack overriden " + KeyHook.class.isInstance(attack) + attack.hashCode());
    }

    @Override
    public void deinstall(MissionInit missionInit)
    {
        // Do nothing - it's not a simple thing to deinstall ourselves, as Minecraft will keep pointers to us internally,
        // and will end up confused. It's safer simply to stay hooked in. As long as overriding is turned off, the game
        // will behave normally anyway.
    }

    @Override
    public boolean onExecute(String verb, String parameter, MissionInit missionInit)
    {
        // Our keyhook does all the work:
        return (this.keyHook != null) ? this.keyHook.execute(verb, parameter) : false;
    }

    /** Return the KeyBinding object we are using.<br>
     * Mainly provided for the use of the unit tests.
     * @return our internal KeyBinding object.
     */
    public KeyBinding getKeyBinding()
    {
        return this.keyHook;
    }
}