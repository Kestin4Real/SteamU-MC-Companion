package be.voidguardians.steamu.ui;

import be.voidguardians.steamu.mixin.AbstractContainerScreenAccessor;
import be.voidguardians.steamu.net.ScreenClient.Touch;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

/**
 * Shows a real vanilla screen on the Deck instead of the PC: the player
 * inventory (with its 2x2 crafting grid) and any container (chest, furnace,
 * crafting table, modded machines...). Everything behaves exactly like
 * vanilla because it IS vanilla; Deck touches become mouse clicks:
 *
 *   tap               left click (pick up / put down)
 *   drag to a slot    move the stack there
 *   hold ~0.45 s      right click (take half / place one)
 *   Shift button      turns taps into shift-clicks (quick move)
 *   Close button      closes it (or press X again)
 *
 * The PC keeps showing the game, so you can keep moving meanwhile.
 * All methods run on the client thread.
 */
public final class DeckScreens {
	public static final DeckScreens INSTANCE = new DeckScreens();

	private static final long LONG_PRESS_NANOS = 450_000_000L;
	private static final float DRAG_THRESHOLD = 3; // GUI units
	private static final int BUTTON_W = 36, BUTTON_H = 22, MARGIN = 4;
	private static final int MOD_SHIFT = 1; // SDL_KMOD_LSHIFT (26.x reads input through SDL3)
	private static final int LEFT = InputConstants.MOUSE_BUTTON_LEFT, RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;

	private Screen screen;
	private int scale = 4, width, height; // GUI scale and size in GUI units
	private int pixelWidth = 1280, pixelHeight = 800;
	private boolean shift;

	// Touch gesture state (finger 0)
	private boolean down, pressSent, longPress, carriedAtDown;
	private float downX, downY, x = -1, y = -1;
	private long downNanos;

	private DeckScreens() {}

	public boolean isOpen() {
		return screen != null;
	}

	public int guiScale() {
		return scale;
	}

	public int guiWidth() {
		return width;
	}

	public int guiHeight() {
		return height;
	}

	/** Called from GuiMixin: container screens go to the Deck when one is connected. */
	public boolean routeToDeck(Screen s, boolean deckAvailable) {
		if (!deckAvailable || !(s instanceof AbstractContainerScreen<?>)) return false;
		if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return false; // E on the PC stays on the PC
		open(s);
		return true;
	}

	public void toggleInventory(Minecraft mc) {
		if (screen != null) close(mc);
		else if (mc.player != null) open(new InventoryScreen(mc.player));
	}

	public void open(Screen s) {
		if (screen != null) {
			// Replaced (e.g. a chest opened while the Deck showed the
			// inventory): the new menu is already the open one, so only
			// tidy up the old screen client-side.
			screen.removed();
		}
		screen = s;
		shift = false;
		down = false;
		x = y = -1;
		s.added();
		layout(pixelWidth, pixelHeight, true);
	}

	/** Closes the Deck screen and the container behind it, without touching the PC's screen. */
	public void close(Minecraft mc) {
		if (screen == null) return;
		Screen s = screen;
		screen = null;
		LocalPlayer player = mc.player;
		if (player != null && s instanceof AbstractContainerScreen<?> cs && player.containerMenu == cs.getMenu()) {
			player.connection.send(new ServerboundContainerClosePacket(player.containerMenu.containerId));
			player.containerMenu = player.inventoryMenu; // what Player.closeContainer() does
		}
		s.removed();
	}

	/** Every client tick. */
	public void tick(Minecraft mc, boolean deckAvailable) {
		if (screen == null) return;
		LocalPlayer player = mc.player;
		if (player == null) {
			screen = null; // left the world
			return;
		}
		if (!deckAvailable) {
			close(mc); // nobody could see it anymore
			return;
		}
		// The server closed the container (walked away, chest broken...).
		if (screen instanceof AbstractContainerScreen<?> cs && !(screen instanceof InventoryScreen)
			&& player.containerMenu != cs.getMenu()) {
			screen.removed();
			screen = null;
			return;
		}
		screen.tick();
	}

