package net.lax1dude.eaglercraft.v1_8.webxr;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebXR;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * EaglerXR: WebXR controller pointing and buttons (in-tab stereo session only).
 *
 * <p>The aim controller is the right one, or the left one when it is the only
 * tracked pointer. Its target ray replaces the mouse/head ray for the game's
 * hit-test ({@code Minecraft.pick} via {@link #computeRay}), so the block
 * outline, mining, attacking and placing all follow the controller.
 *
 * <p>Buttons are fed into the normal key mappings (like the touch controls), so
 * vanilla attack/use logic, cooldowns and hold-to-mine run unchanged:
 * <ul>
 * <li>aim trigger: attack / mine (hold to keep mining)</li>
 * <li>aim squeeze (grip): use / place (hold to repeat)</li>
 * <li>aim A (primary face button): jump</li>
 * <li>other controller's thumbstick: walk (digital, relative to body yaw); with
 * only one controller its own stick walks</li>
 * <li>aim thumbstick left/right (when two controllers): 30 degree snap turn</li>
 * </ul>
 * Nothing here runs unless {@link PlatformWebXR#isPresenting()} is true, so the
 * desktop path is unchanged.
 */
public final class XRControllerInput26 {

	private static final float PRESS = 0.6F;
	private static final float RELEASE = 0.35F;
	private static final float STICK_ON = 0.5F;
	private static final float TURN_ON = 0.7F;
	private static final float TURN_OFF = 0.3F;
	private static final float SNAP_DEGREES = 30.0F;
	/** Sanity bound for the controller offset from the head (metres). */
	private static final float MAX_HAND_OFFSET = 1.5F;
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

	private static boolean attack, use, jump, up, down, left, right;
	private static boolean turnArmed = true;

	private XRControllerInput26() {
	}

	/** Called once per frame from the input pump. */
	public static void update(final Minecraft minecraft) {
		int aim = PlatformWebXR.isPresenting() ? PlatformWebXR.getAimController() : -1;
		if (aim < 0 || minecraft.player == null || minecraft.level == null || minecraft.gui.screen() != null) {
			release(minecraft);
			turnArmed = true;
			return;
		}
		float[] c = PlatformWebXR.getControllerData();
		int a = PlatformWebXR.controllerOffset(aim);
		int other = 1 - aim;
		boolean twoHands = c[PlatformWebXR.controllerOffset(other)] != 0.0F;
		int m = twoHands ? PlatformWebXR.controllerOffset(other) : a;

		boolean nextAttack = hysteresis(c[a + 9], attack);
		boolean nextUse = hysteresis(c[a + 10], use);
		boolean nextJump = hysteresis(c[a + 13], jump);
		float mx = c[m + 11];
		float my = c[m + 12];
		boolean nextUp = my < -STICK_ON;
		boolean nextDown = my > STICK_ON;
		boolean nextLeft = mx < -STICK_ON;
		boolean nextRight = mx > STICK_ON;

		set(minecraft.options.keyAttack, nextAttack, attack, true);
		set(minecraft.options.keyUse, nextUse, use, true);
		set(minecraft.options.keyJump, nextJump, jump, false);
		set(minecraft.options.keyUp, nextUp, up, false);
		set(minecraft.options.keyDown, nextDown, down, false);
		set(minecraft.options.keyLeft, nextLeft, left, false);
		set(minecraft.options.keyRight, nextRight, right, false);
		attack = nextAttack;
		use = nextUse;
		jump = nextJump;
		up = nextUp;
		down = nextDown;
		left = nextLeft;
		right = nextRight;

		if (twoHands) {
			float tx = c[a + 11];
			if (turnArmed && Math.abs(tx) > TURN_ON) {
				turnArmed = false;
				float yaw = minecraft.player.getYRot() + (tx > 0.0F ? SNAP_DEGREES : -SNAP_DEGREES);
				minecraft.player.setYRot(yaw);
				minecraft.player.yRotO = yaw;
			} else if (Math.abs(tx) < TURN_OFF) {
				turnArmed = true;
			}
		} else {
			turnArmed = true;
		}
	}

	/**
	 * The aim controller's pick ray in world space for this frame, or null when
	 * not presenting / no controller. Returns {origin, unit direction}.
	 *
	 * <p>WebXR reference-space axes (x right, y up, -z forward) are the camera's
	 * local axes; the reference space is turned by the entity's body yaw exactly
	 * as {@code Camera.applyXRHeadPose} does, and anchored so that the XR head
	 * position maps to the entity's eye position (head translation is not applied
	 * to the camera either).
	 */
	public static Vec3[] computeRay(final Entity entity, final float partialTicks) {
		if (!PlatformWebXR.isPresenting()) {
			return null;
		}
		int aim = PlatformWebXR.getAimController();
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
		Quaternionf body = new Quaternionf().rotationY((float) Math.PI - entity.getViewYRot(partialTicks) * DEG_TO_RAD);
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
		return new Vec3[] { origin, new Vec3(dir.x / len, dir.y / len, dir.z / len) };
	}

	/** True while the aim trigger is held through this class (keeps mining without a grabbed mouse). */
	public static boolean isDrivingInput() {
		return PlatformWebXR.isPresenting() && PlatformWebXR.getAimController() >= 0;
	}

	private static boolean hysteresis(final float value, final boolean wasDown) {
		return wasDown ? value > RELEASE : value > PRESS;
	}

	private static void set(final KeyMapping mapping, final boolean value, final boolean oldValue, final boolean click) {
		if (value != oldValue) {
			mapping.setDown(value);
			if (value && click) {
				mapping.eaglerTouchClick();
			}
		}
	}

	private static void release(final Minecraft minecraft) {
		if (!(attack || use || jump || up || down || left || right)) {
			return;
		}
		set(minecraft.options.keyAttack, false, attack, false);
		set(minecraft.options.keyUse, false, use, false);
		set(minecraft.options.keyJump, false, jump, false);
		set(minecraft.options.keyUp, false, up, false);
		set(minecraft.options.keyDown, false, down, false);
		set(minecraft.options.keyLeft, false, left, false);
		set(minecraft.options.keyRight, false, right, false);
		attack = use = jump = up = down = left = right = false;
	}
}
