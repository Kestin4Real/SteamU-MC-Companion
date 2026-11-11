package be.voidguardians.steamu.net;

import be.voidguardians.steamu.SteamUCompanion;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client for the SteamU second-screen API (see sdk/steamu_screen.h in the
 * SteamU repo). Talks to steamu-host over $XDG_RUNTIME_DIR/steamu-screen.sock
 * and hands over frames through a memory-mapped file in /dev/shm, so pixels
 * are never copied through the socket.
 *
 * Reconnects automatically: steamu-host may be started before or after the game.
 */
public final class ScreenClient {
	// Message types (sus_msg_type)
	private static final int MSG_HELLO = 1, MSG_DISPLAY = 2, MSG_ATTACH = 3, MSG_FRAME = 4,
		MSG_RELEASE = 5, MSG_TOUCH = 6, MSG_DETACH = 7, MSG_INPUT_MODE = 8, MSG_CONTROLLER = 9;
	private static final int MAGIC = 0x53435553, VERSION = 1;
	private static final int FORMAT_RGBA8 = 1;
	private static final int SLOTS = 3;

	public record Display(boolean connected, int width, int height, int refreshHz) {}

	public record Touch(int type, int finger, float x, float y) {
		public static final int DOWN = 1, MOVE = 2, UP = 3;
	}

	/**
	 * Steam Deck controls (sus_controller_msg). buttons = lo | hi << 32,
	 * bits as SUS_DECK_* in steamu_screen.h. Sticks -1..1 (+y up),
	 * triggers 0..1.
	 */
	public record Controller(long buttons, float leftX, float leftY, float rightX, float rightY,
							 float leftTrigger, float rightTrigger, long receivedNanos) {
		public boolean down(long bit) {
			return (buttons & bit) != 0;
		}
	}

	/** Button bits of {@link Controller#buttons()}. */
	public static final class Deck {
		public static final long R2 = 0x1L, L2 = 0x2L, R1 = 0x4L, L1 = 0x8L, Y = 0x10L, B = 0x20L, X = 0x40L, A = 0x80L,
			DPAD_UP = 0x100L, DPAD_RIGHT = 0x200L, DPAD_LEFT = 0x400L, DPAD_DOWN = 0x800L,
			VIEW = 0x1000L, STEAM = 0x2000L, MENU = 0x4000L, L5 = 0x8000L, R5 = 0x10000L,
			LPAD_CLICK = 0x20000L, RPAD_CLICK = 0x40000L, LPAD_TOUCH = 0x80000L, RPAD_TOUCH = 0x100000L,
			L3 = 0x400000L, R3 = 0x4000000L,
			L4 = 0x200L << 32, R4 = 0x400L << 32, QAM = 0x40000L << 32;

		private Deck() {}
	}

	private final String appName;
	private final ConcurrentLinkedQueue<Touch> touches = new ConcurrentLinkedQueue<>();
	private volatile Display display;
	private volatile SocketChannel channel;
	private volatile boolean running = true;
	private volatile Controller controller;
	private final java.util.concurrent.atomic.AtomicLong latchedButtons = new java.util.concurrent.atomic.AtomicLong();
	// Gyro rotation (degrees) integrated per report while the right trackpad is touched.
	private double gyroYaw, gyroPitch;
	private int lastSeq = -1;
	private volatile long gyroGate = Deck.RPAD_TOUCH; // gyro counts while any of these is held
	private boolean modeSubscribe, modeExclusive, modeSent;

	// Shared frame memory (guarded by `this`)
	private Path shmPath;
	private MappedByteBuffer shm;
	private int attachedWidth, attachedHeight, slotSize;
	private final boolean[] busy = new boolean[SLOTS];

	public ScreenClient(String appName) {
		this.appName = appName;
		Thread t = new Thread(this::run, "SteamU screen client");
		t.setDaemon(true);
		t.start();
	}

	/** Deck display info, or null while steamu-host is not reachable. */
	public Display display() {
		return display;
	}

	public Touch pollTouch() {
		return touches.poll();
	}

	/** Latest controller state, or null if not subscribed / no data. */
	public Controller controller() {
		return controller;
	}

	/** Buttons that were down at any point since the last call (catches short taps). */
	public long takeLatchedButtons() {
		return latchedButtons.getAndSet(0);
	}

	/** Gyro rotation since the last call: {yaw, pitch} in degrees (+yaw = right, +pitch = down). */
	public synchronized double[] takeGyro() {
		double[] r = {gyroYaw, gyroPitch};
		gyroYaw = gyroPitch = 0;
		return r;
	}

