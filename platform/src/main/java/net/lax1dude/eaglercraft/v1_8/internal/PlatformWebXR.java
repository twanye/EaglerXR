package net.lax1dude.eaglercraft.v1_8.internal;

/**
 * EaglerXR in-tab WebXR stereo seam. The real implementation lives in
 * :platform-teavm (same FQN, shadowed on the web classpath like the other
 * platform stubs). On desktop every method is a no-op and {@link #isPresenting()}
 * is always false, so the desktop render path is unchanged.
 *
 * <p>Frame data layout returned by {@link #getFrameData()} (valid after
 * {@link #pollFrame()} returned true):
 * <pre>
 *   [0]      view count (0..2)
 *   [1..3]   viewer (head) position in the XR reference space, metres
 *   [4..7]   viewer (head) orientation quaternion x,y,z,w
 *   per view i, starting at {@link #viewOffset(int)}:
 *     +0..15  projection matrix (column-major, WebGL convention)
 *     +16..18 eye position relative to the viewer position (reference-space axes)
 *     +19..22 eye orientation quaternion x,y,z,w (reference space)
 *     +23..26 viewport x,y,w,h in XRWebGLLayer framebuffer pixels
 * </pre>
 */
public final class PlatformWebXR {

	public static final int VIEW_STRIDE = 27;
	public static final int FRAME_DATA_LENGTH = 8 + 2 * VIEW_STRIDE;

	private static final float[] EMPTY_FRAME = new float[FRAME_DATA_LENGTH];

	private PlatformWebXR() {
	}

	/** True when the browser exposes WebXR and the active renderer can drive an XRWebGLLayer. */
	public static boolean isSupported() {
		return false;
	}

	/** True while an in-tab immersive-vr session with an XRWebGLLayer is presenting. */
	public static boolean isPresenting() {
		return false;
	}

	/**
	 * Requests an immersive-vr session. Must be called while the page still has
	 * transient user activation (e.g. from a button press). Returns false if the
	 * request could not even be started; see {@link #getStatusMessage()}.
	 */
	public static boolean requestEnterVR() {
		return false;
	}

	/** Ends the in-tab session if one is running. */
	public static void exitVR() {
	}

	/** Last human-readable status / error from the WebXR layer, or null. */
	public static String getStatusMessage() {
		return null;
	}

	/** Tells the web layer whether a world is being rendered (shows/hides the DOM "Enter VR" button). */
	public static void setWorldActive(final boolean worldActive) {
	}

	/** Snapshots the latest XR viewer pose for this game frame. Returns true when presenting with views. */
	public static boolean pollFrame() {
		return false;
	}

	/** Number of views captured by the last {@link #pollFrame()}. */
	public static int getViewCount() {
		return 0;
	}

	/** Snapshot written by {@link #pollFrame()}; see the class comment for the layout. Do not modify. */
	public static float[] getFrameData() {
		return EMPTY_FRAME;
	}

	public static int viewOffset(final int view) {
		return 8 + view * VIEW_STRIDE;
	}

	/**
	 * Copies the rendered eye image (a GpuTextureView of the main render target)
	 * into the given view's viewport of the XR layer. {@code colorTextureView} is
	 * typed as Object because :platform does not depend on the blaze3d classes.
	 */
	public static void submitEye(final int view, final Object colorTextureView, final int width, final int height) {
	}
}
