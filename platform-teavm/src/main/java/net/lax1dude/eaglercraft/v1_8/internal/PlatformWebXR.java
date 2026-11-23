package net.lax1dude.eaglercraft.v1_8.internal;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.webxr.WebXRBridge;
import net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2TextureView;

/**
 * Web implementation of the EaglerXR in-tab stereo seam (shadows the :platform
 * stub by FQN). All browser work is in {@link WebXRBridge}; this class only
 * snapshots the per-frame XR data into a Java array so the camera and the
 * renderer read one consistent pose per game frame.
 *
 * <p>Startup safety (Quest load fix): nothing here touches WebXR, WebGL or the DOM
 * while the game loads. The JS runtime is installed the first time the player is in
 * a world (or opens the pause menu), it only defines functions, and the immersive-VR
 * support query, makeXRCompatible, the XRWebGLLayer and every GL resource wait for the
 * Enter VR press. Any failure in the bridge disables VR for the session instead of
 * propagating into the render loop; {@code ?novr} / {@code localStorage['eaglerxr.disable']='1'}
 * turns it all off.
 */
public final class PlatformWebXR {

	public static final int VIEW_STRIDE = 27;
	public static final int FRAME_DATA_LENGTH = 8 + 2 * VIEW_STRIDE;
	public static final int CONTROLLER_STRIDE = 16;
	public static final int CONTROLLER_DATA_LENGTH = 2 * CONTROLLER_STRIDE;
	public static final int GUI_DATA_LENGTH = 8;

	private static final float[] frame = new float[FRAME_DATA_LENGTH];
	private static final float[] controllers = new float[CONTROLLER_DATA_LENGTH];
	private static final float[] gui = new float[GUI_DATA_LENGTH];
	private static boolean installed = false;
	/** Set when the user disabled VR or any bridge call failed; every entry point is then a no-op. */
	private static boolean disabled = false;
	private static boolean presenting = false;
	private static int viewCount = 0;
	private static int lastWorldActive = -1;

	private PlatformWebXR() {
	}

	/** Installs the JS side once; returns false (and disables VR) if that is not possible. */
	private static boolean ensureInstalled() {
		if (disabled) {
			return false;
		}
		if (installed) {
			return true;
		}
		installed = true;
		try {
			if (WebXRBridge.isDisabledByUser()) {
				disabled = true;
				return false;
			}
			JSObject gl = null;
			try {
				// Returns the canvas' existing context; null when the WebGPU backend owns the canvas.
				gl = PlatformRuntime.getWebGL2Context();
			} catch (Throwable t) {
				gl = null;
			}
			WebXRBridge.installStereo(gl);
			return true;
		} catch (Throwable t) {
			fail("install", t);
			return false;
		}
	}

	/** Turns VR off for this session after a bridge failure; the game keeps running. */
	private static void fail(final String where, final Throwable t) {
		disabled = true;
		presenting = false;
		viewCount = 0;
		try {
			WebXRBridge.exitStereoSession();
		} catch (Throwable ignored) {
		}
		try {
			WebXRBridge.reportError(where + " failed, VR disabled for this session", String.valueOf(t));
		} catch (Throwable ignored) {
		}
	}

	public static boolean isSupported() {
		if (!ensureInstalled()) {
			return false;
		}
		try {
			return WebXRBridge.isStereoSupported();
		} catch (Throwable t) {
			fail("isSupported", t);
			return false;
		}
	}

	public static boolean isPresenting() {
		return presenting;
	}

	public static boolean requestEnterVR() {
		if (!ensureInstalled()) {
			return false;
		}
		try {
			WebXRBridge.markStage("xr-enter-pressed");
			return WebXRBridge.enterStereoSession();
		} catch (Throwable t) {
			fail("enterVR", t);
			return false;
		}
	}

	public static void exitVR() {
		if (installed && !disabled) {
			try {
				WebXRBridge.exitStereoSession();
			} catch (Throwable t) {
				fail("exitVR", t);
			}
		}
	}

	public static String getStatusMessage() {
		if (disabled) {
			return "VR is disabled (see the error overlay)";
		}
		try {
			return installed ? WebXRBridge.stereoStatusMessage() : null;
		} catch (Throwable t) {
			return null;
		}
	}

