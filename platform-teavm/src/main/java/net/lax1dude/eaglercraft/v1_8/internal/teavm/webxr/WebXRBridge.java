/*
 * EaglerXR WebXR boundary.
 *
 * This class intentionally contains the browser-specific surface only. Game
 * code consumes the latest snapshot and decides how to apply it to the
 * camera, player, GUI, and interaction systems.
 */
package net.lax1dude.eaglercraft.v1_8.internal.teavm.webxr;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;

/** Browser-native WebXR session and input bridge. */
public final class WebXRBridge {

	private WebXRBridge() {
	}

	@JSFunctor
	public interface FrameCallback extends JSObject {
		void onFrame();
	}

	@JSFunctor
	public interface SessionCallback extends JSObject {
		void onSessionEnded(String reason);
	}

	@JSBody(script = "return !!(globalThis.navigator && navigator.xr && typeof navigator.xr.isSessionSupported === 'function');")
	public static native boolean isSupported();

	@JSBody(script = "if(!globalThis.navigator || !navigator.xr) return 'This browser does not expose WebXR.'; return 'Immersive VR is unavailable on this device or in this browsing context.';")
	public static native String unavailableReason();

	@JSBody(params = { "onFrame", "onEnded" }, script =
		"var B=globalThis.__eaglerXR||(globalThis.__eaglerXR={active:false,frame:0,session:null,ref:null,poses:null,controllers:null});"
		+ "if(B.active||!navigator.xr||!navigator.xr.isSessionSupported){return false;}"
		+ "navigator.xr.isSessionSupported('immersive-vr').then(function(ok){if(!ok){onEnded('Immersive VR is not supported.');return null;}"
		+ "return navigator.xr.requestSession('immersive-vr',{optionalFeatures:['local-floor','bounded-floor','hand-tracking']});})"
		+ ".then(function(s){if(!s)return;B.session=s;B.active=true;B.ref=null;B.poses=null;B.controllers=[];"
		+ "s.addEventListener('end',function(){B.active=false;B.session=null;B.ref=null;B.poses=null;B.controllers=[];onEnded('WebXR session ended.');});"
		+ "s.requestReferenceSpace('local-floor').catch(function(){return s.requestReferenceSpace('local');}).then(function(r){B.ref=r;s.requestAnimationFrame(function(t,f){B.frame=f;onFrame();});}).catch(function(e){B.active=false;onEnded(String(e&&e.message||e));});"
		+ "}).catch(function(e){B.active=false;onEnded(String(e&&e.message||e));});return true;")
	public static native boolean enterSession(FrameCallback onFrame, SessionCallback onEnded);

	@JSBody(script = "var B=globalThis.__eaglerXR; if(!B||!B.session)return false; B.session.end(); return true;")
	public static native boolean endSession();

	@JSBody(script = "return !!(globalThis.__eaglerXR&&globalThis.__eaglerXR.active);")
	public static native boolean isActive();

	/** Returns [head xyz, head quaternion xyzw, projection coefficients]. */
	@JSBody(script =
		"var B=globalThis.__eaglerXR;if(!B||!B.active||!B.frame||!B.ref)return null;"
		+ "var p=B.frame.getViewerPose(B.ref);if(!p||!p.views.length)return null;"
		+ "var v=p.views[0],t=v.transform;var o=t.orientation,q=t.position;"
		+ "B.poses=[q.x,q.y,q.z,o.x,o.y,o.z,o.w,v.projectionMatrix[0],v.projectionMatrix[5],v.projectionMatrix[10],v.projectionMatrix[14]];"
		+ "B.controllers=[];for(var i=0;i<B.frame.session.inputSources.length;i++){var s=B.frame.session.inputSources[i];if(!s.gripSpace)continue;var g=B.frame.getPose(s.gripSpace,B.ref);if(!g)continue;var a=g.transform.position,b=g.transform.orientation;B.controllers.push(s.handedness==='left'?0:1,a.x,a.y,a.z,b.x,b.y,b.z,b.w,s.gamepad?((s.gamepad.buttons[0]&&s.gamepad.buttons[0].value)||0):0,s.gamepad?((s.gamepad.buttons[1]&&s.gamepad.buttons[1].value)||0):0);}"
		+ "B.frame.session.requestAnimationFrame(function(t,f){B.frame=f;});return B.poses;")
	public static native float[] updateFrame();

