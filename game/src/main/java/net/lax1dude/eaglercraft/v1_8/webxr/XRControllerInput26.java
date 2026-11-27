package net.lax1dude.eaglercraft.v1_8.webxr;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebXR;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * EaglerXR: WebXR controller input (in-tab stereo session only).
 *
 * <p>The default scheme follows the Visor VR mod's Oculus/Meta Touch defaults
 * (VisorModStudio/Visor, MPL-2.0; reimplemented here, no Visor code is
 * copied). Right-handed layout, "main" = right controller, "off" = left;
 * {@code leftHanded} swaps them. See docs/WEBXR.md for the table and the
 * deviations from Visor.
 *
 * <p>In game (no screen open):
 * <ul>
 * <li>main trigger: attack / mine (hold keeps mining) - Visor</li>
 * <li>main A: use / place (hold repeats) - Visor</li>
 * <li>main B: jump - Visor</li>
 * <li>main stick click: sneak (hold) - Visor</li>
 * <li>main stick left/right: 45 degree snap turn, or smooth turn - Visor</li>
 * <li>off stick: analog walk relative to the head (or to the off controller),
 * auto-sprint when pushed fully forward - Visor</li>
 * <li>off Y: pause menu - Visor</li>
 * <li>off X: inventory (Visor leaves inventory unbound; X is its off-hand use)</li>
 * <li>main / off grip: next / previous hotbar slot (Visor: hold grip for a
 * radial hotbar)</li>
 * <li>off trigger: drop held item (unbound in Visor)</li>
 * <li>off stick click: sprint (Visor relies on auto-sprint)</li>
 * </ul>
 *
 * <p>With a screen open the controller rays point at the floating GUI panel:
 * trigger = left click, A / X = right click, stick up/down = scroll, main stick
 * click = shift (shift-click), off Y = close the screen (Escape).
 *
 * <p>The player's look direction (sent to the server) follows the headset:
 * the player's yaw is {@code worldYaw + head yaw} and its pitch is the head
 * pitch, where {@code worldYaw} is the snap/smooth-turn offset of the XR
 * reference space. The camera ({@code Camera.applyXRHeadPose}) and the
 * controller ray use {@code worldYaw} as their base.
 *
 * <p>Nothing here runs unless {@link PlatformWebXR#isPresenting()} is true, so
 * the desktop path is unchanged.
 */
public final class XRControllerInput26 {

	// Controller data offsets (see PlatformWebXR).
	private static final int TRIGGER = 9;
	private static final int SQUEEZE = 10;
	private static final int STICK_X = 11;
	private static final int STICK_Y = 12;
	private static final int PRIMARY = 13;
	private static final int SECONDARY = 14;
	private static final int STICK_CLICK = 15;
	private static final int[] BUTTONS = { TRIGGER, SQUEEZE, PRIMARY, SECONDARY, STICK_CLICK };

	private static final float PRESS = 0.6F;
	private static final float RELEASE = 0.35F;
	private static final float MOVE_DEADZONE = 0.12F;
	private static final float DIGITAL_ON = 0.4F;
	private static final float TURN_ON = 0.6F;
	private static final float TURN_OFF = 0.3F;
	private static final float SMOOTH_DEADZONE = 0.15F;
	private static final float SCROLL_DEADZONE = 0.3F;
	private static final float SCROLL_REPEAT_DELAY = 0.3F;
	private static final float SCROLL_MIN_RATE = 3.0F;
	private static final float SCROLL_MAX_RATE = 15.0F;
	/** Sanity bound for the controller offset from the head (metres). */
	private static final float MAX_HAND_OFFSET = 1.5F;
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
	private static final float RAD_TO_DEG = (float) (180.0 / Math.PI);

	// Options (read when a session starts; web: globalThis.eaglerXRConfig or localStorage "eaglerxr.<key>").
	private static boolean optionsLoaded;
	private static boolean optSmoothTurn;
	private static float optSnapDegrees = 45.0F;
	private static float optSmoothDegreesPerSecond = 70.0F;
	private static boolean optMoveByController;
	private static boolean optAutoSprint = true;
	private static float optSprintThreshold = 0.9F;
	private static boolean optLeftHanded;
	private static boolean optRoomscale = true;
	private static boolean optHurtComfort = true;

	// Roomscale swinging (Vivecraft-style): controller tip tracked per game tick.
	/** Tip speed (m/s, room space) that counts as a swing; Vivecraft's SwingTracker uses 2.5 (x1.5 in creative). */
	private static final float SWING_SPEED = 2.5F;
	/** Distance of the "weapon tip" along the controller ray (Vivecraft measures speed 0.3 m out). */
	private static final float TIP_LENGTH = 0.3F;
	/** Extra reach around entity hitboxes for a hand swing (Vivecraft entityReachAdd default 0.3). */
	private static final double ENTITY_HIT_MARGIN = 0.3;
	private static final long ROOMSCALE_HOLD_MS = 1500L;
	private static Vector3f rsLastTipRef;
	private static Vec3 rsLastTipWorld;
	private static boolean rsWeaponSolid;
	private static long rsLastHitMs;
	private static final List<Entity> rsHitEntities = new ArrayList<>();

	// Button hysteresis state, [slot][index into BUTTONS].
	private static final boolean[][] down = new boolean[2][BUTTONS.length];
	private static final boolean[][] prev = new boolean[2][BUTTONS.length];
	/** Set when switching between game and GUI input; buttons are ignored until all are released. */
	private static boolean waitForRelease;
	private static int lastMode = -1;

	// In-game key mapping state.
	private static boolean attack, use, jump, sneak, sprint, up, downKey, left, right;
	private static Vec2 analogMove;
	private static boolean turnArmed = true;

	// GUI pointer state.
	private static int cursorSlot = -1;
	private static boolean guiLeft, guiRight;
	private static double cursorX = Double.NaN, cursorY = Double.NaN;
	private static int scrollDir;
	private static float scrollHeld, scrollAccum;

	// Head-driven look direction.
	private static boolean yawActive;
	private static Object yawPlayer;
	private static float worldYaw, lastSetYaw, pendingTurn;
	private static long lastNanos;

	// Last in-game pick ray (for the laser length).
	private static Vec3 lastRayOrigin;

	private XRControllerInput26() {
	}

	/** Called once per frame from the input pump. */
	public static void update(final Minecraft minecraft) {
		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 0.0F : Math.min(0.1F, (now - lastNanos) / 1.0E9F);
		lastNanos = now;
		if (!PlatformWebXR.isPresenting()) {
			releaseGame(minecraft);
			releaseGui(minecraft);
			yawActive = false;
			yawPlayer = null;
			optionsLoaded = false;
			lastMode = -1;
			return;
		}
		loadOptions();
		float[] c = PlatformWebXR.getControllerData();
		int main = mainSlot(c);
		int off = main < 0 ? -1 : 1 - main;
		if (off >= 0 && c[PlatformWebXR.controllerOffset(off)] == 0.0F) {
			off = -1;
		}
		updateButtons(c);

		boolean loading = minecraft.gui.overlay() != null;
		Screen screen = minecraft.gui.screen();
		boolean guiMode = screen != null || minecraft.player == null || minecraft.level == null;
		int mode = loading ? 0 : (guiMode ? 2 : 1);
		if (mode != lastMode) {
			waitForRelease = true;
			lastMode = mode;
		}
		if (waitForRelease && !anyButtonDown()) {
			waitForRelease = false;
		}

		if (mode == 1 && main >= 0) {
			releaseGui(minecraft);
			updateGame(minecraft, c, main, off, dt);
		} else {
			releaseGame(minecraft);
			if (mode == 2 && main >= 0) {
				updateGui(minecraft, screen, c, main, off, dt);
			} else {
				releaseGui(minecraft);
			}
		}
		syncLook(minecraft);

		for (int s = 0; s < 2; ++s) {
			System.arraycopy(down[s], 0, prev[s], 0, BUTTONS.length);
		}
	}

	// ------------------------------------------------------------------ game

	private static void updateGame(final Minecraft minecraft, final float[] c, final int main, final int off, final float dt) {
		boolean buttons = !waitForRelease;
		boolean nextAttack = buttons && isDown(main, TRIGGER);
		boolean nextUse = buttons && isDown(main, PRIMARY);
		boolean nextJump = buttons && isDown(main, SECONDARY);
		boolean nextSneak = buttons && isDown(main, STICK_CLICK);

		// Movement: the off-hand stick, or the only controller's stick.
		int ms = off >= 0 ? off : main;
		int mo = PlatformWebXR.controllerOffset(ms);
		float sx = c[mo + STICK_X];
		float sf = -c[mo + STICK_Y];
		float mag = (float) Math.sqrt(sx * sx + sf * sf);
		boolean autoSprinting = false;
		boolean nextUp = false, nextDown = false, nextLeft = false, nextRight = false;
		if (mag > MOVE_DEADZONE) {
			float scaled = Math.min(1.0F, (mag - MOVE_DEADZONE) / (1.0F - MOVE_DEADZONE));
			float rawForward = sf / mag * Math.min(1.0F, mag);
			sx = sx / mag * scaled;
			sf = sf / mag * scaled;
			if (optMoveByController && off >= 0) {
				// Rotate the stick from the off controller's yaw into the head's (= player's) yaw.
				float d = (controllerYawDegrees(c, off) - headYawDegrees()) * DEG_TO_RAD;
				float cos = (float) Math.cos(d), sin = (float) Math.sin(d);
				float rx = sx * cos + sf * sin;
				float rf = -sx * sin + sf * cos;
				sx = rx;
				sf = rf;
			}
			if (optAutoSprint && !(optMoveByController && off >= 0) && rawForward >= optSprintThreshold) {
				autoSprinting = true;
				sf = 1.0F;
			} else if (optAutoSprint && sf > 0.0F) {
				sf = Math.min(1.0F, sf / optSprintThreshold);
			}
			float len = (float) Math.sqrt(sx * sx + sf * sf);
			if (len > 1.0F) {
				sx /= len;
				sf /= len;
			}
			analogMove = new Vec2(-sx, sf);
			nextUp = sf > DIGITAL_ON;
			nextDown = sf < -DIGITAL_ON;
			nextLeft = sx < -DIGITAL_ON;
			nextRight = sx > DIGITAL_ON;
		} else {
			analogMove = null;
		}
		boolean nextSprint = autoSprinting || (buttons && off >= 0 && isDown(off, STICK_CLICK));

		set(minecraft.options.keyAttack, nextAttack, attack, true);
		set(minecraft.options.keyUse, nextUse, use, true);
		set(minecraft.options.keyJump, nextJump, jump, false);
		set(minecraft.options.keyShift, nextSneak, sneak, false);
		set(minecraft.options.keySprint, nextSprint, sprint, false);
		set(minecraft.options.keyUp, nextUp, up, false);
		set(minecraft.options.keyDown, nextDown, downKey, false);
		set(minecraft.options.keyLeft, nextLeft, left, false);
		set(minecraft.options.keyRight, nextRight, right, false);
		attack = nextAttack;
		use = nextUse;
		jump = nextJump;
		sneak = nextSneak;
		sprint = nextSprint;
		up = nextUp;
		downKey = nextDown;
		left = nextLeft;
		right = nextRight;

		if (buttons) {
			if (off >= 0 && pressed(off, PRIMARY)) {
				minecraft.options.keyInventory.eaglerTouchClick();
			}
			if (off >= 0 && pressed(off, SECONDARY)) {
				minecraft.pauseGame(false);
			}
			if (off >= 0 && pressed(off, TRIGGER)) {
				minecraft.options.keyDrop.eaglerTouchClick();
			}
			LocalPlayer player = minecraft.player;
			if (player != null && !player.isSpectator()) {
				int step = (pressed(main, SQUEEZE) ? 1 : 0) - (off >= 0 && pressed(off, SQUEEZE) ? 1 : 0);
				if (step != 0) {
					Inventory inventory = player.getInventory();
					int size = Inventory.getSelectionSize();
					inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() + step, size));
				}
			}
		}

		// Turning with the main stick (only when the other hand has the movement stick).
		if (off >= 0) {
			float tx = c[PlatformWebXR.controllerOffset(main) + STICK_X];
			if (optSmoothTurn) {
				if (Math.abs(tx) > SMOOTH_DEADZONE) {
					float amount = (Math.abs(tx) - SMOOTH_DEADZONE) / (1.0F - SMOOTH_DEADZONE);
					pendingTurn += Math.signum(tx) * amount * optSmoothDegreesPerSecond * dt;
				}
				turnArmed = true;
			} else if (turnArmed && Math.abs(tx) > TURN_ON) {
				turnArmed = false;
				pendingTurn += tx > 0.0F ? optSnapDegrees : -optSnapDegrees;
			} else if (Math.abs(tx) < TURN_OFF) {
				turnArmed = true;
			}
		} else {
			turnArmed = true;
		}
	}

	private static void releaseGame(final Minecraft minecraft) {
		analogMove = null;
		turnArmed = true;
		if (!(attack || use || jump || sneak || sprint || up || downKey || left || right)) {
			return;
		}
		set(minecraft.options.keyAttack, false, attack, false);
		set(minecraft.options.keyUse, false, use, false);
		set(minecraft.options.keyJump, false, jump, false);
		set(minecraft.options.keyShift, false, sneak, false);
		set(minecraft.options.keySprint, false, sprint, false);
		set(minecraft.options.keyUp, false, up, false);
		set(minecraft.options.keyDown, false, downKey, false);
		set(minecraft.options.keyLeft, false, left, false);
		set(minecraft.options.keyRight, false, right, false);
		attack = use = jump = sneak = sprint = up = downKey = left = right = false;
	}

	// ------------------------------------------------------------------ GUI

	private static void updateGui(final Minecraft minecraft, final Screen screen, final float[] c, final int main, final int off, final float dt) {
		float[] g = PlatformWebXR.getGuiData();
		boolean buttons = !waitForRelease;
		// The cursor belongs to the hand that last clicked on the panel; otherwise to whichever hand points at it.
		for (int s : new int[] { main, off }) {
			if (s >= 0 && hits(g, s) && buttons && (pressed(s, TRIGGER) || pressed(s, PRIMARY))) {
				cursorSlot = s;
			}
		}
		if ((cursorSlot < 0 || !hits(g, cursorSlot)) && !guiLeft && !guiRight) {
			if (hits(g, main)) {
				cursorSlot = main;
			} else if (off >= 0 && hits(g, off)) {
				cursorSlot = off;
			}
		}
		if (cursorSlot < 0 || c[PlatformWebXR.controllerOffset(cursorSlot)] == 0.0F) {
			cursorSlot = main;
		}
		int cs = cursorSlot;
		boolean onPanel = hits(g, cs);
		if (onPanel) {
			double x = g[3 + cs * 3] * minecraft.getWindow().getScreenWidth();
			double y = g[4 + cs * 3] * minecraft.getWindow().getScreenHeight();
			if (x != cursorX || y != cursorY) {
				cursorX = x;
				cursorY = y;
				minecraft.mouseHandler.eaglerXRPointer(x, y);
			}
		}
		int mods = buttons && isDown(main, STICK_CLICK) ? 1 : 0; // GLFW_MOD_SHIFT
		boolean nextLeft = buttons && isDown(cs, TRIGGER) && (guiLeft || onPanel);
		boolean nextRight = buttons && isDown(cs, PRIMARY) && (guiRight || onPanel);
		if (nextLeft != guiLeft) {
			guiLeft = nextLeft;
			minecraft.mouseHandler.eaglerXRButton(0, nextLeft, mods);
		}
		if (nextRight != guiRight) {
			guiRight = nextRight;
			minecraft.mouseHandler.eaglerXRButton(1, nextRight, mods);
		}

		// Scroll with the cursor hand's stick (up = scroll up), with hold-to-repeat.
		float sy = -c[PlatformWebXR.controllerOffset(cs) + STICK_Y];
		int dir = Math.abs(sy) < SCROLL_DEADZONE ? 0 : (sy > 0.0F ? 1 : -1);
		if (dir == 0) {
			scrollDir = 0;
		} else if (dir != scrollDir) {
			scrollDir = dir;
			scrollHeld = 0.0F;
			scrollAccum = 0.0F;
			if (onPanel) {
				minecraft.mouseHandler.eaglerXRScroll(dir);
			}
		} else {
			scrollHeld += dt;
			if (scrollHeld >= SCROLL_REPEAT_DELAY) {
				float strength = Math.min(1.0F, (Math.abs(sy) - SCROLL_DEADZONE) / (1.0F - SCROLL_DEADZONE));
				scrollAccum += (SCROLL_MIN_RATE + (SCROLL_MAX_RATE - SCROLL_MIN_RATE) * strength) * dt;
				while (scrollAccum >= 1.0F) {
					scrollAccum -= 1.0F;
					if (onPanel) {
						minecraft.mouseHandler.eaglerXRScroll(dir);
					}
				}
			}
		}

		// Off-hand Y (Visor "menu"): close the screen like Escape.
		if (buttons && off >= 0 && pressed(off, SECONDARY) && screen != null && screen.shouldCloseOnEsc() && !guiLeft && !guiRight) {
			screen.onClose();
		}
	}

	private static void releaseGui(final Minecraft minecraft) {
		if (guiLeft) {
			guiLeft = false;
			minecraft.mouseHandler.eaglerXRButton(0, false, 0);
		}
		if (guiRight) {
			guiRight = false;
			minecraft.mouseHandler.eaglerXRButton(1, false, 0);
		}
		scrollDir = 0;
		cursorX = cursorY = Double.NaN;
	}

	private static boolean hits(final float[] g, final int slot) {
		return slot >= 0 && g[0] == 2.0F && g[2 + slot * 3] != 0.0F;
	}

	// ------------------------------------------------------------------ look direction

	private static void syncLook(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		float[] f = PlatformWebXR.getFrameData();
		if (player == null || f[0] == 0.0F) {
			yawActive = false;
			yawPlayer = null;
			pendingTurn = 0.0F;
			return;
		}
		float head = headYawDegrees();
		if (!yawActive || yawPlayer != player) {
			worldYaw = player.getYRot() - head;
			yawActive = true;
			yawPlayer = player;
		} else {
			// Rotation applied by anything else (server teleport, mouse, vehicles) turns the reference space.
			float external = player.getYRot() - lastSetYaw;
			if (Math.abs(external) > 1.0E-3F) {
				worldYaw += external;
			}
		}
		worldYaw += pendingTurn;
		pendingTurn = 0.0F;
		float yaw = worldYaw + head;
		float pitch = headPitchDegrees();
		player.setYRot(yaw);
		player.yRotO = yaw;
		player.setXRot(pitch);
		player.xRotO = pitch;
		lastSetYaw = player.getYRot();
	}

	/**
	 * Yaw (degrees, Minecraft convention) of the XR reference space's -Z axis for
	 * {@code entity}, or NaN when the head-driven look is not active for it.
	 */
	public static float getWorldYaw(final Entity entity) {
		return yawActive && entity != null && entity == yawPlayer && PlatformWebXR.isPresenting() ? worldYaw : Float.NaN;
	}

	/** Head yaw in the reference space, degrees, positive = turned right. */
	private static float headYawDegrees() {
		float[] f = PlatformWebXR.getFrameData();
		Vector3f fwd = new Quaternionf(f[4], f[5], f[6], f[7]).normalize().transform(new Vector3f(0.0F, 0.0F, -1.0F));
		return (float) Math.atan2(fwd.x, -fwd.z) * RAD_TO_DEG;
	}

	/** Head pitch, degrees, positive = looking down (Minecraft convention). */
	private static float headPitchDegrees() {
		float[] f = PlatformWebXR.getFrameData();
		Vector3f fwd = new Quaternionf(f[4], f[5], f[6], f[7]).normalize().transform(new Vector3f(0.0F, 0.0F, -1.0F));
		float p = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, -fwd.y))) * RAD_TO_DEG;
		return Math.max(-90.0F, Math.min(90.0F, p));
	}

	private static float controllerYawDegrees(final float[] c, final int slot) {
		int o = PlatformWebXR.controllerOffset(slot);
		Vector3f fwd = new Quaternionf(c[o + 5], c[o + 6], c[o + 7], c[o + 8]).normalize().transform(new Vector3f(0.0F, 0.0F, -1.0F));
		return (float) Math.atan2(fwd.x, -fwd.z) * RAD_TO_DEG;
	}

	// ------------------------------------------------------------------ ray

	/** Controller slot that aims (main hand when tracked, else the other one), or -1. */
	public static int aimSlot() {
		return PlatformWebXR.isPresenting() ? mainSlot(PlatformWebXR.getControllerData()) : -1;
	}

	private static int mainSlot(final float[] c) {
		int main = optLeftHanded ? 0 : 1;
		if (c[PlatformWebXR.controllerOffset(main)] != 0.0F) {
			return main;
		}
		return c[PlatformWebXR.controllerOffset(1 - main)] != 0.0F ? 1 - main : -1;
	}

	/**
	 * The aim controller's pick ray in world space for this frame, or null when
	 * not presenting / no controller. Returns {origin, unit direction}.
	 *
	 * <p>WebXR reference-space axes (x right, y up, -z forward) are turned by the
	 * world yaw exactly as {@code Camera.applyXRHeadPose} does, and anchored so
	 * that the XR head position maps to the entity's eye position.
	 */
	public static Vec3[] computeRay(final Entity entity, final float partialTicks) {
		if (!PlatformWebXR.isPresenting()) {
			return null;
		}
		int aim = aimSlot();
		if (aim < 0) {
			return null;
		}
		float[] c = PlatformWebXR.getControllerData();
		float[] f = PlatformWebXR.getFrameData();
		int o = PlatformWebXR.controllerOffset(aim);
		Quaternionf ray = new Quaternionf(c[o + 5], c[o + 6], c[o + 7], c[o + 8]);
		if (ray.lengthSquared() < 1.0E-6F) {
			return null;
		}
		ray.normalize();
		float baseYaw = getWorldYaw(entity);
		if (Float.isNaN(baseYaw)) {
			baseYaw = entity.getViewYRot(partialTicks);
		}
		Quaternionf body = new Quaternionf().rotationY((float) Math.PI - baseYaw * DEG_TO_RAD);
		Vector3f dir = new Vector3f(0.0F, 0.0F, -1.0F);
		ray.transform(dir);
		body.transform(dir);
		Vector3f offset = new Vector3f(c[o + 2] - f[1], c[o + 3] - f[2], c[o + 4] - f[3]);
		if (!(offset.lengthSquared() <= MAX_HAND_OFFSET * MAX_HAND_OFFSET)) {
			offset.set(0.0F);
		}
		body.transform(offset);
		Vec3 eye = entity.getEyePosition(partialTicks);
		Vec3 origin = new Vec3(eye.x + offset.x, eye.y + offset.y, eye.z + offset.z);
		double len = Math.sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z);
		if (len < 1.0E-6) {
			return null;
		}
		lastRayOrigin = origin;
		return new Vec3[] { origin, new Vec3(dir.x / len, dir.y / len, dir.z / len) };
	}

	/**
	 * Maps a point in the XR reference space to the world, like {@link #computeRay}: turned by the
	 * world yaw and anchored so the XR head position is the entity's eye. Null if implausible.
	 */
	private static Vec3 refToWorld(final Entity entity, final Vector3f refPoint, final float partialTicks) {
		float[] f = PlatformWebXR.getFrameData();
		float baseYaw = getWorldYaw(entity);
		if (Float.isNaN(baseYaw)) {
			baseYaw = entity.getViewYRot(partialTicks);
		}
		Vector3f offset = new Vector3f(refPoint.x - f[1], refPoint.y - f[2], refPoint.z - f[3]);
		if (!(offset.lengthSquared() <= (MAX_HAND_OFFSET + TIP_LENGTH) * (MAX_HAND_OFFSET + TIP_LENGTH))) {
			return null;
		}
		new Quaternionf().rotationY((float) Math.PI - baseYaw * DEG_TO_RAD).transform(offset);
		Vec3 eye = entity.getEyePosition(partialTicks);
		return new Vec3(eye.x + offset.x, eye.y + offset.y, eye.z + offset.z);
	}

	/**
	 * Roomscale swinging, once per game tick (Vivecraft's SwingTracker, reimplemented): the main
	 * controller's tip (0.3 m along its ray) is tracked in room space for speed and in the world for
	 * hits. A swing faster than {@link #SWING_SPEED} through an entity's hitbox attacks it once per
	 * swing; one that enters a block from outside starts / continues mining it with 3 to 7 "ticks" of
	 * progress (faster = more), like Vivecraft. The trigger keeps working as before.
	 */
	public static void tickRoomscale(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		int main = aimSlot();
		if (!optRoomscale || main < 0 || player == null || minecraft.level == null || minecraft.gameMode == null
			|| minecraft.gui.screen() != null || minecraft.gui.overlay() != null || player.isSpectator()) {
			rsLastTipRef = null;
			rsLastTipWorld = null;
			rsWeaponSolid = false;
			rsHitEntities.clear();
			return;
		}
		float[] c = PlatformWebXR.getControllerData();
		int o = PlatformWebXR.controllerOffset(main);
		Quaternionf q = new Quaternionf(c[o + 5], c[o + 6], c[o + 7], c[o + 8]);
		if (q.lengthSquared() < 1.0E-6F) {
			return;
		}
		q.normalize();
		Vector3f tipRef = q.transform(new Vector3f(0.0F, 0.0F, -TIP_LENGTH)).add(c[o + 2], c[o + 3], c[o + 4]);
		Vec3 tipWorld = refToWorld(player, tipRef, 1.0F);
		Vector3f lastRef = rsLastTipRef;
		Vec3 lastWorld = rsLastTipWorld;
		rsLastTipRef = tipRef;
		rsLastTipWorld = tipWorld;
		if (tipWorld == null || lastWorld == null || lastRef == null) {
			rsWeaponSolid = false;
			return;
		}
		float speed = tipRef.distance(lastRef) / 0.05F;
		boolean fast = speed > SWING_SPEED * (player.getAbilities().instabuild ? 1.5F : 1.0F);
		boolean attackHeld = minecraft.options.keyAttack.isDown();
		long now = System.currentTimeMillis();

		boolean hitEntity = false;
		if (!fast) {
			rsHitEntities.clear();
		} else if (!attackHeld) {
			AABB sweep = new AABB(lastWorld, tipWorld).inflate(ENTITY_HIT_MARGIN + 0.5);
			for (Entity e : minecraft.level.getEntities(player, sweep, x -> x.isPickable() && x.isAlive() && x != player.getVehicle())) {
				AABB box = e.getBoundingBox().inflate(ENTITY_HIT_MARGIN);
				if (!box.contains(tipWorld) && !box.clip(lastWorld, tipWorld).isPresent()) {
					continue;
				}
				hitEntity = true;
				if (rsHitEntities.contains(e)) {
					continue;
				}
				rsHitEntities.add(e);
				if (player.isWithinEntityInteractionRange(e, 1.0)) {
					minecraft.gameMode.attack(player, e);
					player.swing(InteractionHand.MAIN_HAND);
					rsLastHitMs = now;
				}
			}
		}
		if (hitEntity) {
			return;
		}

		BlockHitResult hit = minecraft.level.clip(new ClipContext(lastWorld, tipWorld, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		boolean solid = hit.getType() == HitResult.Type.BLOCK;
		if (solid && !rsWeaponSolid && fast && !attackHeld && !hit.isInside()) {
			BlockPos pos = hit.getBlockPos();
			if (!minecraft.level.getBlockState(pos).isAir() && player.isWithinBlockInteractionRange(pos, 1.0)) {
				Direction dir = hit.getDirection();
				int hits = 3 + (int) Math.min(speed - SWING_SPEED, 4.0F);
				minecraft.gameMode.eaglerClearDestroyDelay();
				minecraft.gameMode.startDestroyBlock(pos, dir);
				for (int i = 0; i < hits && minecraft.gameMode.isDestroying(); ++i) {
					if (minecraft.gameMode.continueDestroyBlock(pos, dir)) {
						minecraft.level.addBreakingBlockEffect(pos, dir);
					}
				}
				minecraft.gameMode.eaglerClearDestroyDelay();
				player.swing(InteractionHand.MAIN_HAND);
				rsLastHitMs = now;
			}
		}
		rsWeaponSolid = solid;
	}

	/**
	 * True shortly after a roomscale hit while a block is still being mined, so the vanilla
	 * "attack key released" path does not abort the progress between swings.
	 */
	public static boolean isRoomscaleMining(final Minecraft minecraft) {
		return optRoomscale && PlatformWebXR.isPresenting() && minecraft.gameMode != null && minecraft.gameMode.isDestroying()
			&& System.currentTimeMillis() - rsLastHitMs < ROOMSCALE_HOLD_MS;
	}

	/**
	 * In-game laser length for the web layer: distance from the controller to the
	 * hit (metres = blocks), negative reach when nothing is hit, 0 for no laser.
	 */
	public static float laserLength(final Minecraft minecraft) {
		if (!PlatformWebXR.isPresenting() || minecraft.player == null || lastRayOrigin == null || aimSlot() < 0) {
			return 0.0F;
		}
		HitResult hit = minecraft.hitResult;
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			return (float) Math.max(0.05, hit.getLocation().distanceTo(lastRayOrigin));
		}
		return -(float) Math.max(1.0, minecraft.player.blockInteractionRange());
	}

	/** Analog movement (x = left, y = forward) while the XR stick drives it, else null. */
	public static Vec2 getAnalogMove() {
		return PlatformWebXR.isPresenting() ? analogMove : null;
	}

	/** True while the XR controllers drive input (keeps mining without a grabbed mouse). */
	public static boolean isDrivingInput() {
		return PlatformWebXR.isPresenting() && aimSlot() >= 0;
	}

	// ------------------------------------------------------------------ helpers

	private static void loadOptions() {
		if (optionsLoaded) {
			return;
		}
		optionsLoaded = true;
		optSmoothTurn = PlatformWebXR.getConfig("smoothTurn", 0.0F) != 0.0F;
		optSnapDegrees = clamp(PlatformWebXR.getConfig("snapTurnDegrees", 45.0F), 5.0F, 180.0F);
		optSmoothDegreesPerSecond = clamp(PlatformWebXR.getConfig("smoothTurnSpeed", 70.0F), 10.0F, 720.0F);
		optMoveByController = PlatformWebXR.getConfig("moveRelativeToController", 0.0F) != 0.0F;
		optAutoSprint = PlatformWebXR.getConfig("autoSprint", 1.0F) != 0.0F;
		optSprintThreshold = clamp(PlatformWebXR.getConfig("sprintThreshold", 0.9F), 0.3F, 1.0F);
		optLeftHanded = PlatformWebXR.getConfig("leftHanded", 0.0F) != 0.0F;
		optRoomscale = PlatformWebXR.getConfig(VRSettingsScreen.ROOMSCALE, 1.0F) != 0.0F;
		optHurtComfort = PlatformWebXR.getConfig(VRSettingsScreen.HURT_COMFORT, 1.0F) != 0.0F;
	}

	/** Re-reads the options on the next frame (called by the VR Settings screen when one changes). */
	public static void reloadOptions() {
		optionsLoaded = false;
	}

	/** True while presenting with hurt-camera comfort on: the damage tilt/shake is not applied to the headset view. */
	public static boolean isHurtCamSuppressed() {
		return PlatformWebXR.isPresenting() && optHurtComfort;
	}

	private static float clamp(final float v, final float lo, final float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static void updateButtons(final float[] c) {
		for (int s = 0; s < 2; ++s) {
			int o = PlatformWebXR.controllerOffset(s);
			boolean tracked = c[o] != 0.0F;
			for (int i = 0; i < BUTTONS.length; ++i) {
				float v = tracked ? c[o + BUTTONS[i]] : 0.0F;
				down[s][i] = down[s][i] ? v > RELEASE : v > PRESS;
			}
		}
	}

	private static int index(final int field) {
		for (int i = 0; i < BUTTONS.length; ++i) {
			if (BUTTONS[i] == field) {
				return i;
			}
		}
		throw new IllegalArgumentException();
	}

	private static boolean isDown(final int slot, final int field) {
		return slot >= 0 && down[slot][index(field)];
	}

	/** Rising edge this frame. */
	private static boolean pressed(final int slot, final int field) {
		if (slot < 0) {
			return false;
		}
		int i = index(field);
		return down[slot][i] && !prev[slot][i];
	}

	private static boolean anyButtonDown() {
		for (int s = 0; s < 2; ++s) {
			for (int i = 0; i < BUTTONS.length; ++i) {
				if (down[s][i]) {
					return true;
				}
			}
		}
		return false;
	}

	private static void set(final KeyMapping mapping, final boolean value, final boolean oldValue, final boolean click) {
		if (value != oldValue) {
			mapping.setDown(value);
			if (value && click) {
				mapping.eaglerTouchClick();
			}
		}
	}
}
