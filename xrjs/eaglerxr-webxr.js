/* EaglerXR WebXR runtime; source-sha256=12dfe438f8d054cc410ecc4b92a8163516a7a206bbe66c0fa8835e4eaeecb5fb */
(function (root) {
  "use strict";

  var state = { session: null, referenceSpace: null, frame: null, active: false };

  function supported() {
    return !!(root.navigator && root.navigator.xr &&
      typeof root.navigator.xr.isSessionSupported === "function");
  }

  function unavailableReason() {
    if (!root.navigator || !root.navigator.xr) return "This browser does not expose WebXR.";
    return "Immersive VR is unavailable on this device or in this browsing context.";
  }

  function referenceSpace(session) {
    return session.requestReferenceSpace("local-floor")
      .catch(function () { return session.requestReferenceSpace("local"); });
  }

  function snapshot(frame) {
    var pose = frame.getViewerPose(state.referenceSpace);
    if (!pose) return null;
    var views = [];
    for (var i = 0; i < pose.views.length; ++i) {
      var view = pose.views[i];
      views.push({
        eye: view.eye,
        projectionMatrix: new Float32Array(view.projectionMatrix),
        viewMatrix: new Float32Array(view.transform.inverse.matrix),
        viewport: frame.session.renderState.baseLayer &&
          frame.session.renderState.baseLayer.getViewport(view)
      });
    }
    var controllers = [];
    for (var j = 0; j < frame.session.inputSources.length; ++j) {
      var source = frame.session.inputSources[j];
      if (!source.gripSpace) continue;
      var grip = frame.getPose(source.gripSpace, state.referenceSpace);
      if (!grip) continue;
      var transform = grip.transform;
      var position = transform.position;
      var orientation = transform.orientation;
      var buttons = source.gamepad && source.gamepad.buttons;
      controllers.push({
        handedness: source.handedness || "none",
        position: [position.x, position.y, position.z],
        orientation: [orientation.x, orientation.y, orientation.z, orientation.w],
        primary: buttons && buttons[0] ? buttons[0].value : 0,
        secondary: buttons && buttons[1] ? buttons[1].value : 0,
        targetRayMode: source.targetRayMode
      });
    }
    return { timestamp: performance.now(), views: views, controllers: controllers };
  }

  function end(reason) {
    var callback = state.onEnded;
    state.session = null;
    state.referenceSpace = null;
    state.frame = null;
    state.active = false;
    state.onFrame = null;
    state.onEnded = null;
    if (callback) callback(reason || "WebXR session ended.");
  }

  var api = {
    isSupported: supported,
    unavailableReason: unavailableReason,
    isActive: function () { return state.active; },
    hasRoomScaleBounds: function () {
      return !!(state.referenceSpace && state.referenceSpace.type === "bounded-floor");
    },
    enterSession: function (onFrame, onEnded) {
      if (state.active) return Promise.reject(new Error("A WebXR session is already active."));
      if (!supported()) return Promise.reject(new Error(unavailableReason()));
      state.onFrame = typeof onFrame === "function" ? onFrame : function () {};
      state.onEnded = typeof onEnded === "function" ? onEnded : function () {};
      return root.navigator.xr.isSessionSupported("immersive-vr").then(function (ok) {
        if (!ok) throw new Error("Immersive VR is not supported.");
        return root.navigator.xr.requestSession("immersive-vr", {
          optionalFeatures: ["local-floor", "bounded-floor", "hand-tracking"]
        });
      }).then(function (session) {
        state.session = session;
        state.active = true;
        session.addEventListener("end", function () { end("WebXR session ended."); });
        return referenceSpace(session).then(function (space) {
          state.referenceSpace = space;
          function nextFrame(time, frame) {
            state.frame = frame;
            var current = snapshot(frame);
            if (current) state.onFrame(current);
            if (state.active && state.session === session) session.requestAnimationFrame(nextFrame);
          }
          session.requestAnimationFrame(nextFrame);
          return api;
        });
      }).catch(function (error) {
        if (state.active) end(error && error.message ? error.message : String(error));
        else { state.onFrame = null; state.onEnded = null; }
        throw error;
      });
    },
    exitSession: function () {
      return state.session ? state.session.end() : Promise.resolve();
    }
  };

  root.EaglerXRWebXR = api;
})(typeof globalThis !== "undefined" ? globalThis : window);
