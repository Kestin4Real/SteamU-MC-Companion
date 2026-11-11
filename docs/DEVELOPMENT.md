# SteamU Companion: development notes

How the mod works inside, how to build it and how to test it. For players,
see the [README](../README.md).

## How it works

The mod is a client of SteamU's
[second-screen API](https://github.com/Kestin4Real/SteamU/blob/main/docs/API-QUICKSTART.md),
implemented in pure Java: a Unix domain socket plus a memory-mapped file in
`/dev/shm`, no native code (`net/ScreenClient.java`).

### Drawing the Deck screen

1. **Private renderer.** After the game's own GUI pass each frame,
   `render/CompanionRenderer` draws the Deck UI with a second, private
   `GuiRenderer` into an off-screen 1280×800 target, using Minecraft's own
   item, sprite and text rendering.
   * `GuiRendererMixin` and `GameRendererMixin` point only that renderer
     instance at the off-screen target, so the game's own GUI is untouched.
   * Picture-in-picture renderers (player model and the like) get their own
     instances too.
2. **Readback.** The pixels are copied to a GPU buffer and mapped a frame
   later (asynchronous readback, no GPU stall), straight into a free slot
   of the shared-memory file.
3. **Encoding.** `steamu-host` encodes those frames instead of the monitor
   and sends them to the Deck.

The UI is laid out in GUI units at GUI scale 5 (`ui/CompanionLayout`). That
makes the 1280×800 canvas 256×160 units, and an inventory slot is 90 px,
about 12 mm on the Deck.

### Touch

Touch events arrive on the socket thread and are queued. On the client
tick, `ui/CompanionScreen` turns them into ordinary inventory clicks:
pickup, swap, quick-move and hotbar selection. The server sees normal
clicks, so this works on any server.

### Screens on the Deck

* **Routing.** `GuiMixin` intercepts `setScreen` for container screens (and
  the X inventory) and hands them to `ui/DeckScreens` instead of the PC,
  as long as a Deck is connected.
* **Rendering.** `DeckScreens` hosts the real vanilla screen object at a
  GUI scale that fits the Deck and draws it with the private renderer.
* **Input.** Touches become `MouseButtonEvent`s for that screen: a tap is a
  left click, holding about half a second is a right click, and the
  on-screen Shift button adds shift-clicks.
* **What works.** Recipe book, furnace progress and modded containers all
  work, because it is the game's own screen.

### Controls

* **Source.** SteamU forwards every controller report (about 250 Hz) to the
  mod (`ScreenClient.Controller`). Short taps are latched, so they are
  never missed between frames.
* **Exclusive mode.** While in a world with no menu, or in a menu while the
  window has focus, the mod asks for exclusive input. SteamU then holds the
  PC's virtual controller neutral, so Steam Input can't act on the same
  buttons.
* **In game.** `input/ControllerHandler` runs once per rendered frame:
  * key bindings are pressed and released on edges, so the keyboard keeps
    working;
  * analog movement goes through `KeyboardInputMixin`;
  * look combines the stick (quadratic curve) with the gyro, which is
    integrated on the socket thread while the gyro button is held.
* **Menus.** Navigation sends Minecraft the same `KeyEvent`s a keyboard
  would (arrows, Enter, Escape, Tab), with key repeat. That way every
  vanilla menu works.
* **Bindings.** These live in `config/CompanionConfig`, two Deck buttons
  per action, stored in `config/steamu_companion.json`.
  `ui/ControlsScreen` is the in-game editor. It is reachable from Options →
  Controls (`VanillaControlsScreenMixin`), Mod Menu and a key binding.

## Building

```bash
./gradlew build
```

The jar ends up in `build/libs/`. Gradle downloads Java 25 by itself
(Minecraft 26.x requires it).

Versions are in `gradle.properties`:
* Minecraft 26.3
* Fabric Loader 0.20.1
* Loom 1.19
* Fabric API 0.165.2+26.3

Minecraft 26.x ships unobfuscated, so the code uses Mojang's names
directly.

## Automated in-game test

```bash
STEAMU_PHASE_SECONDS=14 ./gradlew runClientGameTest
```

This starts the game, creates a survival world and runs these phases
(`STEAMU_TEST_PHASES` picks which, comma separated):

| Phase | Checks |
|---|---|
| `xp` | stats screen with levels and points (for a screenshot) |
| `menu` | pause menu driven with the Deck's controller |
| `remap` | SteamU Controls screen; rebind Attack with a Deck button |
| `unbind` | the Options → Controls button opens it, and Esc unbinds a slot (fully automatic) |
| `crafting` | inventory on the Deck, with oak logs to craft |
| `chest` | a right-clicked chest full of diamonds and gold opens on the Deck |

Each interactive phase:
1. logs a `STEAMU_PHASE` line, with the Deck-pixel positions of the slots
   and buttons to touch;
2. waits `STEAMU_PHASE_SECONDS`, so you (or a script) can act on the Deck;
3. logs a `STEAMU_RESULT` line with the resulting state.

Run it with `steamu-host` and a Deck connected, or with a simulated Deck on
the PC. For scripted runs, `steamu-deck --fake-stdin --windowed` takes
button commands such as `tap A` or `hold R2` on standard input.

To keep a running SteamU setup undisturbed, use a separate test host:

```bash
export STEAMU_SCREEN_SOCKET=$XDG_RUNTIME_DIR/steamu-test.sock
steamu-host --port 28850 &
SDL_AUDIO_DRIVER=dummy steamu-deck --fake-idle --windowed --host 127.0.0.1 --port 28850 &
./gradlew runClientGameTest
```

The mod reads `STEAMU_SCREEN_SOCKET` too, so the game talks to the test
host.
