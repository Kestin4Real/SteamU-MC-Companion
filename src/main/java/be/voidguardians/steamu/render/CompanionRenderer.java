package be.voidguardians.steamu.render;

import be.voidguardians.steamu.SteamUCompanion;
import be.voidguardians.steamu.net.ScreenClient;
import be.voidguardians.steamu.ui.CompanionLayout;
import be.voidguardians.steamu.ui.CompanionScreen;
import be.voidguardians.steamu.ui.DeckScreens;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.GuiBannerResultRenderer;
import net.minecraft.client.gui.render.pip.GuiBookModelRenderer;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.GuiProfilerChartRenderer;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Renders the companion UI into its own off-screen target with a second,
 * private GuiRenderer, then reads it back asynchronously and hands it to
 * steamu-host. Runs on the render thread right after the game's own GUI.
 *
 * GuiRenderer normally draws into the game's main target at the window's
 * size; GuiRendererMixin swaps in our target and a 1280x800 window state,
 * but only while {@link #isCompanion} is true for that renderer instance.
 */
public final class CompanionRenderer {
	public static final CompanionRenderer INSTANCE = new CompanionRenderer();
	private static final int READBACK_BUFFERS = 3;

	private ScreenClient client;
	private final CompanionScreen screen = new CompanionScreen();
	private final GuiRenderState state = new GuiRenderState();
	private final WindowRenderState window = new WindowRenderState();
	private GuiRenderer guiRenderer;
	private TextureTarget target;
	private final GpuBuffer[] readback = new GpuBuffer[READBACK_BUFFERS];
	private final boolean[] inFlight = new boolean[READBACK_BUFFERS];
	private boolean rendering;
	private GuiGraphicsExtractor extractor; // non-null while building the Deck UI
	private long lastFrameNanos;

	private CompanionRenderer() {}

	public void init(ScreenClient client) {
		this.client = client;
	}

	public CompanionScreen screen() {
		return screen;
	}

	/** Applies pending Deck touches. Client thread only (tick or render). */
	public void processTouches(Minecraft mc) {
		if (client == null) return;
		ScreenClient.Touch touch;
		while ((touch = client.pollTouch()) != null) {
			if (DeckScreens.INSTANCE.isOpen()) DeckScreens.INSTANCE.handleTouch(touch, mc);
			else screen.handleTouch(touch, mc);
		}
	}

	/** A Deck is connected and showing our frames. */
	public boolean deckAvailable() {
		ScreenClient c = client;
		ScreenClient.Display d = c == null ? null : c.display();
		return d != null && d.connected();
	}

	/** True for the GuiGraphicsExtractor currently building the Deck UI. */
	public boolean isDeckExtractor(Object graphics) {
		return extractor != null && graphics == extractor;
	}

	public boolean extracting() {
		return extractor != null;
	}

	public static boolean isCompanion(GuiRenderer renderer) {
		return INSTANCE.rendering && renderer == INSTANCE.guiRenderer;
	}

	public WindowRenderState window() {
		return window;
	}

	public TextureTarget target() {
		return target;
	}

	/** Called from GameRendererMixin after the main GUI pass. */
	public void renderFrame(GameRenderer gameRenderer) {
		if (client == null) return;
		ScreenClient.Display display = client.display();
		if (display == null || !display.connected()) return;
		int width = display.width(), height = display.height();
		if (width < 64 || height < 64) return;

		// Pace to the Deck's refresh rate (at most 60 fps: it's a menu).
		long now = System.nanoTime();
		long interval = 1_000_000_000L / Math.max(1, Math.min(60, display.refreshHz()));
		if (now - lastFrameNanos < interval - 1_000_000L) return;

		if (!client.ensureAttached(width, height)) return;
		int buffer = freeReadbackBuffer();
		if (buffer < 0) return; // GPU still busy with older frames: skip this one
		lastFrameNanos = now;

		try {
			ensureResources(gameRenderer, width, height);

			// Build the UI into our private render state. Touches are applied
			// first so a dragged item follows the finger every frame.
			Minecraft mc = Minecraft.getInstance();
			processTouches(mc);
			DeckScreens deck = DeckScreens.INSTANCE;
			window.guiScale = deck.isOpen() ? deck.guiScale() : CompanionLayout.GUI_SCALE;
			GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(mc, state, -1, -1);
			extractor = graphics;
			try {
				if (deck.isOpen()) {
					deck.layout(width, height, false);
					window.guiScale = deck.guiScale();
					deck.draw(graphics, mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
				} else {
					screen.setSize(width, height);
					screen.draw(graphics, mc);
				}
			} finally {
				extractor = null;
			}

			RenderSystem.getDevice().createCommandEncoder()
				.clearColorAndDepthTextures(target.getColorTexture(), new Vector4f(0, 0, 0, 1), target.getDepthTexture(), 0.0);
			rendering = true;
			try {
				guiRenderer.render();
				guiRenderer.endFrame();
			} finally {
				rendering = false;
			}

			// Asynchronous readback: the callback runs once the GPU is done.
			GpuBuffer buf = readback[buffer];
			inFlight[buffer] = true;
			int bytes = width * height * 4;
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), buf, 0L, () -> {
				try {
					deliver(buf, bytes);
				} finally {
					inFlight[buffer] = false;
				}
			}, 0);
		} catch (RuntimeException e) {
			SteamUCompanion.LOG.error("Companion rendering failed, disabling it", e);
			client = null;
		}
	}

	private void deliver(GpuBuffer buf, int bytes) {
		if (client.slotSize() != bytes) return; // re-attached at another size meanwhile
		int slot = client.acquireSlot();
		if (slot < 0) return; // host still busy: drop this frame
		try (GpuBufferSlice.MappedView view = buf.map(true, false)) {
			ByteBuffer src = view.data();
			src.limit(bytes);
			client.slotBuffer(slot).put(src);
		} catch (RuntimeException e) {
			client.cancel(slot);
			throw e;
		}
		client.submit(slot);
	}

	private int freeReadbackBuffer() {
		for (int i = 0; i < READBACK_BUFFERS; i++) if (!inFlight[i]) return i;
		return -1;
	}

	private void ensureResources(GameRenderer gameRenderer, int width, int height) {
		window.width = width;
		window.height = height;
		window.appropriateLineWidth = window.guiScale;
		if (guiRenderer == null) {
			// Same picture-in-picture renderers as the game's own GUI, so
			// e.g. the inventory screen can draw the player model.
			Minecraft mc = Minecraft.getInstance();
			guiRenderer = new GuiRenderer(state, gameRenderer.featureRenderDispatcher(), List.of(
				new GuiEntityRenderer(mc.getEntityRenderDispatcher()),
				new GuiSkinRenderer(),
				new GuiBookModelRenderer(),
				new GuiBannerResultRenderer(mc.getAtlasManager()),
				new GuiProfilerChartRenderer()));
		}
		if (target == null || target.width != width || target.height != height) {
			if (target != null) target.destroyBuffers();
			target = new TextureTarget("steamu_companion", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			for (int i = 0; i < READBACK_BUFFERS; i++) {
				if (readback[i] != null) readback[i].close();
				readback[i] = RenderSystem.getDevice().createBuffer(() -> "SteamU companion readback",
					GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4);
				inFlight[i] = false;
			}
		}
	}
}
