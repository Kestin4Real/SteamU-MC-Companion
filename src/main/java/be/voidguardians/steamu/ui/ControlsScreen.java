package be.voidguardians.steamu.ui;

import be.voidguardians.steamu.config.CompanionConfig;
import be.voidguardians.steamu.config.CompanionConfig.Action;
import be.voidguardians.steamu.net.ScreenClient.Deck;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Remaps the Deck's buttons. Each action has two slots; select one (mouse,
 * or the Deck's D-pad + A), then press the Deck button to bind it. Esc (or
 * the Deck's Menu button) unbinds the slot instead. Opened from Options →
 * Controls, Mod Menu or the "Open SteamU Controls" key.
 */
public class ControlsScreen extends Screen {
	private static final long CAPTURE_TIMEOUT_NANOS = 6_000_000_000L;
	private static final int ROW_H = 22, SLOT_W = 72, COL_GAP = 12;

	private final Screen parent;
	private final Map<Action, Button[]> slotButtons = new EnumMap<>(Action.class);
	private Button invertButton;
	private int[] labelX, labelY;

	private Action captureAction;
	private int captureSlot = -1;
	private boolean captureArmed;
	private long captureStart;

	public ControlsScreen(Screen parent) {
		super(Component.literal("SteamU Controls"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Action[] actions = Action.values();
		int rows = (actions.length + 1) / 2;
		int colW = Math.min(250, (width - 20 - COL_GAP) / 2);
		int x0 = (width - (2 * colW + COL_GAP)) / 2;
		int y0 = Math.max(26, (height - (rows * ROW_H + 60)) / 2 + 16);
		labelX = new int[actions.length];
		labelY = new int[actions.length];
		CompanionConfig config = CompanionConfig.get();

		for (int i = 0; i < actions.length; i++) {
			Action action = actions[i];
			int x = x0 + (i / rows) * (colW + COL_GAP), y = y0 + (i % rows) * ROW_H;
			labelX[i] = x;
			labelY[i] = y + 6;
			Button[] buttons = new Button[CompanionConfig.SLOTS];
			for (int slot = 0; slot < CompanionConfig.SLOTS; slot++) {
				final int s = slot;
				int bx = x + colW - (CompanionConfig.SLOTS - slot) * (SLOT_W + 2);
				buttons[slot] = addRenderableWidget(Button.builder(Component.empty(), b -> startCapture(action, s))
					.bounds(bx, y, SLOT_W, 20).build());
			}
			slotButtons.put(action, buttons);
		}

		int footerY = y0 + rows * ROW_H + 14;
		int bw = 120, total = 3 * bw + 8;
		int fx = (width - total) / 2;
		invertButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			config.invertLook = !config.invertLook;
			refresh();
		}).bounds(fx, footerY, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> {
			config.resetBindings();
			config.invertLook = false;
			captureAction = null;
			refresh();
		}).bounds(fx + bw + 4, footerY, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(fx + 2 * (bw + 4), footerY, bw, 20).build());
		refresh();
	}

	private void refresh() {
		CompanionConfig config = CompanionConfig.get();
		for (var e : slotButtons.entrySet()) {
			for (int slot = 0; slot < CompanionConfig.SLOTS; slot++) {
				boolean capturing = e.getKey() == captureAction && slot == captureSlot;
				CompanionConfig.Button b = config.binding(e.getKey(), slot);
				e.getValue()[slot].setMessage(Component.literal(capturing ? "> press <" : b == null ? "-" : b.label()));
			}
		}
		invertButton.setMessage(Component.literal("Invert look: " + (config.invertLook ? "ON" : "OFF")));
	}

	// --- Capture (fed by ControllerHandler) ----------------------------------

	private void startCapture(Action action, int slot) {
		captureAction = action;
		captureSlot = slot;
		captureArmed = false; // wait until the button that opened it is released
		captureStart = System.nanoTime();
		refresh();
	}

	public boolean isCapturing() {
		if (captureAction != null && System.nanoTime() - captureStart > CAPTURE_TIMEOUT_NANOS) {
			captureAction = null;
			refresh();
		}
		return captureAction != null;
	}

	/** buttons = held now, pressed = newly pressed this frame. */
	public void captureInput(long buttons, long pressed) {
		if (!captureArmed) {
			captureArmed = buttons == 0;
			return;
		}
		if (pressed == 0) return;
		CompanionConfig config = CompanionConfig.get();
		if ((pressed & Deck.MENU) != 0) config.bind(captureAction, captureSlot, null); // clear
		else {
			CompanionConfig.Button b = CompanionConfig.Button.fromBits(pressed);
			if (b == null) return; // e.g. the Steam button: not bindable, keep waiting
			config.bind(captureAction, captureSlot, b);
		}
		captureAction = null;
		config.save();
		refresh();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (captureAction != null && event.isEscape()) {
			// Esc while waiting for a button = unbind this slot
			CompanionConfig config = CompanionConfig.get();
			config.bind(captureAction, captureSlot, null);
			captureAction = null;
			config.save();
			refresh();
			return true;
		}
		return super.keyPressed(event);
	}

	// --- Drawing -------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
		Action[] actions = Action.values();
		for (int i = 0; i < actions.length; i++)
			graphics.text(font, actions[i].label, labelX[i], labelY[i], 0xFFE0E0E0);
		String hint = captureAction != null
			? "Press a Deck button for \"" + captureAction.label + "\"  (Esc or Menu: unbind)"
			: "Select a slot, then press a Deck button (Esc unbinds). D-pad moves, A selects, B back.";
		graphics.centeredText(font, hint, width / 2, height - 14, 0xFFA0A8B8);
	}

	@Override
	public void onClose() {
		CompanionConfig.get().save();
		minecraft.gui.setScreen(parent);
	}
}
