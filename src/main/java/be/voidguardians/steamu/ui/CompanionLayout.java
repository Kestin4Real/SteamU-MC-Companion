package be.voidguardians.steamu.ui;

/**
 * Where everything sits on the Deck screen, in GUI units. The Deck's
 * 1280x800 is rendered at GUI scale 5, so the canvas is 256x160 units and a
 * slot (18 units) is 90 px, about 12 mm on the Deck: comfortable to touch.
 *
 * Slot numbers are InventoryMenu indices: 5-8 armor (head..feet), 9-35 main
 * inventory, 36-44 hotbar, 45 offhand.
 */
public final class CompanionLayout {
	public static final int GUI_SCALE = 5;
	public static final int SLOT = 18;
	public static final int ARMOR_START = 5, INV_START = 9, HOTBAR_START = 36, OFFHAND = 45;

	public final int width, height; // GUI units
	/** Gap between the health/food/armor rows and the XP bar: 10 units = 50 px on the Deck. */
	public static final int XP_GAP = 10;

	public final int invX, invY, hotbarY, armorX, offhandX;
	/** Health + food row, then armor + air row (9 units each, 1 apart), then the XP bar. */
	public final int statsY = 2;
	public final int xpBarY = statsY + 10 + 9 + XP_GAP;

	public CompanionLayout(int pixelWidth, int pixelHeight) {
		width = pixelWidth / GUI_SCALE;
		height = pixelHeight / GUI_SCALE;
		invX = (width - 9 * SLOT) / 2;
		invY = xpBarY + 5 + 10; // XP bar is 5 high; inventory panel border starts 6 above invY
		hotbarY = invY + 3 * SLOT + 6;
		armorX = invX - SLOT - 8;
		offhandX = invX + 9 * SLOT + 8;
	}

	/** Top-left corner of a menu slot, or null if it is not shown. */
	public int[] slotPos(int slot) {
		if (slot >= INV_START && slot < HOTBAR_START) {
			int i = slot - INV_START;
			return new int[] {invX + (i % 9) * SLOT, invY + (i / 9) * SLOT};
		}
		if (slot >= HOTBAR_START && slot < OFFHAND) return new int[] {invX + (slot - HOTBAR_START) * SLOT, hotbarY};
		if (slot >= ARMOR_START && slot < INV_START) return new int[] {armorX, invY + (slot - ARMOR_START) * SLOT};
		if (slot == OFFHAND) return new int[] {offhandX, hotbarY};
		return null;
	}

	/** Menu slot under a point (GUI units), or -1. */
	public int hit(float x, float y) {
		for (int slot = ARMOR_START; slot <= OFFHAND; slot++) {
			int[] p = slotPos(slot);
			if (p != null && x >= p[0] && x < p[0] + SLOT && y >= p[1] && y < p[1] + SLOT) return slot;
		}
		return -1;
	}
}