	/** Buttons that enable gyro aiming while held (from the "Gyro aim" binding). */
	public void setGyroGate(long mask) {
		gyroGate = mask;
	}

	/** Subscribe to the Deck's controls; exclusive = keep Steam's mapping out of it. */
	public synchronized void setInputMode(boolean subscribe, boolean exclusive) {
		if (modeSent && subscribe == modeSubscribe && exclusive == modeExclusive) return;
		if (channel == null) return;
		ByteBuffer msg = payload(4);
		msg.put((byte) (subscribe ? 1 : 0)).put((byte) (exclusive ? 1 : 0));
		if (send(MSG_INPUT_MODE, msg)) {
			modeSubscribe = subscribe;
			modeExclusive = exclusive;
			modeSent = true;
		}
		if (!subscribe) controller = null;
	}

	public void shutdown() {
		running = false;
		closeChannel();
	}

	// --- Frames ------------------------------------------------------------

	/** Makes sure frames of this size can be submitted. Call before acquireSlot. */
	public synchronized boolean ensureAttached(int width, int height) {
		if (channel == null) return false;
		if (shm != null && width == attachedWidth && height == attachedHeight) return true;
		try {
			releaseShm();
			slotSize = width * height * 4;
			shmPath = Path.of("/dev/shm/steamu-companion-" + ProcessHandle.current().pid());
			try (RandomAccessFile file = new RandomAccessFile(shmPath.toFile(), "rw")) {
				file.setLength((long) slotSize * SLOTS);
				shm = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, (long) slotSize * SLOTS);
			}
			attachedWidth = width;
			attachedHeight = height;
			java.util.Arrays.fill(busy, false);

			ByteBuffer msg = payload(152); // sizeof(sus_attach_msg)
			byte[] path = shmPath.toString().getBytes(StandardCharsets.UTF_8);
			msg.put(path).position(128);
			msg.putInt(width).putInt(height).putInt(width * 4).putInt(SLOTS).putInt(FORMAT_RGBA8);
			msg.put((byte) 1); // bottom-up rows, as read back from the GPU
			msg.put(new byte[3]);
			send(MSG_ATTACH, msg);
			SteamUCompanion.LOG.info("Attached to SteamU: {}x{}", width, height);
			return true;
		} catch (IOException e) {
			SteamUCompanion.LOG.warn("Cannot attach to SteamU: {}", e.toString());
			releaseShm();
			return false;
		}
	}

	/** A free slot index, or -1 if the host still holds all of them. */
	public synchronized int acquireSlot() {
		if (shm == null) return -1;
		for (int i = 0; i < SLOTS; i++) {
			if (!busy[i]) {
				busy[i] = true;
				return i;
			}
		}
		return -1;
	}

	/** View of a slot to write `slotSize()` bytes of RGBA pixels into. */
	public synchronized ByteBuffer slotBuffer(int slot) {
		return shm.slice(slot * slotSize, slotSize);
	}

	public synchronized int slotSize() {
		return shm == null ? 0 : slotSize;
	}

	public synchronized void submit(int slot) {
		ByteBuffer msg = payload(16);
		msg.putInt(slot).putInt(0).putLong(System.nanoTime() / 1000);
		if (!send(MSG_FRAME, msg)) busy[slot] = false;
	}

	/** Gives a slot back without submitting it. */
	public synchronized void cancel(int slot) {
		busy[slot] = false;
	}

	private void releaseShm() {
		shm = null; // unmapped by the GC
		attachedWidth = attachedHeight = 0;
		if (shmPath != null) {
			try {
				Files.deleteIfExists(shmPath);
			} catch (IOException ignored) {
			}
			shmPath = null;
		}
	}

	// --- Connection --------------------------------------------------------

	private void run() {
		// STEAMU_SCREEN_SOCKET overrides the path (used by test setups).
		String override = System.getenv("STEAMU_SCREEN_SOCKET");
		String dir = System.getenv("XDG_RUNTIME_DIR");
		if ((override == null || override.isEmpty()) && dir == null) {
			SteamUCompanion.LOG.warn("XDG_RUNTIME_DIR not set, SteamU second screen disabled");
			return;
		}
		Path socket = override != null && !override.isEmpty() ? Path.of(override) : Path.of(dir, "steamu-screen.sock");
		boolean announced = false;
		while (running) {
			try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
				ch.connect(UnixDomainSocketAddress.of(socket));
				channel = ch;
				ByteBuffer hello = payload(72);
				hello.putInt(MAGIC).putShort((short) VERSION).putShort((short) 0);
				hello.put(appName.getBytes(StandardCharsets.UTF_8), 0, Math.min(63, appName.length()));
				send(MSG_HELLO, hello);
				SteamUCompanion.LOG.info("Connected to steamu-host");
				announced = false;
				readLoop(ch);
			} catch (IOException e) {
				if (!announced) {
					SteamUCompanion.LOG.info("steamu-host not reachable ({}), retrying in the background", e.getMessage());
					announced = true;
				}
			}
			synchronized (this) {
				channel = null;
				display = null;
				controller = null;
				modeSent = false;
				releaseShm();
			}
			touches.clear();
			sleep(2000);
		}
	}

	private void readLoop(SocketChannel ch) throws IOException {
		ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		while (running) {
			header.clear();
			readFully(ch, header);
			header.flip();
			int type = header.getShort() & 0xFFFF;
			header.getShort();
			int size = header.getInt();
			if (size < 0 || size > 4096) throw new IOException("bad message size " + size);
			ByteBuffer body = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
			readFully(ch, body);
			body.flip();
			switch (type) {
				case MSG_DISPLAY -> {
					if (size < 8) break;
					boolean connected = body.get() != 0;
					body.get();
					display = new Display(connected, body.getShort() & 0xFFFF, body.getShort() & 0xFFFF,
						body.getShort() & 0xFFFF);
				}
				case MSG_RELEASE -> {
					if (size < 4) break;
					int slot = body.getInt();
					synchronized (this) {
						if (slot >= 0 && slot < SLOTS) busy[slot] = false;
					}
				}
				case MSG_CONTROLLER -> {
					if (size < 48) break;
					long lo = body.getInt() & 0xFFFFFFFFL, hi = body.getInt() & 0xFFFFFFFFL;
					float lx = body.getShort() / 32767f, ly = body.getShort() / 32767f;
					float rx = body.getShort() / 32767f, ry = body.getShort() / 32767f;
					float lt = (body.getShort() & 0xFFFF) / 32767f, rt = (body.getShort() & 0xFFFF) / 32767f;
					body.position(body.position() + 12); // trackpads + pressures
					float gyroPitchRate = body.getShort(), gyroYawRate = body.getShort();
					body.position(body.position() + 2 + 6); // roll + accel
					int seq = body.getInt();
					long buttons = lo | hi << 32;
					latchedButtons.accumulateAndGet(buttons, (a, b) -> a | b);
					controller = new Controller(buttons, lx, ly, rx, ry, Math.min(1, lt), Math.min(1, rt), System.nanoTime());
					// Integrate the gyro per report (4 ms each) for precise aiming.
					int steps = lastSeq < 0 ? 1 : Math.max(1, Math.min(10, seq - lastSeq));
					lastSeq = seq;
					if ((buttons & gyroGate) != 0) {
						double scale = 2000.0 / 32768.0 * 0.004 * steps; // raw -> degrees per report
						synchronized (this) {
							gyroYaw -= gyroYawRate * scale;     // turning the Deck left = look left
							gyroPitch -= gyroPitchRate * scale; // tilting the top back = look up
						}
					}
				}
				case MSG_TOUCH -> {
					if (size < 12) break;
					int t = body.get() & 0xFF;
					int finger = body.get() & 0xFF;
					body.getShort();
					touches.add(new Touch(t, finger, body.getFloat(), body.getFloat()));
				}
				default -> {
				}
			}
		}
	}

	private boolean send(int type, ByteBuffer body) {
		SocketChannel ch = channel;
		if (ch == null) return false;
		body.clear(); // messages are fixed-size structs: always send the whole buffer
		ByteBuffer msg = ByteBuffer.allocate(8 + body.remaining()).order(ByteOrder.LITTLE_ENDIAN);
		msg.putShort((short) type).putShort((short) 0).putInt(body.remaining()).put(body).flip();
		try {
			synchronized (ch) {
				while (msg.hasRemaining()) ch.write(msg);
			}
			return true;
		} catch (IOException e) {
			closeChannel();
			return false;
		}
	}

	private void closeChannel() {
		SocketChannel ch = channel;
		if (ch != null) {
			try {
				ch.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static ByteBuffer payload(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static void readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
		while (buf.hasRemaining()) {
			if (ch.read(buf) < 0) throw new IOException("steamu-host closed the connection");
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}
}