	public static void setWorldActive(final boolean worldActive) {
		int v = worldActive ? 1 : 0;
		if (v == lastWorldActive) {
			return;
		}
		int previous = lastWorldActive;
		lastWorldActive = v;
		if (worldActive || previous == 1) {
			try {
				WebXRBridge.markStage(worldActive ? "in-world" : "left-world");
			} catch (Throwable ignored) {
			}
		}
		// Nothing to tell the JS side until the player has been in a world: the loading
		// screens and title menu never install or touch the VR runtime.
		if (!worldActive && !installed) {
			return;
		}
		if (!ensureInstalled()) {
			return;
		}
		try {
			WebXRBridge.setStereoWorldActive(worldActive);
		} catch (Throwable t) {
			fail("setWorldActive", t);
		}
	}

	public static boolean pollFrame() {
		if (!installed || disabled) {
			if (presenting) {
				clearFrame();
			}
			return false;
		}
		try {
			return pollFrame0();
		} catch (Throwable t) {
			fail("pollFrame", t);
			clearFrame();
			return false;
		}
	}

	private static boolean pollFrame0() {
		if (!WebXRBridge.isStereoPresenting()) {
			clearFrame();
			return false;
		}
		Float32Array data = WebXRBridge.stereoFrameData();
		if (data == null) {
			clearFrame();
			return false;
		}
		int n = Math.min(data.getLength(), FRAME_DATA_LENGTH);
		for (int i = 0; i < n; ++i) {
			frame[i] = data.get(i);
		}
		viewCount = Math.max(0, Math.min(2, (int) frame[0]));
		presenting = viewCount > 0;
		Float32Array cd = WebXRBridge.stereoControllerData();
		int m = cd == null ? 0 : Math.min(cd.getLength(), CONTROLLER_DATA_LENGTH);
		for (int i = 0; i < m; ++i) {
			controllers[i] = cd.get(i);
		}
		for (int i = m; i < CONTROLLER_DATA_LENGTH; ++i) {
			controllers[i] = 0.0F;
		}
		Float32Array gd = WebXRBridge.stereoGuiData();
		int k = gd == null ? 0 : Math.min(gd.getLength(), GUI_DATA_LENGTH);
		for (int i = 0; i < k; ++i) {
			gui[i] = gd.get(i);
		}
		for (int i = k; i < GUI_DATA_LENGTH; ++i) {
			gui[i] = 0.0F;
		}
		return presenting;
	}

	private static void clearFrame() {
		presenting = false;
		viewCount = 0;
		for (int i = 0; i < CONTROLLER_DATA_LENGTH; ++i) {
			controllers[i] = 0.0F;
		}
		for (int i = 0; i < GUI_DATA_LENGTH; ++i) {
			gui[i] = 0.0F;
		}
	}

	public static float[] getControllerData() {
		return controllers;
	}

	public static int controllerOffset(final int slot) {
		return slot * CONTROLLER_STRIDE;
	}

	public static int getAimController() {
		if (!presenting) {
			return -1;
		}
		if (controllers[CONTROLLER_STRIDE] != 0.0F) {
			return 1;
		}
		return controllers[0] != 0.0F ? 0 : -1;
	}

	public static int getViewCount() {
		return viewCount;
	}

	public static float[] getFrameData() {
		return frame;
	}

	public static int viewOffset(final int view) {
		return 8 + view * VIEW_STRIDE;
	}

	public static void submitEye(final int view, final Object colorTextureView, final int width, final int height) {
		if (!presenting || disabled || !(colorTextureView instanceof WebGL2TextureView)) {
			return;
		}
		try {
			WebXRBridge.copyEye(view, ((WebGL2TextureView) colorTextureView).texture().glId(), width, height);
		} catch (Throwable t) {
			fail("submitEye", t);
		}
	}

	public static float[] getGuiData() {
		return gui;
	}

	public static void submitGui(final Object colorTextureView, final int width, final int height, final int mode, final float laser, final int aimSlot) {
		if (!installed || disabled) {
			return;
		}
		try {
			if (!presenting || !(colorTextureView instanceof WebGL2TextureView)) {
				WebXRBridge.copyGui(null, 0, 0, 0, 0.0F, aimSlot);
				return;
			}
			WebXRBridge.copyGui(((WebGL2TextureView) colorTextureView).texture().glId(), width, height, mode, laser, aimSlot);
		} catch (Throwable t) {
			fail("submitGui", t);
		}
	}

	public static float getConfig(final String key, final float def) {
		if (!installed || disabled) {
			return def;
		}
		double v;
		try {
			v = WebXRBridge.configValue(key, def);
		} catch (Throwable t) {
			return def;
		}
		return Double.isNaN(v) || Double.isInfinite(v) ? def : (float) v;
	}
}
