package be.voidguardians.steamu.config;

import be.voidguardians.steamu.SteamUCompanion;
import be.voidguardians.steamu.net.ScreenClient.Deck;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller bindings and settings, stored in config/steamu_companion.json.
 * Each action has up to two Deck buttons (either one triggers it).
 */
public final class CompanionConfig {
	/** Deck buttons that can be bound. L2/R2 also fire on a half-pressed analog trigger. */
	public enum Button {
		A(Deck.A), B(Deck.B), X(Deck.X), Y(Deck.Y),
		L1(Deck.L1), R1(Deck.R1), L2(Deck.L2), R2(Deck.R2), L3(Deck.L3), R3(Deck.R3),
		L4(Deck.L4), R4(Deck.R4), L5(Deck.L5), R5(Deck.R5),
		DPAD_UP(Deck.DPAD_UP), DPAD_DOWN(Deck.DPAD_DOWN), DPAD_LEFT(Deck.DPAD_LEFT), DPAD_RIGHT(Deck.DPAD_RIGHT),
		VIEW(Deck.VIEW), MENU(Deck.MENU),
		LEFT_PAD_CLICK(Deck.LPAD_CLICK), RIGHT_PAD_CLICK(Deck.RPAD_CLICK),
		LEFT_PAD_TOUCH(Deck.LPAD_TOUCH), RIGHT_PAD_TOUCH(Deck.RPAD_TOUCH);

		public final long bit;

		Button(long bit) {
			this.bit = bit;
		}

		public String label() {
			return switch (this) {
				case DPAD_UP -> "D-pad Up";
				case DPAD_DOWN -> "D-pad Down";
				case DPAD_LEFT -> "D-pad Left";
				case DPAD_RIGHT -> "D-pad Right";
				case VIEW -> "View";
				case MENU -> "Menu";
				case LEFT_PAD_CLICK -> "L Pad Click";
				case RIGHT_PAD_CLICK -> "R Pad Click";
				case LEFT_PAD_TOUCH -> "L Pad Touch";
				case RIGHT_PAD_TOUCH -> "R Pad Touch";
				default -> name();
			};
		}

		/** The bound button that is set in `bits`, if any (lowest first). */
		public static Button fromBits(long bits) {
			for (Button b : values()) if ((bits & b.bit) != 0) return b;
			return null;
		}
	}

	public enum Action {
		ATTACK("Attack / break", Button.R2),
		USE("Use / place", Button.L2),
		JUMP("Jump", Button.A, Button.R4),
		SNEAK("Sneak", Button.B, Button.L4),
		SPRINT("Sprint", Button.L3, Button.L5),
		DECK_INVENTORY("Crafting on Deck", Button.X),
		SWAP_OFFHAND("Swap offhand", Button.Y, Button.R5),
		HOTBAR_NEXT("Next hotbar slot", Button.R1, Button.DPAD_RIGHT),
		HOTBAR_PREV("Previous hotbar slot", Button.L1, Button.DPAD_LEFT),
		DROP("Drop item", Button.DPAD_DOWN),
		PICK_BLOCK("Pick block", Button.R3),
		PERSPECTIVE("Change perspective", Button.DPAD_UP),
		PC_INVENTORY("Inventory on PC", Button.VIEW),
		PAUSE("Pause", Button.MENU),
		GYRO_AIM("Gyro aim (hold)", Button.RIGHT_PAD_TOUCH);

		public final String label;
		final Button[] defaults;

		Action(String label, Button... defaults) {
			this.label = label;
			this.defaults = defaults;
		}
	}

	public static final int SLOTS = 2;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static CompanionConfig instance;

	private final Map<Action, Button[]> bindings = new EnumMap<>(Action.class);
	private final long[] masks = new long[Action.values().length];
	public boolean invertLook = false;
	public double lookSpeed = 320;      // degrees per second at full deflection
	public double gyroSensitivity = 1.0; // 1 = real-world 1:1

	public static CompanionConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	private CompanionConfig() {
		resetBindings();
	}

	public void resetBindings() {
		for (Action a : Action.values()) bindings.put(a, slotsOf(a.defaults));
		updateMasks();
	}

	public Button binding(Action action, int slot) {
		return bindings.get(action)[slot];
	}

	public void bind(Action action, int slot, Button button) {
		bindings.get(action)[slot] = button;
		updateMasks();
	}

	/** Bits of all buttons bound to an action. */
	public long mask(Action action) {
		return masks[action.ordinal()];
	}

	private void updateMasks() {
		for (Action a : Action.values()) {
			long m = 0;
			for (Button b : bindings.get(a)) if (b != null) m |= b.bit;
			masks[a.ordinal()] = m;
		}
	}

	private static Button[] slotsOf(Button... buttons) {
		Button[] slots = new Button[SLOTS];
		for (int i = 0; i < Math.min(SLOTS, buttons.length); i++) slots[i] = buttons[i];
		return slots;
	}

	// --- Persistence ------------------------------------------------------

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("steamu_companion.json");
	}

	private static CompanionConfig load() {
		CompanionConfig config = new CompanionConfig();
		Path path = path();
		if (!Files.exists(path)) {
			config.save(); // write the defaults so they can be edited by hand
			return config;
		}
		try (Reader r = Files.newBufferedReader(path)) {
			JsonObject root = GSON.fromJson(r, JsonObject.class);
			if (root.has("invert_look")) config.invertLook = root.get("invert_look").getAsBoolean();
			if (root.has("look_speed")) config.lookSpeed = root.get("look_speed").getAsDouble();
			if (root.has("gyro_sensitivity")) config.gyroSensitivity = root.get("gyro_sensitivity").getAsDouble();
			if (root.has("bindings")) {
				JsonObject b = root.getAsJsonObject("bindings");
				for (Action a : Action.values()) {
					String key = a.name().toLowerCase(java.util.Locale.ROOT);
					if (!b.has(key)) continue;
					List<Button> buttons = new ArrayList<>();
					for (var e : b.getAsJsonArray(key)) {
						try {
							buttons.add(Button.valueOf(e.getAsString().toUpperCase(java.util.Locale.ROOT)));
						} catch (IllegalArgumentException ignored) {
							SteamUCompanion.LOG.warn("Unknown Deck button '{}' in {}", e.getAsString(), path);
						}
					}
					config.bindings.put(a, slotsOf(buttons.toArray(new Button[0])));
				}
				config.updateMasks();
			}
		} catch (IOException | RuntimeException e) {
			SteamUCompanion.LOG.warn("Cannot read {}, using defaults: {}", path, e.toString());
		}
		return config;
	}

	public void save() {
		Map<String, Object> root = new LinkedHashMap<>();
		Map<String, List<String>> b = new LinkedHashMap<>();
		for (Action a : Action.values()) {
			List<String> names = new ArrayList<>();
			for (Button button : bindings.get(a)) if (button != null) names.add(button.name());
			b.put(a.name().toLowerCase(java.util.Locale.ROOT), names);
		}
		root.put("bindings", b);
		root.put("invert_look", invertLook);
		root.put("look_speed", lookSpeed);
		root.put("gyro_sensitivity", gyroSensitivity);
		try {
			Files.createDirectories(path().getParent());
			try (Writer w = Files.newBufferedWriter(path())) {
				GSON.toJson(root, w);
			}
		} catch (IOException e) {
			SteamUCompanion.LOG.warn("Cannot save {}: {}", path(), e.toString());
		}
	}
}
