/*
 * EaglerXR companion link (game-tab side).
 *
 * The game tab cannot own a WebXR session that is displayed in a different tab,
 * so the work is split in two:
 *
 *   game tab (this file)          companion tab (eaglerxr-companion.html)
 *   ---------------------         ---------------------------------------
 *   opens the companion tab  -->  detects Quest / PC, checks WebXR support,
 *   streams the game canvas  -->  starts the immersive session and shows the
 *   (WebRTC loopback)             game on a virtual screen
 *   receives head/controller <--  streams head + controller poses
 *   poses and exposes them as
 *   globalThis.__eaglerXR (the object WebXRBridge.java reads)
 *
 * Tabs talk over a direct window.postMessage link (window.open / opener) with a
 * BroadcastChannel as a fallback, so it also works from file:// pages.
 */
(function (global) {
    "use strict";

    var PROTOCOL = 1;
    var CHANNEL = "eaglerxr-link-v1";
    var COMPANION_FILE = "eaglerxr-companion.html";
    var WINDOW_NAME = "eaglerxr-companion";

    var params = new URLSearchParams(global.location.search || "");
    var autoOpenMode = params.get("xrcompanion"); // "0" = never, "1" = always, else only if WebXR VR is supported

    var bc = null;
    try { if (typeof BroadcastChannel === "function") bc = new BroadcastChannel(CHANNEL); } catch (e) { bc = null; }

    var peerWin = null;
    var directConfirmed = false;
    var companionConnected = false;
    var lastSeq = -1;
    var latest = null;
    var listeners = [];
    var pc = null;
    var openBanner = null;

    var state = {
        companionConnected: false,
        platform: null,      // "quest" | "pico" | "pc" | "mobile" (as reported by the companion)
        xrSupported: null,   // null until the companion reports it
        xrActive: false,
        videoActive: false
    };

    function targetOrigin() {
        return global.location.origin === "null" ? "*" : global.location.origin;
    }

    function emit(event, detail) {
        for (var i = 0; i < listeners.length; ++i) {
            try { listeners[i](event, detail, state); } catch (e) { /* listener errors must not break the link */ }
        }
        try { global.dispatchEvent(new CustomEvent("eaglerxr:" + event, { detail: detail })); } catch (e) { /* ignore */ }
    }

    function send(msg) {
        msg.eaglerxr = PROTOCOL;
        var sent = false;
        if (peerWin && !peerWin.closed && (directConfirmed || msg.type === "game-here" || msg.type === "ping")) {
            try { peerWin.postMessage(msg, targetOrigin()); sent = true; } catch (e) { /* fall through to BroadcastChannel */ }
        }
        if (bc && (!directConfirmed || !sent)) {
            try { bc.postMessage(msg); } catch (e) { /* ignore */ }
        }
    }

    // ---------------------------------------------------------------- remote XR frame adapter

    function bridge() {
        return global.__eaglerXR || (global.__eaglerXR = {
            active: false, frame: 0, session: null, ref: null, poses: null, controllers: null
        });
    }

    function vec(p) { return { x: p[0], y: p[1], z: p[2] }; }
    function quat(o) { return { x: o[0], y: o[1], z: o[2], w: o[3] }; }

    var fakeFrame = {
        session: null,
        getViewerPose: function () {
            if (!latest) return null;
            var proj = latest.proj instanceof Float32Array ? latest.proj : new Float32Array(latest.proj || 16);
            return {
                transform: { position: vec(latest.head), orientation: quat(latest.head.slice(3)) },
                views: [{
                    eye: "none",
                    projectionMatrix: proj,
                    transform: { position: vec(latest.head), orientation: quat(latest.head.slice(3)) }
                }]
            };
        },
        getPose: function (space) {
            if (!space || !space.__pose) return null;
            return { transform: { position: vec(space.__pose.p), orientation: quat(space.__pose.o) } };
        }
    };

    var fakeSession = {
        inputSources: [],
        renderState: {},
        remote: true,
        requestAnimationFrame: function (cb) {
            return setTimeout(function () { cb(performance.now(), fakeFrame); }, 0);
        },
        end: function () {
            send({ type: "end-session" });
            return Promise.resolve();
        }
    };
    fakeFrame.session = fakeSession;

    function applyPose(msg) {
        latest = msg;
        var sources = [];
        var list = msg.controllers || [];
        for (var i = 0; i < list.length; ++i) {
            var c = list[i];
            var buttons = [];
            for (var b = 0; b < (c.b ? c.b.length : 0); ++b) buttons.push({ value: c.b[b], pressed: c.b[b] > 0.5 });
            sources.push({
                handedness: c.h || "none",
                targetRayMode: "tracked-pointer",
                gripSpace: { __pose: { p: c.p, o: c.o } },
                gamepad: { buttons: buttons, axes: c.axes || [] }
            });
        }
        fakeSession.inputSources = sources;
        var B = bridge();
        B.frame = fakeFrame;
    }

    function startRemoteSession(msg) {
        var B = bridge();
        if (B.stereo) return; // an in-tab stereo session (WebXRBridge.installStereo) owns __eaglerXR
        B.active = true;
        B.session = fakeSession;
        B.ref = { type: msg.refType || "local-floor" };
        B.poses = null;
        B.controllers = [];
        B.frame = fakeFrame;
        latest = null;
        lastSeq = -1;
        state.xrActive = true;
        emit("session", { active: true, refType: B.ref.type });
    }

    function endRemoteSession(reason) {
        var B = bridge();
        if (B.stereo) { state.xrActive = false; closeVideo(); return; } // never tear down the in-tab session
        B.active = false;
        B.session = null;
        B.ref = null;
        B.poses = null;
        B.controllers = [];
        B.frame = 0;
        latest = null;
        fakeSession.inputSources = [];
        state.xrActive = false;
        closeVideo();
        emit("session", { active: false, reason: reason || "ended" });
    }

    // ---------------------------------------------------------------- canvas mirroring (WebRTC loopback)

    function findCanvas() {
        return document.querySelector("#game_frame canvas") || document.querySelector("canvas");
    }

    function closeVideo() {
        if (pc) { try { pc.close(); } catch (e) { /* ignore */ } pc = null; }
        if (state.videoActive) { state.videoActive = false; emit("video", { active: false }); }
    }

    function startVideo() {
        closeVideo();
        var canvas = findCanvas();
        if (!canvas || typeof canvas.captureStream !== "function" || typeof RTCPeerConnection !== "function") {
            send({ type: "video-unavailable", reason: !canvas ? "no game canvas yet" : "capture/WebRTC unsupported" });
            return;
        }
        var stream;
        try { stream = canvas.captureStream(60); } catch (e) {
            send({ type: "video-unavailable", reason: String(e && e.message || e) });
            return;
        }
        var peer = pc = new RTCPeerConnection({ iceServers: [] });
        stream.getTracks().forEach(function (track) {
            var sender = peer.addTrack(track, stream);
            try {
                var p = sender.getParameters();
                if (!p.encodings || !p.encodings.length) p.encodings = [{}];
                p.encodings[0].maxBitrate = 16000000;
                sender.setParameters(p).catch(function () { /* optional */ });
            } catch (e) { /* optional */ }
        });
        peer.onicecandidate = function (ev) {
            if (ev.candidate) send({ type: "ice", from: "game", candidate: ev.candidate.toJSON ? ev.candidate.toJSON() : ev.candidate });
        };
        peer.onconnectionstatechange = function () {
            if (peer !== pc) return;
            var st = peer.connectionState;
            if (st === "connected" && !state.videoActive) { state.videoActive = true; emit("video", { active: true }); }
            if ((st === "failed" || st === "closed") && state.videoActive) { state.videoActive = false; emit("video", { active: false }); }
        };
        peer.createOffer().then(function (offer) {
            return peer.setLocalDescription(offer);
        }).then(function () {
            send({ type: "offer", sdp: peer.localDescription.sdp });
        }).catch(function (e) {
            send({ type: "video-unavailable", reason: String(e && e.message || e) });
        });
    }

    // ---------------------------------------------------------------- message handling

    function handle(msg, viaDirect) {
        if (!msg || msg.eaglerxr !== PROTOCOL) return;
        switch (msg.type) {
        case "hello":
            companionConnected = state.companionConnected = true;
            state.platform = msg.platform || state.platform;
            if (typeof msg.xrSupported === "boolean") state.xrSupported = msg.xrSupported;
            if (viaDirect) directConfirmed = true;
            send({ type: "game-here", canvas: !!findCanvas() });
            hideBanner();
            emit("companion", { connected: true, platform: state.platform, xrSupported: state.xrSupported });
            break;
        case "ping":
            send({ type: "game-here", canvas: !!findCanvas() });
            break;
        case "xr-support":
            state.xrSupported = !!msg.supported;
            state.platform = msg.platform || state.platform;
            emit("support", { supported: state.xrSupported, platform: state.platform });
            break;
        case "want-video":
            startVideo();
            break;
        case "answer":
            if (pc && msg.sdp) pc.setRemoteDescription({ type: "answer", sdp: msg.sdp }).catch(function () { /* ignore */ });
            break;
        case "ice":
            if (pc && msg.from === "companion" && msg.candidate) pc.addIceCandidate(msg.candidate).catch(function () { /* ignore */ });
            break;
        case "session-start":
            startRemoteSession(msg);
            break;
        case "session-end":
            endRemoteSession(msg.reason);
            break;
        case "pose":
            if (typeof msg.seq === "number") {
                if (msg.seq <= lastSeq && lastSeq - msg.seq < 1000) return; // duplicate or stale
                lastSeq = msg.seq;
            }
            if (bridge().stereo) return; // in-tab stereo session active: ignore companion poses
            if (!state.xrActive) startRemoteSession(msg);
            applyPose(msg);
            break;
        case "companion-closing":
            companionConnected = state.companionConnected = false;
            directConfirmed = false;
            if (state.xrActive) endRemoteSession("companion closed");
            emit("companion", { connected: false });
            break;
        }
    }

    global.addEventListener("message", function (ev) {
        if (!ev.data || ev.data.eaglerxr !== PROTOCOL) return;
        if (ev.origin !== global.location.origin) return;
        if (ev.source && ev.source !== global) peerWin = ev.source;
        handle(ev.data, true);
    });
    if (bc) {
        bc.onmessage = function (ev) {
            if (directConfirmed && ev.data && ev.data.type !== "hello") return; // direct link already carries traffic
            handle(ev.data, false);
        };
    }

    global.addEventListener("pagehide", function () {
        send({ type: "game-closing" });
        closeVideo();
    });

    // ---------------------------------------------------------------- opening the companion tab

    function companionURL() {
        var url = new URL(COMPANION_FILE, global.location.href);
        url.searchParams.set("from", "game");
        return url.href;
    }

    function openCompanion() {
        if (companionConnected && peerWin && !peerWin.closed) {
            try { peerWin.focus(); } catch (e) { /* ignore */ }
            return true;
        }
        var win = null;
        try { win = global.open(companionURL(), WINDOW_NAME); } catch (e) { win = null; }
        if (win) {
            peerWin = win;
            hideBanner();
            return true;
        }
        return false;
    }

    function hideBanner() {
        if (openBanner && openBanner.parentNode) openBanner.parentNode.removeChild(openBanner);
        openBanner = null;
        disarmGestureOpen();
    }

    function showBanner() {
        if (openBanner || !document.body) return;
        openBanner = document.createElement("button");
        openBanner.type = "button";
        openBanner.id = "eaglerxr-open-companion";
        openBanner.textContent = "Open VR companion tab";
        openBanner.style.cssText = "position:fixed;left:18px;bottom:18px;z-index:2147483647;padding:10px 16px;" +
            "font:600 14px sans-serif;border:1px solid #777;border-radius:5px;background:#202124;color:#fff;cursor:pointer;opacity:.92";
        openBanner.addEventListener("click", function (ev) {
            ev.stopPropagation();
            if (openCompanion()) hideBanner();
        });
        document.body.appendChild(openBanner);
    }

    var gestureArmed = false;
    var gestureEvents = ["pointerdown", "keydown", "touchend"];
    function onGesture() {
        if (openCompanion()) hideBanner();
    }
    function armGestureOpen() {
        if (gestureArmed) return;
        gestureArmed = true;
        gestureEvents.forEach(function (name) { global.addEventListener(name, onGesture, true); });
    }
    function disarmGestureOpen() {
        if (!gestureArmed) return;
        gestureArmed = false;
        gestureEvents.forEach(function (name) { global.removeEventListener(name, onGesture, true); });
    }

    function vrSupported() {
        try {
            if (!global.navigator || !navigator.xr || typeof navigator.xr.isSessionSupported !== "function") {
                return Promise.resolve(false);
            }
            return navigator.xr.isSessionSupported("immersive-vr").then(function (ok) { return !!ok; }, function () { return false; });
        } catch (e) {
            return Promise.resolve(false);
        }
    }

    function autoOpen() {
        if (autoOpenMode === "0") return;
        // Ask first: an already-open companion tab must not be reloaded.
        send({ type: "ping" });
        setTimeout(function () {
            if (companionConnected) return;
            var decide = autoOpenMode === "1" ? Promise.resolve(true) : vrSupported();
            decide.then(function (wanted) {
                if (!wanted || companionConnected) return;
                if (!openCompanion()) { // popup blocked: no user gesture on page load
                    showBanner();
                    armGestureOpen();
                }
            });
        }, 600);
    }

    global.eaglerXRLink = {
        state: state,
        open: openCompanion,
        isCompanionConnected: function () { return companionConnected; },
        isXRActive: function () { return state.xrActive; },
        endSession: function () { send({ type: "end-session" }); },
        onChange: function (cb) { if (typeof cb === "function") listeners.push(cb); },
        // Latest head/controller snapshot streamed from the companion, or null.
        latestPose: function () { return latest; }
    };

    // EaglerXR: never open a second tab (or arm the first-click popup) while the game is
    // still loading: on a standalone headset the loading tab would be backgrounded, and
    // Quest Browser discards background tabs under memory pressure. Standalone headsets
    // also do not need the companion at all (the game tab enters VR itself via the in-game
    // Enter VR button), so they only open it on ?xrcompanion=1.
    var standaloneHeadset = /OculusBrowser|Quest|Pico|Wolvic|MetaQuest/i.test((global.navigator && navigator.userAgent) || "");
    function autoOpenWhenReady() {
        if (autoOpenMode === "0" || (standaloneHeadset && autoOpenMode !== "1")) return;
        var started = Date.now();
        (function wait() {
            if (global.__eaglerGameReady === true) { setTimeout(autoOpen, 1500); return; }
            if (Date.now() - started > 600000) return; // never reached the game: stay out of the way
            setTimeout(wait, 1000);
        })();
    }
    if (document.readyState === "complete") autoOpenWhenReady();
    else global.addEventListener("load", autoOpenWhenReady, { once: true });
}(typeof window !== "undefined" ? window : this));