	/** Picks the largest GUI scale at which the screen (plus the side buttons) fits. */
	public void layout(int pixelWidth, int pixelHeight, boolean force) {
		if (screen == null) return;
		if (!force && pixelWidth == this.pixelWidth && pixelHeight == this.pixelHeight) return;
		this.pixelWidth = pixelWidth;
		this.pixelHeight = pixelHeight;
		int imageW = 176, imageH = 166;
		if (screen instanceof AbstractContainerScreen<?> cs) {
			AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) cs;
			imageW = acc.steamu$imageWidth();
			imageH = acc.steamu$imageHeight();
		}
		scale = 1;
		for (int s = 5; s >= 1; s--) {
			if (imageW + 2 * (BUTTON_W + 2 * MARGIN) <= pixelWidth / s && imageH + 2 * MARGIN <= pixelHeight / s) {
				scale = s;
				break;
			}
		}
		width = pixelWidth / scale;
		height = pixelHeight / scale;
		screen.init(width, height);
	}

	// --- Introspection (used by the automated game test) --------------------

	public Screen current() {
		return screen;
	}

	/** Centre of a menu slot in Deck pixels, or null. */
	public int[] slotCenterPixels(int slotIndex) {
		if (!(screen instanceof AbstractContainerScreen<?> cs)) return null;
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) cs;
		var slot = cs.getMenu().getSlot(slotIndex);
		return new int[] {(acc.steamu$leftPos() + slot.x + 8) * scale, (acc.steamu$topPos() + slot.y + 8) * scale};
	}

	public int[] shiftButtonPixels() {
		return new int[] {(MARGIN + BUTTON_W / 2) * scale, (shiftButtonY() + BUTTON_H / 2) * scale};
	}

	public int[] closeButtonPixels() {
		return new int[] {(MARGIN + BUTTON_W / 2) * scale, (closeButtonY() + BUTTON_H / 2) * scale};
	}

	// --- Drawing -----------------------------------------------------------

	public void draw(GuiGraphicsExtractor g, Minecraft mc, float partialTick) {
		if (screen == null) return;
		updateLongPress();
		g.fill(0, 0, width, height, 0xFF15171C);
		int mx = Math.round(x), my = Math.round(y);
		screen.extractRenderStateWithTooltipAndSubtitles(g, mx, my, partialTick);

		g.nextStratum();
		drawButton(g, mc, shiftButtonY(), "Shift", shift);
		drawButton(g, mc, closeButtonY(), "Close", false);
	}

	private void drawButton(GuiGraphicsExtractor g, Minecraft mc, int by, String label, boolean active) {
		int bx = MARGIN;
		g.fill(bx - 1, by - 1, bx + BUTTON_W + 1, by + BUTTON_H + 1, 0xFF000000);
		g.fill(bx, by, bx + BUTTON_W, by + BUTTON_H, active ? 0xFF4A8A3A : 0xFF3A3F4A);
		g.centeredText(mc.font, label, bx + BUTTON_W / 2, by + (BUTTON_H - 8) / 2, 0xFFFFFFFF);
	}

	private int shiftButtonY() {
		return height / 2 - BUTTON_H - 4;
	}

	private int closeButtonY() {
		return height / 2 + 4;
	}

	private static boolean inside(float px, float py, int bx, int by) {
		return px >= bx && px < bx + BUTTON_W && py >= by && py < by + BUTTON_H;
	}

	// --- Touch -------------------------------------------------------------

	public void handleTouch(Touch t, Minecraft mc) {
		if (screen == null || t.finger() != 0) return;
		float tx = t.x() * width, ty = t.y() * height;
		switch (t.type()) {
			case Touch.DOWN -> {
				if (inside(tx, ty, MARGIN, shiftButtonY())) {
					shift = !shift;
					return;
				}
				if (inside(tx, ty, MARGIN, closeButtonY())) {
					close(mc);
					return;
				}
				down = true;
				pressSent = longPress = false;
				carriedAtDown = carried(mc); // something was already on the cursor
				downX = x = tx;
				downY = y = ty;
				downNanos = System.nanoTime();
			}
			case Touch.MOVE -> {
				if (!down) return;
				float dx = tx - x, dy = ty - y;
				x = tx;
				y = ty;
				if (!pressSent && Math.hypot(tx - downX, ty - downY) > DRAG_THRESHOLD) {
					screen.mouseClicked(event(downX, downY, LEFT), false); // start of a drag
					pressSent = true;
				}
				if (pressSent && !longPress) screen.mouseDragged(event(tx, ty, LEFT), dx, dy);
			}
			case Touch.UP -> {
				if (!down) return;
				down = false;
				x = tx;
				y = ty;
				if (longPress) {
					// right click already done
				} else if (!pressSent) {
					screen.mouseClicked(event(tx, ty, LEFT), false); // tap
					screen.mouseReleased(event(tx, ty, LEFT));
				} else {
					screen.mouseReleased(event(tx, ty, LEFT));
					// Drag-and-drop: this gesture picked the stack up, so drop it
					// where the finger was lifted.
					if (!carriedAtDown && carried(mc) && screen != null) {
						screen.mouseClicked(event(tx, ty, LEFT), false);
						screen.mouseReleased(event(tx, ty, LEFT));
					}
				}
				if (!carried(mc)) x = y = -1; // no hover/tooltip when nothing is held
			}
			default -> {
			}
		}
	}

	private void updateLongPress() {
		if (screen != null && down && !pressSent && System.nanoTime() - downNanos > LONG_PRESS_NANOS) {
			screen.mouseClicked(event(downX, downY, RIGHT), false);
			screen.mouseReleased(event(downX, downY, RIGHT));
			pressSent = longPress = true;
		}
	}

	private boolean carried(Minecraft mc) {
		return mc.player != null && !mc.player.containerMenu.getCarried().isEmpty();
	}

	private MouseButtonEvent event(float ex, float ey, int button) {
		return new MouseButtonEvent(ex, ey, new MouseButtonInfo(button, shift ? MOD_SHIFT : 0));
	}
}
