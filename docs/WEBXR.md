# EaglerXR WebXR boundary

`platform-teavm/.../webxr/WebXRBridge.java` is the browser-native boundary for
the VR implementation. Game code reaches it only through the
`net.lax1dude.eaglercraft.v1_8.internal.PlatformWebXR` seam: a no-op stub in
`:platform` (desktop) that `:platform-teavm` shadows by FQN on the web
classpath, like the other `Platform*` classes. On desktop, and in the browser
whenever no in-tab session is presenting, `PlatformWebXR.isPresenting()` is
false and the render loop is byte-for-byte the old path.

## Implemented

### Session / pose bridge (earlier stage)

- Detects `navigator.xr` and `immersive-vr` support.
- `enterSession` / `updateFrame` / `controllerSnapshot`: a pose-only session
  (no layer) used by the companion-tab contract (`eaglerxr_link.js` feeds
  `globalThis.__eaglerXR` from a second tab). Unchanged by the stereo work.

### In-tab stereo rendering (this stage)

- **Entering VR** (needs a WebXR browser, a secure context and the WebGL 2
  renderer; the WebGPU renderer is not supported):
  - an **Enter VR** button in the pause menu (Esc). It is shown only when
    `navigator.xr` and `XRWebGLLayer` exist. Pressing it while in VR shows
    **Exit VR**;
  - a floating **Enter VR** DOM button (bottom-right) that appears while a world
    is being rendered and `isSessionSupported('immersive-vr')` is true. This is
    the main way in on Quest Browser, where there is no Esc key;
  - `eaglerXREnterVR()` / `eaglerXRExitVR()` globals (console / launcher hooks;
    must be called from a user gesture).
  - Leaving: the headset's system menu or **Exit VR** in the pause menu.
- **Session setup** (`WebXRBridge.installStereo`, JS inside the `@JSBody`):
  `requestSession('immersive-vr', {optionalFeatures:['local-floor']})` is called
  synchronously in the gesture, then `gl.makeXRCompatible()` on the game's
  WebGL2 context, an `XRWebGLLayer` (no AA/depth/stencil/alpha) is attached with
  `updateRenderState({baseLayer})`, and a `local-floor` space is requested with
  a `local` fallback.
- **Per XR animation frame**: the viewer pose is read *inside* the XR callback
  (frames are only valid there) and each view's projection matrix, eye offset
  from the head, eye orientation and `layer.getViewport(view)` are written to
  `__eaglerXR.sd`. Then the game's vsync waiter is resumed (PlatformInput now
  also waits on the XR session's rAF while presenting, because the page's
  window rAF can be throttled in immersive mode), and finally the latest stereo
  image is drawn into `layer.framebuffer` with a full-screen triangle.
- **Game side, per game frame**:
  - `GameRenderer.update` calls `PlatformWebXR.pollFrame()` so the camera and
    the renderer use one consistent pose.
  - `Camera.update` (first person only) applies the head orientation on top of
    the player's body yaw before the cull frustum is built; the culling FOV is
    widened (>= 120 deg vertical, aspect >= 1.25) while presenting.
  - `GameRenderer.renderLevelStereo` renders the level once per XR view into
    the main render target: the view matrix is (head-tracked camera without the
    head) x (eye orientation), translated by the eye offset (IPD); the
    projection takes its x/y terms (FOV, asymmetry) from the WebXR matrix and its
    z terms from the game's own reversed-Z projection. After each eye the colour
    target is blitted into that eye's viewport of an atlas texture sized to the
    layer framebuffer (`PlatformWebXR.submitEye`). Entity / block-entity /
    block-breaking submission lists are restored between eyes because
    `LevelRenderer.render` consumes them.
  - Bobbing, the nausea/portal warp, the first-person hand, screen-space
    effects and the 3D crosshair are skipped in eye passes (they use the desktop
    HUD projection).
  - The desktop canvas keeps showing the last eye plus GUI as a mirror.
- Auto-pause on focus loss is suppressed while presenting (the pause menu is
  not visible in the headset).
- Every GL binding/capability the JS touches (framebuffers, viewport, program,
  VAO, texture unit 0 + sampler, clear colour, colour mask, scissor/depth/blend/
  cull/stencil/rasterizer-discard) is saved and restored.
- The companion link ignores companion poses while an in-tab stereo session
  owns `__eaglerXR`.

### Controller pointing and interaction (this stage)

