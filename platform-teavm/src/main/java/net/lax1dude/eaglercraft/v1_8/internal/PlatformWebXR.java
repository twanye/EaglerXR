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
	private static boolean presenting = false;
	private static int viewCount = 0;
	private static int lastWorldActive = -1;

	private PlatformWebXR() {
	}

	private static void ensureInstalled() {
		if (installed) {
			return;
		}
		installed = true;
		JSObject gl = null;
		try {
			// Returns the canvas' existing context; null when the WebGPU backend owns the canvas.
			gl = PlatformRuntime.getWebGL2Context();
		} catch (Throwable t) {
			gl = null;
		}
		WebXRBridge.installStereo(gl);
	}

	public static boolean isSupported() {
		ensureInstalled();
		return WebXRBridge.isStereoSupported();
	}

	public static boolean isPresenting() {
		return presenting;
	}

	public static boolean requestEnterVR() {
		ensureInstalled();
		return WebXRBridge.enterStereoSession();
	}

	public static void exitVR() {
		if (installed) {
			WebXRBridge.exitStereoSession();
		}
	}

	public static String getStatusMessage() {
		return installed ? WebXRBridge.stereoStatusMessage() : null;
	}

	public static void setWorldActive(final boolean worldActive) {
		int v = worldActive ? 1 : 0;
		if (v == lastWorldActive) {
			return;
		}
		lastWorldActive = v;
		ensureInstalled();
		WebXRBridge.setStereoWorldActive(worldActive);
	}

	public static boolean pollFrame() {
		if (!installed || !WebXRBridge.isStereoPresenting()) {
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
		if (!presenting || !(colorTextureView instanceof WebGL2TextureView)) {
			return;
		}
		WebXRBridge.copyEye(view, ((WebGL2TextureView) colorTextureView).texture().glId(), width, height);
	}

	public static float[] getGuiData() {
		return gui;
	}

	public static void submitGui(final Object colorTextureView, final int width, final int height, final int mode, final float laser, final int aimSlot) {
		if (!installed) {
			return;
		}
		if (!presenting || !(colorTextureView instanceof WebGL2TextureView)) {
			WebXRBridge.copyGui(null, 0, 0, 0, 0.0F, aimSlot);
			return;
		}
		WebXRBridge.copyGui(((WebGL2TextureView) colorTextureView).texture().glId(), width, height, mode, laser, aimSlot);
	}

	public static float getConfig(final String key, final float def) {
		if (!installed) {
			return def;
		}
		double v = WebXRBridge.configValue(key, def);
		return Double.isNaN(v) || Double.isInfinite(v) ? def : (float) v;
	}
}