	/** Returns 10 values per controller: hand, xyz, quaternion xyzw, two buttons. */
	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.controllers?B.controllers.slice():null;")
	public static native float[] controllerSnapshot();

	@JSBody(script = "var B=globalThis.__eaglerXR;return !!(B&&B.ref&&B.ref.type==='bounded-floor');")
	public static native boolean hasRoomScaleBounds();

	// ------------------------------------------------------------------ in-tab stereo rendering
	//
	// The stereo path owns its own immersive-vr session (separate from the
	// pose-only enterSession above and from the companion-tab link): it makes the
	// game's WebGL2 context XR-compatible, attaches an XRWebGLLayer and, on every
	// XR animation frame, (1) captures the viewer pose + per-view projection,
	// eye offset, orientation and layer viewport into __eaglerXR.sd, (2) resumes
	// the game's vsync waiter, (3) draws the latest stereo "atlas" texture into
	// layer.framebuffer. The game renders each eye into its main render target
	// and copyEye() blits it into that eye's viewport of the atlas. Every GL
	// binding/capability touched here is saved and restored so the WebGL2
	// backend's own state assumptions stay valid.

	/** Installs the stereo runtime once; {@code gl} is the game's WebGL2 context (may be null). */
	@JSBody(params = { "gl" }, script = """
		var B = globalThis.__eaglerXR || (globalThis.__eaglerXR = {active:false, frame:0, session:null, ref:null, poses:null, controllers:null});
		if (B.stereoApi) { if (gl && !B.gl) B.gl = gl; return; }
		B.gl = gl || null;
		B.stereo = false; B.pending = false; B.layer = null; B.msg = null; B.world = false; B.xrOk = false;
		B.sd = new Float32Array(62); B.vsync = [];
		// Controller snapshot, 2 slots x 16 floats (slot 0 = left, 1 = right), see PlatformWebXR.
		B.cd = new Float32Array(32);
		var S = B.stereoApi = {};
		var CAPS = null;
		S.supported = function () {
		  return !!(B.gl && globalThis.navigator && navigator.xr && typeof navigator.xr.requestSession === 'function' && typeof XRWebGLLayer === 'function');
		};
		S.save = function (g) {
		  if (!CAPS) CAPS = [g.SCISSOR_TEST, g.DEPTH_TEST, g.BLEND, g.CULL_FACE, g.STENCIL_TEST, g.RASTERIZER_DISCARD];
		  var st = {
		    rfb: g.getParameter(g.READ_FRAMEBUFFER_BINDING), dfb: g.getParameter(g.DRAW_FRAMEBUFFER_BINDING),
		    vp: g.getParameter(g.VIEWPORT), prog: g.getParameter(g.CURRENT_PROGRAM), vao: g.getParameter(g.VERTEX_ARRAY_BINDING),
		    at: g.getParameter(g.ACTIVE_TEXTURE), cc: g.getParameter(g.COLOR_CLEAR_VALUE), cm: g.getParameter(g.COLOR_WRITEMASK), en: []
		  };
		  for (var i = 0; i < CAPS.length; ++i) st.en.push(g.isEnabled(CAPS[i]));
		  g.activeTexture(g.TEXTURE0);
		  st.tex = g.getParameter(g.TEXTURE_BINDING_2D); st.smp = g.getParameter(g.SAMPLER_BINDING);
		  return st;
		};
		S.restore = function (g, st) {
		  g.bindFramebuffer(g.READ_FRAMEBUFFER, st.rfb); g.bindFramebuffer(g.DRAW_FRAMEBUFFER, st.dfb);
		  g.viewport(st.vp[0], st.vp[1], st.vp[2], st.vp[3]);
		  g.useProgram(st.prog); g.bindVertexArray(st.vao);
		  g.activeTexture(g.TEXTURE0); g.bindTexture(g.TEXTURE_2D, st.tex); g.bindSampler(0, st.smp);
		  g.activeTexture(st.at);
		  g.clearColor(st.cc[0], st.cc[1], st.cc[2], st.cc[3]); g.colorMask(st.cm[0], st.cm[1], st.cm[2], st.cm[3]);
		  for (var i = 0; i < CAPS.length; ++i) { if (st.en[i]) g.enable(CAPS[i]); else g.disable(CAPS[i]); }
		};
		S.plain = function (g) {
		  for (var i = 0; i < CAPS.length; ++i) g.disable(CAPS[i]);
		  g.colorMask(true, true, true, true);
		};
		S.ensureProgram = function (g) {
		  if (B.prog) return true;
		  if (B.progFailed) return false;
		  function sh(t, s) { var x = g.createShader(t); g.shaderSource(x, s); g.compileShader(x); return x; }
		  var vs = sh(g.VERTEX_SHADER, '#version 300 es\\nprecision highp float;\\nout vec2 v_uv;\\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));v_uv=p;gl_Position=vec4(p*2.0-1.0,0.0,1.0);}\\n');
		  var fs = sh(g.FRAGMENT_SHADER, '#version 300 es\\nprecision highp float;\\nuniform sampler2D u_tex;\\nin vec2 v_uv;\\nout vec4 o;\\nvoid main(){o=vec4(texture(u_tex,v_uv).rgb,1.0);}\\n');
		  var p = g.createProgram(); g.attachShader(p, vs); g.attachShader(p, fs); g.linkProgram(p);
		  if (!g.getProgramParameter(p, g.LINK_STATUS)) { B.progFailed = true; console.error('eaglerxr: stereo present program failed: ' + g.getProgramInfoLog(p)); return false; }
		  B.prog = p; B.progTex = g.getUniformLocation(p, 'u_tex'); B.vao = g.createVertexArray();
		  B.sampler = g.createSampler();
		  g.samplerParameteri(B.sampler, g.TEXTURE_MIN_FILTER, g.LINEAR); g.samplerParameteri(B.sampler, g.TEXTURE_MAG_FILTER, g.LINEAR);
		  g.samplerParameteri(B.sampler, g.TEXTURE_WRAP_S, g.CLAMP_TO_EDGE); g.samplerParameteri(B.sampler, g.TEXTURE_WRAP_T, g.CLAMP_TO_EDGE);
		  return true;
		};
		S.ensureAtlas = function (g, w, h) {
		  if (B.atlasTex && B.atlasW === w && B.atlasH === h) return;
		  S.freeAtlas(g);
		  B.atlasTex = g.createTexture(); g.bindTexture(g.TEXTURE_2D, B.atlasTex);
		  g.texStorage2D(g.TEXTURE_2D, 1, g.RGBA8, w, h);
		  B.atlasFbo = g.createFramebuffer(); g.bindFramebuffer(g.DRAW_FRAMEBUFFER, B.atlasFbo);
		  g.framebufferTexture2D(g.DRAW_FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, B.atlasTex, 0);
		  g.clearColor(0, 0, 0, 1); g.clear(g.COLOR_BUFFER_BIT);
		  B.readFbo = B.readFbo || g.createFramebuffer();
		  B.atlasW = w; B.atlasH = h; B.atlasReady = false;
		};
		S.freeAtlas = function (g) {
		  if (B.atlasFbo) g.deleteFramebuffer(B.atlasFbo);
		  if (B.atlasTex) g.deleteTexture(B.atlasTex);
		  B.atlasFbo = null; B.atlasTex = null; B.atlasW = 0; B.atlasH = 0; B.atlasReady = false;
		};
		S.copyEye = function (i, tex, w, h) {
		  var g = B.gl, layer = B.layer, d = B.sd;
		  if (!B.stereo || !g || !layer || !tex || i >= d[0] || w <= 0 || h <= 0) return;
		  var o = 8 + i * 27, vx = d[o + 23], vy = d[o + 24], vw = d[o + 25], vh = d[o + 26];
		  if (vw <= 0 || vh <= 0) return;
		  var st = S.save(g);
		  try {
		    S.ensureAtlas(g, layer.framebufferWidth, layer.framebufferHeight);
		    S.plain(g);
		    g.bindFramebuffer(g.READ_FRAMEBUFFER, B.readFbo);
		    g.framebufferTexture2D(g.READ_FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, tex, 0);
		    g.bindFramebuffer(g.DRAW_FRAMEBUFFER, B.atlasFbo);
		    g.blitFramebuffer(0, 0, w, h, vx, vy, vx + vw, vy + vh, g.COLOR_BUFFER_BIT, g.LINEAR);
		    g.framebufferTexture2D(g.READ_FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, null, 0);
		    B.atlasReady = true;
		  } finally { S.restore(g, st); }
		};
		S.present = function () {
		  var g = B.gl, layer = B.layer;
		  if (!g || !layer) return;
		  var st = S.save(g);
		  try {
		    g.bindFramebuffer(g.FRAMEBUFFER, layer.framebuffer);
		    g.viewport(0, 0, layer.framebufferWidth, layer.framebufferHeight);
		    S.plain(g);
		    if (B.atlasTex && B.atlasReady && B.world && S.ensureProgram(g)) {
		      g.useProgram(B.prog); g.bindVertexArray(B.vao);
		      g.activeTexture(g.TEXTURE0); g.bindTexture(g.TEXTURE_2D, B.atlasTex); g.bindSampler(0, B.sampler);
		      g.uniform1i(B.progTex, 0);
		      g.drawArrays(g.TRIANGLES, 0, 3);
		    } else {
		      g.clearColor(0, 0, 0, 1); g.clear(g.COLOR_BUFFER_BIT);
		    }
		  } finally { S.restore(g, st); }
		};
		// Controllers: target-ray pose (the pointing ray, not the grip) plus trigger /
		// squeeze / thumbstick / A,B for each tracked-pointer input source, read in
		// the same XR frame as the viewer pose. select/squeeze events are OR-ed in so
		// sources without a gamepad (e.g. tracked hands pinching) can still act.
		S.captureInputs = function (frame) {
		  var c = B.cd; c.fill(0);
		  var b = null;
		  function bv(k) { var x = b[k]; return x ? Math.max(x.value || 0, x.pressed ? 1 : 0) : 0; }
		  var list = frame.session.inputSources;
		  if (!list) return;
		  for (var i = 0; i < list.length; ++i) {
		    var s = list[i];
		    if (!s || s.targetRayMode !== 'tracked-pointer' || !s.targetRaySpace) continue;
		    var slot = s.handedness === 'left' ? 0 : (s.handedness === 'right' ? 1 : (c[16] ? 0 : 1));
		    var o = slot * 16;
		    if (c[o]) continue;
		    var rp = frame.getPose(s.targetRaySpace, B.ref);
		    if (!rp) continue;
		    var p = rp.transform.position, q = rp.transform.orientation;
		    var gp = s.gamepad, ax = (gp && gp.axes) || [];
		    b = (gp && gp.buttons) || [];
		    c[o] = 1; c[o + 1] = s.handedness === 'left' ? 0 : (s.handedness === 'right' ? 1 : 2);
		    c[o + 2] = p.x; c[o + 3] = p.y; c[o + 4] = p.z;
		    c[o + 5] = q.x; c[o + 6] = q.y; c[o + 7] = q.z; c[o + 8] = q.w;
		    c[o + 9] = Math.max(bv(0), s.__eSel ? 1 : 0);
		    c[o + 10] = Math.max(bv(1), s.__eSqz ? 1 : 0);
		    c[o + 11] = (ax.length >= 4 ? ax[2] : (ax.length >= 2 ? ax[0] : 0)) || 0;
		    c[o + 12] = (ax.length >= 4 ? ax[3] : (ax.length >= 2 ? ax[1] : 0)) || 0;
		    c[o + 13] = bv(4); c[o + 14] = bv(5);
		  }
		};
		S.hookInputEvents = function (s) {
		  function on(type, key, val) { s.addEventListener(type, function (e) { if (e && e.inputSource) e.inputSource[key] = val; }); }
		  on('selectstart', '__eSel', true); on('selectend', '__eSel', false);
		  on('squeezestart', '__eSqz', true); on('squeezeend', '__eSqz', false);
		  s.addEventListener('inputsourceschange', function (e) {
		    var r = (e && e.removed) || [];
		    for (var i = 0; i < r.length; ++i) { r[i].__eSel = false; r[i].__eSqz = false; }
		  });
		};
		S.flushVsync = function () {
		  var w = B.vsync; B.vsync = [];
		  for (var i = 0; i < w.length; ++i) { try { w[i](); } catch (e) { console.error(e); } }
		};
		S.onFrame = function (t, frame) {
		  var s = frame.session;
		  if (!B.stereo || s !== B.session) return;
		  s.requestAnimationFrame(S.onFrame);
		  try {
		    var pose = frame.getViewerPose(B.ref);
		    if (pose && B.layer) {
		      var d = B.sd, n = Math.min(pose.views.length, 2), hp = pose.transform.position, hq = pose.transform.orientation;
		      d[1] = hp.x; d[2] = hp.y; d[3] = hp.z; d[4] = hq.x; d[5] = hq.y; d[6] = hq.z; d[7] = hq.w;
		      for (var i = 0; i < n; ++i) {
		        var v = pose.views[i], o = 8 + i * 27, p = v.transform.position, q = v.transform.orientation, vp = B.layer.getViewport(v);
		        d.set(v.projectionMatrix, o);
		        d[o + 16] = p.x - hp.x; d[o + 17] = p.y - hp.y; d[o + 18] = p.z - hp.z;
		        d[o + 19] = q.x; d[o + 20] = q.y; d[o + 21] = q.z; d[o + 22] = q.w;
		        if (vp) { d[o + 23] = vp.x; d[o + 24] = vp.y; d[o + 25] = vp.width; d[o + 26] = vp.height; }
		      }
		      d[0] = n;
		    }
		  } catch (e) { console.error('eaglerxr: pose capture failed', e); }
		  try { S.captureInputs(frame); } catch (e) { B.cd.fill(0); console.error('eaglerxr: controller capture failed', e); }
		  S.flushVsync();
		  S.present();
		};
		S.cleanup = function (reason) {
		  var wasStereo = B.stereo || B.pending;
		  B.stereo = false; B.pending = false;
		  if (B.session && B.session.__eaglerStereo) { B.session = null; B.ref = null; B.active = false; }
		  B.layer = null; B.sd[0] = 0; B.cd.fill(0);
		  if (B.gl) { try { S.freeAtlas(B.gl); } catch (e) { } }
		  S.flushVsync();
		  if (wasStereo) B.msg = reason || 'VR session ended.';
		  S.updateButton();
		};
		S.enter = function () {
		  if (B.active || B.pending) { B.msg = B.active ? 'A VR session is already running.' : 'A VR session is already starting.'; return false; }
		  if (!S.supported()) { B.msg = 'Immersive VR needs a WebXR browser and the WebGL 2 renderer.'; return false; }
		  var g = B.gl, sess = null, ended = false;
		  B.pending = true; B.msg = 'Starting VR...'; S.updateButton();
		  var req;
		  try { req = navigator.xr.requestSession('immersive-vr', { optionalFeatures: ['local-floor'] }); }
		  catch (e) { B.pending = false; B.msg = 'Could not start VR: ' + (e && e.message || e); S.updateButton(); return false; }
		  req.then(function (s) {
		    sess = s; s.__eaglerStereo = true;
		    S.hookInputEvents(s);
		    s.addEventListener('end', function () { ended = true; if (B.session === s || B.pending) S.cleanup('VR session ended.'); });
		    return g.makeXRCompatible ? g.makeXRCompatible() : null;
		  }).then(function () {
		    if (ended) throw new Error('session ended during setup');
		    var layer = new XRWebGLLayer(sess, g, { antialias: false, depth: false, stencil: false, alpha: false });
		    return sess.updateRenderState({ baseLayer: layer }).then(function () { return layer; });
		  }).then(function (layer) {
		    B.layer = layer;
		    return sess.requestReferenceSpace('local-floor').catch(function () { return sess.requestReferenceSpace('local'); });
		  }).then(function (ref) {
		    if (ended) throw new Error('session ended during setup');
		    B.session = sess; B.ref = ref; B.frame = 0; B.controllers = []; B.sd[0] = 0;
		    B.active = true; B.stereo = true; B.pending = false; B.msg = null;
		    sess.requestAnimationFrame(S.onFrame);
		    S.updateButton();
		  }).catch(function (e) {
		    B.pending = false; B.layer = null;
		    B.msg = 'Could not start VR: ' + (e && e.message || e);
		    if (sess && !ended) { try { sess.end(); } catch (x) { } }
		    S.updateButton();
		  });
		  return true;
		};
		S.exit = function () { if (B.stereo && B.session) { try { B.session.end(); } catch (e) { } } };
		S.requestFrame = function (cb) { if (B.stereo && B.session) { B.vsync.push(cb); return true; } return false; };
		S.setWorld = function (on) { B.world = !!on; if (!on) B.atlasReady = false; S.updateButton(); };
		S.updateButton = function () {
		  var show = B.xrOk && B.world && !B.active && !B.pending && !!B.gl;
		  if (!B.btn) {
		    if (!show || !globalThis.document || !document.body) return;
		    var b = B.btn = document.createElement('button');
		    b.type = 'button'; b.id = 'eaglerxr-enter-vr'; b.textContent = 'Enter VR';
		    b.style.cssText = 'position:fixed;right:18px;bottom:18px;z-index:2147483647;padding:10px 16px;font:600 14px sans-serif;border:1px solid #777;border-radius:5px;background:#202124;color:#fff;cursor:pointer;opacity:.92';
		    b.addEventListener('click', function (ev) { ev.stopPropagation(); ev.preventDefault(); S.enter(); });
		    b.addEventListener('mousedown', function (ev) { ev.stopPropagation(); });
		    document.body.appendChild(b);
		  }
		  B.btn.style.display = show ? 'block' : 'none';
		};
		globalThis.eaglerXREnterVR = S.enter;
		globalThis.eaglerXRExitVR = S.exit;
		if (globalThis.navigator && navigator.xr && typeof navigator.xr.isSessionSupported === 'function') {
		  navigator.xr.isSessionSupported('immersive-vr').then(function (ok) { B.xrOk = !!ok; S.updateButton(); }, function () { B.xrOk = false; });
		  if (navigator.xr.addEventListener) navigator.xr.addEventListener('devicechange', function () {
		    navigator.xr.isSessionSupported('immersive-vr').then(function (ok) { B.xrOk = !!ok; S.updateButton(); }, function () { });
		  });
		}
		""")
	public static native void installStereo(JSObject gl);

