# EaglerXR WebXR boundary

`platform-teavm/.../webxr/WebXRBridge.java` is the browser-native boundary for
the VR implementation. It does not alter the desktop game loop and does not
claim to implement gameplay by itself.

## Implemented in this stage

- Detects `navigator.xr` and `immersive-vr` support.
- Starts one shared immersive session and requests `local-floor`, falling back
  to `local` when the browser rejects it.
- Uses the WebXR frame loop and exposes headset position/orientation, projection
  coefficients, controller grip pose, handedness, and two button values.
- Handles `end` interruption and clears browser-owned state.
- Uses no runtime dependency; browser state is shared to avoid per-frame object
  churn on Quest-class hardware.

## Integration contract

The game layer should call `enterSession` from an in-game VR button, consume
`updateFrame` from the frame callback, and apply the head pose to the camera
before world rendering. Controller snapshots should feed existing interaction,
raycast, inventory, and combat paths. Desktop rendering remains unchanged when
`isActive()` is false.

## Not yet implemented

Rendering two WebXR views into Eaglercraft's WebGL framebuffer, controller
raycasts, block interaction, room-scale player movement, VR HUD/menu input,
settings persistence, and Vivecraft/Visor-derived comfort options still need to
be wired into the game module. They are deliberately not claimed as working.

## Browser limitations

WebXR requires a secure context and user activation. Quest Browser support,
available reference spaces, controller profiles, and WebGL framebuffer rules
must be verified on hardware; this checkout has not been tested on a Quest.