- **Capture** (`WebXRBridge`, `S.captureInputs`, inside the same XR frame
  callback as the viewer pose): for every `tracked-pointer` input source the
  **`targetRaySpace`** pose (the pointing ray, not the grip) is read in the
  session's reference space, together with trigger (`buttons[0]`), squeeze
  (`buttons[1]`), thumbstick (`axes[2..3]`, falling back to `axes[0..1]`) and
  the A/X, B/Y face buttons (`buttons[4..5]`), into `__eaglerXR.cd`
  (2 slots x 16 floats; slot 0 = left, 1 = right). `selectstart/end` and
  `squeezestart/end` events are OR-ed into trigger/squeeze so input sources
  without a gamepad (e.g. pinching tracked hands, if the browser exposes them as
  tracked pointers) can still act. Layout: `PlatformWebXR` class comment.
- **Aim controller**: the right controller; the left one when it is the only
  tracked pointer (`PlatformWebXR.getAimController()`).
- **Pick ray** (`XRControllerInput26.computeRay`): the controller's ray is mapped
  into the world exactly like the head in `Camera.applyXRHeadPose` (reference
  space turned by the player's body yaw, XR head position anchored at the
  player's eye position), so ray and view agree. `Minecraft.pick` uses
  `LocalPlayer.eaglerRaycastXR` (same block clip + entity pick as vanilla, but
  along the controller ray) while presenting and the camera entity is the
  player. Reach is still measured from the eye, as the server checks it. The
  block outline (which reads `minecraft.hitResult`) therefore follows the
  controller and serves as the highlight.
- **Buttons -> key mappings** (`XRControllerInput26.update`, called from the
  input pump next to the touch controls; edge-triggered like `TouchControls26`,
  with press/release hysteresis 0.6/0.35):

  | Input | Action |
  | --- | --- |
  | aim trigger | attack / mine (hold keeps mining) |
  | aim squeeze (grip) | use / place (hold repeats like holding right-click) |
  | aim A | jump |
  | other controller's thumbstick | walk forward/back/strafe (digital, relative to body yaw) |
  | aim thumbstick left/right | 30 deg snap turn (only with two controllers) |

  With one controller its own stick walks and there is no snap turn.
  `continueAttack` also accepts the XR controller as an input owner (the mouse
  is usually not pointer-locked in the headset). All mapped keys are released
  when a screen opens, the session ends or no controller is tracked.
- Desktop / not presenting: `getAimController()` is -1, `computeRay` returns
  null and `update` does nothing, so the mouse pick and key handling are
  unchanged.

## Untested / known limitations

Nothing here has been run on a headset (no Quest hardware was available), and
the change was only checked by CI compilation. In particular, verify on
hardware:

- that `makeXRCompatible()` does not lose the context (multi-GPU desktops can);
- that TeaVM resumes the render thread promptly from the XR rAF callback. The
  design tolerates either order: if the game renders inside the callback the
  image is shown the same frame, otherwise one XR frame later (one frame of
  pose latency, and a held frame if the game is slower than the headset);
- eye orientation conventions (left/right order, sign of eye offsets) and the
  reversed-Z projection splice;
- performance: the world is rendered twice per frame (chunk compile/occlusion
  bookkeeping also runs twice).

Known gaps:

- Eye images are rendered at the canvas resolution and scaled to the eye
  viewport (blurry on high-resolution headsets); the main target is not resized.
- Head **position** (room-scale / leaning) is not applied; only the per-eye
  offset (IPD) is. Head orientation drives the camera; body yaw still comes
  from the mouse; mouse pitch is ignored while presenting in first person.
- Third-person camera: stereo works but head rotation is not applied.
- No GUI, HUD, hand or crosshair in the headset; menus and the title screen show
  black in VR (only the world is rendered). Glowing-entity outlines, post
  effects and debug gizmos only appear in the last eye / mirror.
- Controller interaction (stage above) is untested on hardware: verify the
  ray direction/origin against the block outline, trigger/squeeze mapping on
  Quest Touch controllers, and that hold-to-mine keeps going.
- The player's server-side look direction (yaw/pitch) still comes from the
  body yaw / mouse, not the controller. Things that use the look direction
  rather than the hit result (block placement facing for stairs/furnaces,
  bows, tridents, snowballs, eating/aiming without a target, spear-style
  attack-range items) still use the body direction.
- No laser line or controller model is drawn; the only pointing feedback is the
  vanilla block outline (nothing highlights a targeted entity). No held item /
  hand is drawn in the headset.
- Locomotion is digital (no analog speed), relative to body yaw (not head or
  controller), with no smooth turn, sneak/sprint, hotbar, inventory, drop or
  menu bindings. Room-scale movement, VR HUD/menu input, settings persistence
  and Vivecraft/Visor-style comfort options are still not implemented.

## Browser limitations

WebXR requires a secure context and user activation. Quest Browser support,
available reference spaces, controller profiles, and WebGL framebuffer rules
must be verified on hardware; this checkout has not been tested on a Quest.