	@JSBody(script = "var B=globalThis.__eaglerXR;return !!(B&&B.stereoApi&&B.stereoApi.supported());")
	public static native boolean isStereoSupported();

	@JSBody(script = "var B=globalThis.__eaglerXR;return !!(B&&B.stereo&&B.layer);")
	public static native boolean isStereoPresenting();

	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.stereoApi?B.stereoApi.enter():false;")
	public static native boolean enterStereoSession();

	@JSBody(script = "var B=globalThis.__eaglerXR;if(B&&B.stereoApi)B.stereoApi.exit();")
	public static native void exitStereoSession();

	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.msg?String(B.msg):null;")
	public static native String stereoStatusMessage();

	@JSBody(params = { "on" }, script = "var B=globalThis.__eaglerXR;if(B&&B.stereoApi)B.stereoApi.setWorld(on);")
	public static native void setStereoWorldActive(boolean on);

	/** Latest controller snapshot (2 slots x 16 floats, see PlatformWebXR), or null. */
	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.stereo&&B.cd?B.cd:null;")
	public static native Float32Array stereoControllerData();

	/** Latest per-frame stereo data (see PlatformWebXR for the layout), or null. */
	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.stereo&&B.sd?B.sd:null;")
	public static native Float32Array stereoFrameData();

	@JSBody(params = { "view", "tex", "width", "height" }, script = "var B=globalThis.__eaglerXR;if(B&&B.stereoApi)B.stereoApi.copyEye(view,tex,width,height);")
	public static native void copyEye(int view, JSObject tex, int width, int height);

	/** Queues {@code cb} for the next XR animation frame; returns false when no stereo session is presenting. */
	@JSBody(params = { "cb" }, script = "var B=globalThis.__eaglerXR;return !!(B&&B.stereoApi&&B.stereoApi.requestFrame(cb));")
	public static native boolean requestStereoFrame(FrameCallback cb);
}
