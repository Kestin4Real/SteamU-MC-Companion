# SteamU Companion

A Fabric mod for Minecraft 26.3 that turns a Steam Deck into a Wii U-style
second screen and controller. The Deck has to be connected to your PC with
**[SteamU](https://github.com/Kestin4Real/SteamU)**.

Play on the PC monitor with the Deck in your hands, and manage your
inventory on the Deck's touchscreen without ever pausing.

* **Live vitals** on the Deck: health, food, armor, air, and the XP bar
  with your level.
* **Your whole inventory by touch:** hotbar, main inventory, armor and
  offhand, with real item icons, counts and durability bars.
* **Crafting and containers on the Deck:**
  * Press **X** for your inventory with the 2×2 crafting grid.
  * Chests, furnaces, crafting tables and any other container you
    right-click open on the Deck instead of the PC, so you can keep moving
    while you sort.
* **Built-in Deck controls:** analog movement, gyro aiming, back buttons
  and menu navigation. No Steam Input setup or other controller mod is
  needed, and every button can be remapped.

When you're not in a world, or no Deck is connected, everything behaves
like vanilla and the Deck mirrors your monitor.

## Requirements

* Minecraft **26.3** with **Fabric Loader 0.20.1+** and **Fabric API**.
* [SteamU](https://github.com/Kestin4Real/SteamU) running on the PC
  (`steamu-host`) and the Deck (`steamu-deck`), on a Linux PC.
* Optional: [Mod Menu](https://modrinth.com/mod/modmenu), for a Configure
  button.

## Installation

1. Download the jar from the GitHub Releases page (once there is one), or
   build it yourself (see below).
2. Put it in your instance's `mods/` folder, next to Fabric API.
3. Start `steamu-host` on the PC and SteamU on the Deck as usual, then join
   a world. The Deck switches to the companion screen. Starting
   `steamu-host` after the game also works.

## Controls

| Deck | In game |
|---|---|
| Left stick | Move (analog) |
| Right stick | Look |
| Touch the right trackpad | Gyro aiming while touched |
| R2 / L2 | Attack and break / use and place |
| A or R4 | Jump |
| B or L4 | Sneak |
| L3 or L5 | Sprint |
| X | Inventory and crafting on the Deck (press again to close) |
| Y or R5 | Swap offhand |
| R1 / L1, D-pad right / left | Next / previous hotbar slot |
| D-pad up | Change perspective |
| D-pad down | Drop item |
| R3 | Pick block |
| View | Open the inventory on the PC |
| Menu | Pause |

**Menus on the PC** (title screen, settings, pause, death screen...):
* **D-pad** or **left stick** moves the selection.
* **A** selects. If nothing is selected yet, the first press selects the
  first button.
* **B** or **Menu** goes back.
* **L1 / R1** jump between groups.

While you play, the mod has the controller to itself, so Steam's own
mapping (such as the trackpad mouse) is paused. In menus, Steam's mouse
emulation works again.

### Remapping

Open **Options → Controls → Steam Deck Controls...** (or use Mod Menu, or
the "Open SteamU Controls" key binding).

* Every action has two slots, and either button triggers it.
* To bind a slot, select it with the mouse (or the D-pad and A), then
  press a Deck button.
* To unbind a slot, select it and press **Esc**.
* **Invert look** and **Reset to defaults** are at the bottom.

Settings are saved in `config/steamu_companion.json`. That file also holds
`look_speed` (degrees per second at full stick) and `gyro_sensitivity`.

## Touch

On the inventory overview:
* **Drag** an item to another slot to move, swap or merge it (armor slots
  too).
* **Tap** a hotbar slot to select it.
* **Tap** any other slot to quick-move it, like shift-click (this also
  equips armor).

In crafting and container screens on the Deck:

| Touch | Does |
|---|---|
| Tap | Left click: pick up / put down |
| Drag a stack to a slot | Move it there |
| Hold about half a second | Right click: take half / place one |
| **Shift** button, then tap | Shift-click: quick move (also takes all crafted items) |
| **Close** button (or X) | Close it |

If you walk away from a chest, the server closes it and the Deck returns to
the overview.

Typing (such as renaming in an anvil) isn't possible from the Deck yet.

## Building

```bash
./gradlew build
```

The jar ends up in `build/libs/`. Gradle downloads the Java 25 it needs by
itself.

How the mod works and how to run the automated in-game test are described
in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License

MIT
