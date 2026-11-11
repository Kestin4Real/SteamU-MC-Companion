package be.voidguardians.steamu.input;

import be.voidguardians.steamu.config.CompanionConfig;
import be.voidguardians.steamu.config.CompanionConfig.Action;
import be.voidguardians.steamu.mixin.KeyMappingAccessor;
import be.voidguardians.steamu.net.ScreenClient;
import be.voidguardians.steamu.net.ScreenClient.Deck;
import be.voidguardians.steamu.ui.ControlsScreen;
import be.voidguardians.steamu.ui.DeckScreens;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.InputType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashSet;
import java.util.Set;

/**
 * Plays Minecraft with the Steam Deck's own controls, read straight from
 * SteamU (no Steam Input, no other controller mod needed).
 *
 * In game: left stick moves, right stick looks, everything else is a
 * rebindable {@link Action} (defaults in CompanionConfig, editable in the
 * SteamU Controls screen or config/steamu_companion.json).
 *
 * In menus on the PC (title screen, settings, death screen...): D-pad or
 * left stick moves the selection, A selects, B goes back, L1/R1 jump
 * between groups (like Tab). These are sent to Minecraft as the same key
 * events a keyboard would produce, so every vanilla menu works.
 *
 * Runs once per rendered frame on the client thread (MouseHandlerMixin).
 */
public final class ControllerHandler {
	public static final ControllerHandler INSTANCE = new ControllerHandler();

	private static final float MOVE_DEADZONE = 0.15f;
	private static final float LOOK_DEADZONE = 0.12f;
	private static final float TRIGGER_THRESHOLD = 0.35f;
	private static final float NAV_STICK_THRESHOLD = 0.5f;
	private static final double PITCH_RATIO = 220.0 / 320.0; // pitch a bit slower than yaw
	private static final long STALE_NANOS = 250_000_000L;
	private static final long REPEAT_DELAY_NANOS = 400_000_000L, REPEAT_RATE_NANOS = 110_000_000L;

	private ScreenClient client;
	private final Set<KeyMapping> held = new HashSet<>();
	private long previousButtons;
	private long lastFrameNanos;
	private volatile boolean moveActive;
	private volatile float moveLeft, moveForward;

	// Menu navigation key repeat
	private int navKey = -1;
	private long navNextNanos;

	private ControllerHandler() {}

	public void init(ScreenClient client) {
		this.client = client;
	}

	/** True while the left stick should replace keyboard movement. */
	public boolean overridesMovement() {
		return moveActive;
	}

	public float moveLeft() {
		return moveLeft;
	}

	public float moveForward() {
		return moveForward;
	}

