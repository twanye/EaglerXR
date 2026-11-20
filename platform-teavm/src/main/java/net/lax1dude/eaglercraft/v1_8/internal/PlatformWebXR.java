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

	private static final float[] frame = new float[FRAME_DATA_LENGTH];
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
			presenting = false;
			viewCount = 0;
			return false;
		}
		Float32Array data = WebXRBridge.stereoFrameData();
		if (data == null) {
			presenting = false;
			viewCount = 0;
			return false;
		}
		int n = Math.min(data.getLength(), FRAME_DATA_LENGTH);
		for (int i = 0; i < n; ++i) {
			frame[i] = data.get(i);
		}
		viewCount = Math.max(0, Math.min(2, (int) frame[0]));
		presenting = viewCount > 0;
		return presenting;
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
}
