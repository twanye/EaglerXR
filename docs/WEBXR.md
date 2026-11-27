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
    the XR world yaw (see VR controls below; the body yaw when that is not
    active) before the cull frustum is built; the culling FOV is
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
  the A/X, B/Y face buttons (`buttons[4..5]`) and the thumbstick click
  (`buttons[3]`), into `__eaglerXR.cd`
  (2 slots x 16 floats; slot 0 = left, 1 = right). `selectstart/end` and
  `squeezestart/end` events are OR-ed into trigger/squeeze so input sources
  without a gamepad (e.g. pinching tracked hands, if the browser exposes them as
  tracked pointers) can still act. Layout: `PlatformWebXR` class comment.
- **Aim controller**: the main (right) controller; the other one when it is the
  only tracked pointer (`XRControllerInput26.aimSlot()`).
- **Pick ray** (`XRControllerInput26.computeRay`): the controller's ray is mapped
  into the world exactly like the head in `Camera.applyXRHeadPose` (reference
  space turned by the XR world yaw, XR head position anchored at the
  player's eye position), so ray and view agree. `Minecraft.pick` uses
  `LocalPlayer.eaglerRaycastXR` (same block clip + entity pick as vanilla, but
  along the controller ray) while presenting and the camera entity is the
  player. Reach is still measured from the eye, as the server checks it. The
  block outline (which reads `minecraft.hitResult`) therefore follows the
  controller and serves as the highlight.
- Desktop / not presenting: `aimSlot()` is -1, `computeRay` returns null and
  `update` does nothing, so the mouse pick and key handling are unchanged.

### VR controls (Visor scheme) and VR menus / HUD (this stage)

#### Control scheme

The default bindings follow **Visor** (VisorModStudio/Visor, branch `dev`,
MPL-2.0), its Oculus/Meta Touch defaults in
`visor-core/.../client/input/actions/**` (`getDefaultBindings()` per action,
`OCULUS_TOUCH` entry, right-handed column) and its defaults in
`visor-api/.../client/settings/VRClientSettings.java` (rotation mode HMD, 45 deg
snap step, auto-sprint at 0.9). Visor's code is not copied; the scheme is
reimplemented in `XRControllerInput26`. Where Visor leaves an action unbound,
or uses a mechanic WebXR/this port can't reproduce, the binding is ours and is
marked *deviation*.

Right-handed (main = right controller, off = left). `leftHanded` swaps hands.

| Controller input | In game | In a menu / screen | Source |
| --- | --- | --- | --- |
| Right trigger | attack / mine (hold keeps mining) | left click (right-hand pointer) | Visor |
| Right A | use / place (hold repeats) | right click (right-hand pointer) | Visor |
| Right B | jump | - | Visor |
| Right stick left/right | snap turn 45 deg (smooth turn optional) | - | Visor |
| Right stick up/down | - | scroll (right-hand pointer) | Visor |
| Right stick click | sneak (hold) | shift (held while clicking = shift-click) | Visor |
| Right grip | next hotbar slot | - | *deviation* (Visor: hold grip for a radial hotbar on the hand) |
| Left stick | analog walk/strafe, relative to the head; auto-sprint when pushed fully forward (>= 0.9) | scroll (left-hand pointer) | Visor |
| Left stick click | sprint | - | *deviation* (Visor: sprint unbound, auto-sprint only) |
| Left Y | pause menu | close screen (Escape) | Visor ("menu") |
| Left X | open inventory | right click (left-hand pointer) | *deviation* in game (Visor: X = off-hand use, inventory unbound / via its VR pause menu); Visor in menus |
| Left trigger | drop held item (one) | left click (left-hand pointer) | *deviation* in game (Visor: off-hand attack for two-handed play); Visor in menus |
| Left grip | previous hotbar slot | - | *deviation* (see right grip) |
| Either controller ray | aims (block outline + laser) - right hand | moves the cursor on the menu panel | Visor-like |

- **Look direction follows the head** (Visor's default HMD rotation mode): the
  player's yaw sent to the server is `worldYaw + head yaw` and its pitch is the
  head pitch, so block-placement facing, bows, eating etc. use where you look,
  and walking is relative to the head. `worldYaw` is the snap/smooth-turn offset
  of the XR reference space; the camera (`Camera.applyXRHeadPose`) and the
  controller ray use it as their base. Rotation applied by anything else
  (server teleports, mouse, vehicles) is folded into `worldYaw`.
- Movement is analog (`KeyboardInput` takes `XRControllerInput26.getAnalogMove()`
  as its move vector; the digital keys are still set for code that reads them,
  e.g. boats). Visor-style forward scaling: full walk speed at the sprint
  threshold.
- After switching between game and menu input, buttons are ignored until all
  are released (so the trigger that clicks "Back to Game" doesn't also attack).
- One controller only: its stick walks, no turning, no off-hand actions.
- **Options**: set them in **Options > VR Settings...** (or **VR Settings...** in
  the pause menu while in VR), see "VR features" below. They are stored in
  `localStorage["eaglerxr.<key>"]`; `globalThis.eaglerXRConfig.<key>` (number or
  boolean) overrides the stored value:

  | Key | Default | Meaning |
  | --- | --- | --- |
  | `smoothTurn` | `0` | 1 = smooth turn instead of snap turn |
  | `snapTurnDegrees` | `45` | snap angle (Visor default 45) |
  | `smoothTurnSpeed` | `70` | smooth-turn speed, deg/s at full deflection (~Visor's 0.06 rad/tick) |
  | `moveRelativeToController` | `0` | 1 = walk relative to the left controller instead of the head (auto-sprint off then) |
  | `autoSprint` | `1` | sprint when the stick is pushed past the threshold |
  | `sprintThreshold` | `0.9` | auto-sprint threshold |
  | `leftHanded` | `0` | 1 = swap hands (main = left) |
  | `hudMode` | `0` | HUD placement: 0 face (head-locked), 1 hand (off-hand), 2 floating |
  | `showHands` | `1` | hands: 0 off, 1 both, 2 main hand only |
  | `roomscaleBreaking` | `1` | swing to mine / attack |
  | `hurtCamComfort` | `1` | 1 = no damage tilt/shake in the headset (Vivecraft default) |
  | `vivecraftNetwork` | `0` | 1 = speak the Vivecraft server protocol (experimental) |

  Changes made in the VR Settings screen apply immediately (no need to re-enter VR).
  Example from the console: `localStorage.setItem('eaglerxr.smoothTurn', '1')`, then re-enter VR.

#### VR menus and HUD

- While presenting, `GameRenderer.render` clears the main render target to
  transparent before the GUI pass and draws the GUI alone (screens, HUD,
  hotbar, chat, titles). `PlatformWebXR.submitGui` blits that image
  (premultiplied alpha) into a GUI texture in the web layer.
- The web layer (`WebXRBridge`, `S.updatePanels` / `S.drawOverlays`) draws it on a
  floating quad **per eye at XR frame rate** with the current view pose, after
  the world image:
  - **Screen/menu panel** (any screen open, or no world e.g. title screen):
    1.6 m wide, 1.35 m in front of the head, slightly below eye level, placed
    when the screen opens and then fixed in the room (like Visor's game-screen
    overlay), drawn over a 45% black backdrop.
  - **HUD panel** (in game, no screen): 1.5 m wide, 1.3 m ahead, 0.22 m below
    eye level, follows the head's yaw lazily (35 deg dead zone), not its pitch.
    The 2D crosshair and full-screen camera overlays (vignette, pumpkin,
    spyglass, portal/nausea, powder snow) are skipped in VR (`Hud`).
- **Pointer**: every tracked controller's target ray is intersected with the
  menu panel in the web layer; the hit (u, v) per hand comes back through
  `PlatformWebXR.getGuiData()`. `XRControllerInput26` drives the normal mouse
  path (`MouseHandler.eaglerXRPointer/Button/Scroll`), so hover, click, drag,
  shift-click and scroll work in every vanilla screen. The cursor belongs to
  the hand that last clicked on the panel (else whichever hand points at it); a
  white dot marks each hand's hit point.
- **Lasers**: in menus, a line from each controller to its hit point (short and
  faint when missing); in game, a line from the aim controller to the targeted
  block/entity (faint, reach-length when nothing is hit). Lines are 1 px GL
  lines, drawn without depth (they show through blocks).
- The desktop canvas mirror shows only the GUI (over black) while presenting.
- Desktop (not presenting): no change; the GUI pass and HUD are untouched.


### VR features (webxr-vr-features stage)

Credits: behaviour modelled on **Vivecraft** (github.com/Vivecraft/VivecraftMod,
LGPL-3.0: HUD lock, roomscale swinging, hurt-camera comfort, server protocol) and
**Visor** (VisorModStudio/Visor, branch `dev`, MPL-2.0: control scheme, settings).
Everything is reimplemented for this port; no code from either project is copied.

#### Enter VR on the title screen

The title (main) menu has an **Enter VR** button under Options / Credits. It is
hidden until `navigator.xr.isSessionSupported('immersive-vr')` answers true (the
query starts when the title screen opens, after loading, so the Quest load fix is
unaffected); while presenting it reads **Exit VR**. In VR without a world the
headset shows the menus on the floating screen panel over black. The pause-menu
button and the floating DOM button still work.

#### HUD placement (face / hand / floating)

Like Vivecraft's HUD lock, VR Settings > **HUD** picks where the in-game HUD
(hotbar, hearts, chat...) is drawn; the web layer places the same GUI image:
- **Face** (default): head-locked, 1.1 m ahead and 6 cm low, 0.95 m wide (~46 deg).
- **Hand**: a 0.3 m panel lying on the back of the off-hand controller (7 cm above
  the controller, 10 cm towards the wrist), tilted 30 deg towards you; raise your
  off hand and look at it. Falls back to Face when the off controller is not tracked.
- **Floating**: the previous panel, 1.3 m ahead and lower, following head yaw lazily.
Menus/screens still use the room-fixed screen panel.

#### Visible hands

The web layer draws a simple Minecraft-style arm at each controller's **grip
pose** (target-ray pose if no grip space): a skin-coloured hand cube (7 cm) and a
teal sleeve towards the elbow, flat-shaded. Drawn per eye after the world, before
the HUD panel and lasers, without depth testing (the XR layer has no depth
buffer; back faces are dropped in the vertex shader). VR Settings > **Hands**:
Off / Both (default) / Main hand. The held item is not drawn in the hand yet.

#### VR Settings screen

`VRSettingsScreen` (an options sub-screen, reachable from **Options > VR
Settings...** whenever the browser has `navigator.xr`, and from the pause menu
while in VR) exposes every `eaglerxr.*` option above with normal Minecraft
buttons/sliders (usable with the controller laser). Each change is written to
localStorage at once and reloaded by `XRControllerInput26` / the web layer.

#### Roomscale breaking and attacking (Vivecraft-style)

Reimplemented from Vivecraft's `SwingTracker` (no code copied), once per game
tick: the main controller's "tip" (0.3 m along its ray) is tracked in room space
for speed and in the world for hits.
- A swing faster than **2.5 m/s** (x1.5 in creative) through an entity hitbox
  (+0.3 m margin) attacks that entity once per swing (`gameMode.attack`, arm swing).
- A fast swing that **enters a block from outside** starts mining it and adds 3-7
  ticks of progress (faster swing = more), so repeated swings mine it; creative
  breaks it at once. Between swings the vanilla "attack released" path does not
  abort the progress for 1.5 s.
- Reach is still checked from the eye (`isWithin{Block,Entity}InteractionRange`
  with a 1 block buffer); nothing happens while the trigger (attack) is held, with
  a screen open, or in spectator. Trigger-to-attack/mine works as before.
- VR Settings > **Roomscale breaking** turns it off.

#### Hurt camera comfort

Eye passes never applied view bobbing; the damage tilt/shake (`bobHurt`) is also
skipped while **Hurt camera comfort** is on (default, as in Vivecraft). Turning it
off re-applies vanilla `bobHurt` to each eye.

#### Vivecraft server protocol (experimental, off by default)

`VivecraftNetwork26` speaks the client side of Vivecraft's plugin channel
`vivecraft:data` (reimplemented from VivecraftMod branch `Multiloader-26.3`,
`org.vivecraft.common.network`; no code copied). Enable with VR Settings >
**Vivecraft server support**; it takes effect on the next join.

Transport: `ServerboundCustomPayloadPacket` / `ClientboundCustomPayloadPacket`
normally turn unknown channels into `DiscardedPayload` (bytes dropped); the
fallback now maps `vivecraft:data` and `minecraft:register` to `EaglerRawPayload`
(raw bytes). Eaglercraft multiplayer sends ordinary play-phase packets over the
WebSocket to the Eagler proxy (EaglerXServer/EaglerXBungee/Velocity), which
forwards play packets, including plugin messages, to the backend server.

| Step | Payload (`[ordinal byte][body]`) | Status |
| --- | --- | --- |
| On login / dimension change | C2S `minecraft:register` "vivecraft:data" | verified from source (Bukkit-style channel registration) |
| | C2S VERSION (0): `"Vivecraft-26.2-eaglerxr-0.1.0 VR\n0\n0"` (version, VR/NONVR, max/min network protocol) | format verified from VivecraftMod source |
| Server reply | S2C VERSION (0) = server has Vivecraft; REQUESTDATA (1) = send poses; NETWORK_VERSION (15) = protocol | verified from source |
| Every tick in VR | C2S WORLDSCALE (5) / HEIGHT (12) = 1.0 once; VR_PLAYER_STATE (18): `seated=false, hmd, leftHanded, main, leftHanded, off` | format verified; values are our mapping |
| Legacy server (no NETWORK_VERSION) | HEADDATA (2), CONTROLLER0DATA (3), CONTROLLER1DATA (4) | format verified from the current mod's legacy readers; old plugin versions not checked |
| VR on/off | C2S IS_VR_ACTIVE (17) bool | verified format; server only applies it if it allows VR switching |
| Other VR players | S2C UBERPACKET (8) `UUID, state, worldScale, heightScale` | parsed and stored, **not rendered yet** |

A pose is position (3 floats, metres relative to the player's feet, world axes)
then the orientation quaternion as **w, x, y, z** (local -Z = forward/pointing);
big-endian floats. We claim network protocol 0 (NEW_NETWORKING) only, so the
server does not expect full-body tracking, dual wielding, head-aim or roomscale
attack packets. Our head position is always the player's eye (no room-scale head
position yet); hand poses use the controller target ray.

Guessed / unverified: nothing has been tested against a real Vivecraft server or
a Vivecraft client; whether the Eagler proxy forwards `vivecraft:data` both ways
unchanged; whether old Vivecraft Spigot Extensions accept the `\n0\n0` version
suffix (current Vivecraft does); that the server-side effects of being a VR player
(Vivecraft servers aim projectiles/interaction from the VR hand) feel right with
this port's head-driven look. **Visor** does not use this protocol: it has its own
`visor:channel` (handshake + pose buffers) and needs the Visor mod on the server;
not implemented.

### Quest load safety and on-headset diagnostics (load-fix stage)

Startup no longer touches WebXR. While the game loads (and on the title screen) the VR
code does nothing: the JS runtime (`WebXRBridge.installStereo`) is installed the first
time the player is in a world or opens the pause menu, and it only defines functions.
The `isSessionSupported('immersive-vr')` query that decides whether to show the floating
**Enter VR** button runs 4 s after entering a world; `makeXRCompatible`, the
`XRWebGLLayer`, the stereo atlas / GUI framebuffers and the session itself still wait for
the Enter VR press. Every bridge call is wrapped: a failure turns VR off for the session
(reported on screen) instead of crashing the game.

- `?novr` or `localStorage['eaglerxr.disable'] = '1'` turns all in-tab VR code off.
- Fixed: the VR GUI overlay shaders (PR #3) contained raw newlines inside JS strings, so
  the `installStereo` JSBody was a JavaScript SyntaxError the first time it ran.

Loader memory (headsets): Quest Browser kills the tab on out-of-memory. On standalone
headsets (Quest/Pico/Wolvic user agents) and devices reporting `deviceMemory <= 4`, the
shell compiles the ~103 MB client Wasm first, releases its raw bytes, then the ~43 MB
server image, then the mesh image, instead of all three at once. Mesh workers default off
on headsets (`?mesh=1` turns them on); the server worker stays on (the Wasm-GC client has
no single-thread integrated server). `?seqwasm=0/1` or
`localStorage['eaglerxr.sequentialWasm']` overrides. A headset opening the `file://`
download keeps the server worker. The VR companion tab never auto-opens on standalone
headsets (`?xrcompanion=1` to force) and elsewhere only after the game is ready; the
legacy `eaglerxr_launcher.js` button installs only with `?xrlauncher=1`.

On-screen diagnostics (first script in `<head>`, before the single-file payloads):
- Uncaught errors, unhandled rejections, Wasm traps/compile errors, fatal shell/Java crash
  reports, VR bridge failures and `webglcontextlost` show in a red overlay at the top of the
  page (message + stack + load stage + JS heap), with Copy / Hide / Clear buttons. The last
  one is kept in `localStorage['eaglerxr.lastError']`.
- `localStorage['eaglerxr.loadStage']` is a breadcrumb updated at every major stage
  (`html-head`, `html-parse-payloads`, `payloads-parsed`, `shell-options ...`,
  `wasm-compile-*`, `wasm-client-compiled`, `wasm-workers-compiled`, `wasm-instantiated`,
  `java-main`, `boot-NN-...`, `game-ready`, `in-world`, `xr-*`). An out-of-memory tab kill
  cannot be caught, so the next load shows a yellow "previous attempt" panel naming the
  stage the previous load died in and its last stages/heap readings. A normal reload or
  close is not reported.
- Opt out with `?noerroroverlay` or `localStorage['eaglerxr.errorOverlay'] = '0'`.

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
  offset (IPD) is. Head orientation drives the camera and (since the VR-controls
  stage) the player's yaw/pitch; mouse pitch is ignored while presenting.
- Third-person camera: stereo works but head rotation is not applied to the
  camera (the player's look still follows the head).
- Hands are simple boxes (no held item, no controller model, no skin texture).
  Glowing-entity outlines, post effects and debug gizmos only appear in the
  last eye / mirror.
- VR controls, menus and HUD (stage above) are untested on hardware. Verify:
  panel placement/size and text legibility on Quest; that the GUI image has
  usable alpha (premultiplied) on Quest Browser's WebGL2; that screen blur
  (in-world menus) looks right over the transparent clear; ray-panel cursor
  accuracy (u/v orientation); that hover/drag work while the page reports no
  focus in immersive mode; button indices on Quest Touch (`buttons[3]` stick
  click, `[4]` A/X, `[5]` B/Y); the snap-turn / head-yaw sign conventions; and
  that look-follows-head does not fight vehicles (boats clamp the rider yaw).
- Not done from Visor: radial hotbar on the hand (grip steps slots instead),
  two-handed / off-hand interaction, off-hand roomscale swinging, climbing,
  teleport locomotion, VR keyboard (chat/sign/anvil text entry has no input
  in the headset; the desktop keyboard still works), seated mode, world
  scale, haptics.
- VR features stage (title Enter VR, HUD modes, hands, VR Settings, roomscale,
  hurt-camera toggle, Vivecraft protocol) is CI-compiled only; verify on Quest:
  hand box orientation on the grip pose, Hand HUD placement/legibility, the
  2.5 m/s swing threshold, and that settings sliders are usable with the laser.
- Toggle-sprint / toggle-sneak vanilla options: the VR bindings press and
  release the key mappings, so with "toggle" set each press flips the state.
- In-game laser and lasers in menus are 1 px lines without depth testing; the
  targeted entity is not highlighted.
- Holding a mouse button through a screen change is released on the switch;
  drag-splitting stacks across a screen close is not preserved.

## Browser limitations

WebXR requires a secure context and user activation. Quest Browser support,
available reference spaces, controller profiles, and WebGL framebuffer rules
must be verified on hardware; this checkout has not been tested on a Quest.