	public void frame(Minecraft mc) {
		if (client == null) return;
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;
		CompanionConfig config = CompanionConfig.get();
		client.setGyroGate(config.mask(Action.GYRO_AIM));

		LocalPlayer player = mc.player;
		Screen screen = mc.gui.screen();
		boolean inGame = player != null && screen == null;
		// Exclusive = Steam's own mapping of the Deck controller is paused.
		// In menus only while Minecraft has focus, so the Deck still drives
		// the desktop when you switch to another window.
		boolean exclusive = inGame || (screen != null && mc.isWindowActive());
		client.setInputMode(client.display() != null, exclusive);

		ScreenClient.Controller c = client.controller();
		if (c == null || now - c.receivedNanos() > STALE_NANOS) {
			releaseAll();
			moveActive = false;
			navKey = -1;
			client.takeGyro();
			return;
		}
		// Analog triggers count as L2/R2 once pressed far enough.
		long buttons = c.buttons()
			| (c.rightTrigger() > TRIGGER_THRESHOLD ? Deck.R2 : 0)
			| (c.leftTrigger() > TRIGGER_THRESHOLD ? Deck.L2 : 0);
		long down = buttons | client.takeLatchedButtons(); // includes taps shorter than a frame
		long pressed = down & ~previousButtons;
		previousButtons = buttons;

		float[] move = deadzone(c.leftX(), c.leftY(), MOVE_DEADZONE);
		float[] look = deadzone(c.rightX(), c.rightY(), LOOK_DEADZONE);
		double[] gyro = client.takeGyro();
		if (buttons != 0 || move[0] != 0 || move[1] != 0 || look[0] != 0 || look[1] != 0)
			mc.getFramerateLimitTracker().onInputReceived(); // don't drop to AFK frame rates

		if (!inGame) {
			releaseAll();
			moveActive = false;
			if (screen instanceof ControlsScreen controls && controls.isCapturing()) {
				navKey = -1;
				controls.captureInput(buttons, pressed);
			} else if (screen != null) {
				navigateMenu(mc, screen, buttons, pressed, c, now);
			}
			return;
		}
		navKey = -1;

		Options o = mc.options;
		hold(o.keyAttack, down, config.mask(Action.ATTACK));
		hold(o.keyUse, down, config.mask(Action.USE));
		hold(o.keyJump, down, config.mask(Action.JUMP));
		hold(o.keyShift, down, config.mask(Action.SNEAK));
		hold(o.keySprint, down, config.mask(Action.SPRINT));
		hold(o.keyDrop, down, config.mask(Action.DROP));
		hold(o.keySwapOffhand, down, config.mask(Action.SWAP_OFFHAND));
		hold(o.keyTogglePerspective, down, config.mask(Action.PERSPECTIVE));
		hold(o.keyPickItem, down, config.mask(Action.PICK_BLOCK));
		hold(o.keyInventory, down, config.mask(Action.PC_INVENTORY));

		if ((pressed & config.mask(Action.DECK_INVENTORY)) != 0) DeckScreens.INSTANCE.toggleInventory(mc);
		if ((pressed & config.mask(Action.PAUSE)) != 0) {
			releaseAll();
			mc.pauseGame(false);
			return;
		}
		int step = ((pressed & config.mask(Action.HOTBAR_NEXT)) != 0 ? 1 : 0)
			- ((pressed & config.mask(Action.HOTBAR_PREV)) != 0 ? 1 : 0);
		if (step != 0) {
			var inventory = player.getInventory();
			inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() + step, 9));
		}

		// Movement is applied by KeyboardInputMixin on the next tick.
		moveLeft = -move[0];
		moveForward = move[1];
		moveActive = move[0] != 0 || move[1] != 0;

		// Look: stick with a quadratic curve for precision, plus gyro.
		double invert = config.invertLook ? -1 : 1;
		double yaw = curve(look[0]) * config.lookSpeed * dt + gyro[0] * config.gyroSensitivity;
		double pitch = -invert * curve(look[1]) * config.lookSpeed * PITCH_RATIO * dt
			+ invert * gyro[1] * config.gyroSensitivity;
		if (yaw != 0 || pitch != 0) player.turn(yaw / 0.15, pitch / 0.15); // turn() takes mouse units (0.15 deg)
	}

	// --- Menus -------------------------------------------------------------

	private void navigateMenu(Minecraft mc, Screen screen, long buttons, long pressed, ScreenClient.Controller c, long now) {
		// Direction from the D-pad or the left stick, with keyboard-like repeat.
		int dir = -1;
		if ((buttons & Deck.DPAD_UP) != 0 || c.leftY() > NAV_STICK_THRESHOLD) dir = InputConstants.KEY_UP;
		else if ((buttons & Deck.DPAD_DOWN) != 0 || c.leftY() < -NAV_STICK_THRESHOLD) dir = InputConstants.KEY_DOWN;
		else if ((buttons & Deck.DPAD_LEFT) != 0 || c.leftX() < -NAV_STICK_THRESHOLD) dir = InputConstants.KEY_LEFT;
		else if ((buttons & Deck.DPAD_RIGHT) != 0 || c.leftX() > NAV_STICK_THRESHOLD) dir = InputConstants.KEY_RIGHT;
		if (dir != navKey) {
			navKey = dir;
			navNextNanos = now + REPEAT_DELAY_NANOS;
			if (dir != -1) key(mc, screen, dir, 0);
		} else if (dir != -1 && now >= navNextNanos) {
			navNextNanos = now + REPEAT_RATE_NANOS;
			key(mc, screen, dir, 0);
		}
		if ((pressed & Deck.A) != 0) {
			// Nothing selected yet: select the first element first, so you
			// always see what A is about to press.
			if (screen.getFocused() == null) key(mc, screen, InputConstants.KEY_TAB, 0);
			else key(mc, screen, InputConstants.KEY_RETURN, 0);
		}
		if ((pressed & (Deck.B | Deck.MENU)) != 0) key(mc, screen, InputConstants.KEY_ESCAPE, 0);
		if ((pressed & Deck.R1) != 0) key(mc, screen, InputConstants.KEY_TAB, 0);
		if ((pressed & Deck.L1) != 0) key(mc, screen, InputConstants.KEY_TAB, 1); // Shift+Tab
	}

	private static void key(Minecraft mc, Screen screen, int scancode, int modifiers) {
		mc.setLastInputType(scancode == InputConstants.KEY_TAB ? InputType.KEYBOARD_TAB : InputType.KEYBOARD_ARROW);
		int keycode = switch (scancode) {
			case InputConstants.KEY_UP -> InputConstants.KEYCODE_UP;
			case InputConstants.KEY_DOWN -> InputConstants.KEYCODE_DOWN;
			case InputConstants.KEY_LEFT -> InputConstants.KEYCODE_LEFT;
			case InputConstants.KEY_RIGHT -> InputConstants.KEYCODE_RIGHT;
			case InputConstants.KEY_RETURN -> InputConstants.KEYCODE_RETURN;
			case InputConstants.KEY_TAB -> InputConstants.KEYCODE_TAB;
			default -> 27; // Escape
		};
		KeyEvent event = new KeyEvent(scancode, keycode, modifiers);
		screen.keyPressed(event);
		if (mc.gui.screen() == screen) screen.keyReleased(event);
	}

	// --- Key bindings ------------------------------------------------------

	/** Presses/releases a key binding on edges only, so the keyboard keeps working too. */
	private void hold(KeyMapping key, long down, long mask) {
		boolean isDown = (down & mask) != 0;
		if (isDown && held.add(key)) {
			key.setDown(true);
			KeyMappingAccessor acc = (KeyMappingAccessor) key;
			acc.steamu$setClickCount(acc.steamu$getClickCount() + 1);
		} else if (!isDown && held.remove(key)) {
			key.setDown(false);
		}
	}

	private void releaseAll() {
		for (KeyMapping key : held) key.setDown(false);
		held.clear();
	}

	private static double curve(float v) {
		return Math.copySign(v * v, v);
	}

	/** Radial deadzone, rescaled so movement starts at 0 just outside it. */
	private static float[] deadzone(float x, float y, float zone) {
		float mag = (float) Math.hypot(x, y);
		if (mag < zone) return new float[] {0, 0};
		float scaled = Math.min(1, (mag - zone) / (1 - zone));
		return new float[] {x / mag * scaled, y / mag * scaled};
	}
}
