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
		// Grip poses (JS only, used to draw the hands): 2 slots x 8 = [valid, x, y, z, qx, qy, qz, qw].
		B.gp = new Float32Array(16);
		// VR GUI panel state. gd = [panel mode, -, hit0, u0, v0, hit1, u1, v1] (v from the top), see PlatformWebXR.
		B.gd = new Float32Array(8);
		B.gui = { mode: 0, laser: 0, aim: 1, tex: null, fbo: null, w: 0, h: 0, ready: false, lastMode: 0,
		  screenPanel: null, hudPanel: null, hudYaw: null, cur: [null, null], lasers: [] };
		B.views = null;
		var S = B.stereoApi = {};
		var CAPS = null;
		// Load-stage breadcrumb + on-screen error overlay (index.html early diagnostics), if present.
		S.stage = function (n) { try { if (globalThis.__eaglerXRStage) globalThis.__eaglerXRStage(n); } catch (e) { } };
		S.report = function (where, e) {
		  try { console.error('eaglerxr: ' + where, e); } catch (x) { }
		  try { if (globalThis.__eaglerXRReportError) globalThis.__eaglerXRReportError('webxr:' + where, e); } catch (x) { }
		};
		// VR options (globalThis.eaglerXRConfig, else localStorage 'eaglerxr.<key>'), cached until the
		// VR Settings screen writes one (PlatformWebXR.setConfig clears B.cfgCache).
		S.cfg = function (key, def) {
		  var C = B.cfgCache || (B.cfgCache = {});
		  if (Object.prototype.hasOwnProperty.call(C, key)) return C[key];
		  var v = def, c = globalThis.eaglerXRConfig;
		  try {
		    if (c && typeof c[key] === 'number' && isFinite(c[key])) v = c[key];
		    else if (c && typeof c[key] === 'boolean') v = c[key] ? 1 : 0;
		    else {
		      var x = globalThis.localStorage && localStorage.getItem('eaglerxr.' + key);
		      if (x === 'true') v = 1; else if (x === 'false') v = 0;
		      else if (x !== null && x !== undefined && x !== '' && isFinite(+x)) v = +x;
		    }
		  } catch (e) { }
		  C[key] = v;
		  return v;
		};
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
		    S.drawOverlays(g);
		  } finally { S.restore(g, st); }
		};
		// ---- VR GUI / HUD panel and laser pointers (composited per eye at XR frame rate) ----
		S.qrot = function (q, x, y, z) {
		  var qx = q.x, qy = q.y, qz = q.z, qw = q.w;
		  var tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
		  return [x + qw * tx + (qy * tz - qz * ty), y + qw * ty + (qz * tx - qx * tz), z + qw * tz + (qx * ty - qy * tx)];
		};
		S.qmul = function (a, b) {
		  return { x: a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y, y: a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
		    z: a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w, w: a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z };
		};
		// Column-major rigid transform from a quaternion and a position.
		S.qmat = function (q, x, y, z) {
		  var a = S.qrot(q, 1, 0, 0), b = S.qrot(q, 0, 1, 0), d = S.qrot(q, 0, 0, 1);
		  return new Float32Array([a[0], a[1], a[2], 0, b[0], b[1], b[2], 0, d[0], d[1], d[2], 0, x, y, z, 1]);
		};
		S.wrap = function (a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; };
		S.mul = function (a, b) {
		  var o = new Float32Array(16);
		  for (var c = 0; c < 4; ++c) for (var r = 0; r < 4; ++r) {
		    o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
		  }
		  return o;
		};
		// A panel is a w x h metre quad centred at (x,y,z), turned by yaw about +Y; its front (+Z) faces the user.
		S.place = function (hp, yaw, dist, dy, w, aspect) {
		  return { x: hp.x - Math.sin(yaw) * dist, y: hp.y + dy, z: hp.z - Math.cos(yaw) * dist, yaw: yaw, w: w, h: w * aspect };
		};
		S.model = function (p) {
		  if (p.m) return p.m;
		  var cs = Math.cos(p.yaw), sn = Math.sin(p.yaw);
		  return new Float32Array([cs, 0, -sn, 0, 0, 1, 0, 0, sn, 0, cs, 0, p.x, p.y, p.z, 1]);
		};
		S.hit = function (p, o, d) {
		  var cs = Math.cos(p.yaw), sn = Math.sin(p.yaw);
		  var den = d[0] * sn + d[2] * cs;
		  if (den > -1e-4) return null;
		  var t = ((p.x - o[0]) * sn + (p.z - o[2]) * cs) / den;
		  if (!(t > 0) || t > 20) return null;
		  var hx = o[0] + d[0] * t - p.x, hy = o[1] + d[1] * t - p.y, hz = o[2] + d[2] * t - p.z;
		  var u = (hx * cs - hz * sn) / p.w + 0.5, v = hy / p.h + 0.5;
		  if (u < 0 || u > 1 || v < 0 || v > 1) return null;
		  return { u: u, v: v, t: t };
		};
		S.updatePanels = function (pose) {
		  var G = B.gui, gd = B.gd, c = B.cd;
		  gd.fill(0); G.lasers = []; G.cur = [null, null];
		  if (!pose) return;
		  var hp = pose.transform.position, f = S.qrot(pose.transform.orientation, 0, 0, -1);
		  var yaw = Math.atan2(-f[0], -f[2]);
		  var aspect = G.w > 0 && G.h > 0 ? G.h / G.w : 0.5625;
		  if (G.mode === 2 && G.ready) {
		    // Menus: placed once in front of the head when a screen opens, then fixed in the room.
		    if (!G.screenPanel || G.lastMode !== 2) G.screenPanel = S.place(hp, yaw, 1.35, -0.08, 1.6, aspect);
		    G.screenPanel.h = G.screenPanel.w * aspect;
		  } else { G.screenPanel = null; }
		  if (G.mode === 1 && G.ready) {
		    // HUD placement (VR Settings "HUD"; like Vivecraft's HUD lock): 0 face (default), 1 hand, 2 floating.
		    var hm = S.cfg('hudMode', 0) | 0, offSlot = S.cfg('leftHanded', 0) ? 1 : 0, oo = offSlot * 16;
		    if (hm === 1 && c[oo]) {
		      // Hand HUD: lies on the back of the off-hand (above the controller, towards the wrist),
		      // tilted 30 degrees towards the user; the top edge points along the controller.
		      var cq = { x: c[oo + 5], y: c[oo + 6], z: c[oo + 7], w: c[oo + 8] };
		      var pq = S.qmul(cq, { x: Math.sin(-Math.PI / 6), y: 0, z: 0, w: Math.cos(-Math.PI / 6) });
		      var hw = 0.3, off = S.qrot(cq, 0, 0.07, 0.1);
		      G.hudPanel = { m: S.qmat(pq, c[oo + 2] + off[0], c[oo + 3] + off[1], c[oo + 4] + off[2]), w: hw, h: hw * aspect, back: 0.3 };
		      G.hudYaw = null;
		    } else if (hm === 2) {
		      // Floating HUD: lower in view, follows the head's yaw lazily (35 degree dead zone), never its pitch.
		      if (G.hudYaw === null || G.lastMode !== 1) G.hudYaw = yaw;
		      var dy = S.wrap(yaw - G.hudYaw), lim = 0.6;
		      if (dy > lim) G.hudYaw += dy - lim; else if (dy < -lim) G.hudYaw += dy + lim;
		      G.hudPanel = S.place(hp, G.hudYaw, 1.3, -0.22, 1.5, aspect);
		    } else {
		      // Face HUD: head-locked, 1.1 m ahead and slightly low, about 46 degrees wide.
		      var fq = pose.transform.orientation, fo = S.qrot(fq, 0, -0.06, -1.1), fw = 0.95;
		      G.hudPanel = { m: S.qmat(fq, hp.x + fo[0], hp.y + fo[1], hp.z + fo[2]), w: fw, h: fw * aspect };
		      G.hudYaw = null;
		    }
		  } else { G.hudPanel = null; G.hudYaw = null; }
		  G.lastMode = G.ready ? G.mode : 0;
		  gd[0] = G.screenPanel ? 2 : (G.hudPanel ? 1 : 0);
		  for (var s = 0; s < 2; ++s) {
		    var o = s * 16;
		    if (!c[o]) continue;
		    var org = [c[o + 2], c[o + 3], c[o + 4]];
		    var d = S.qrot({ x: c[o + 5], y: c[o + 6], z: c[o + 7], w: c[o + 8] }, 0, 0, -1);
		    var len = 0, alpha = 0;
		    if (G.screenPanel) {
		      var h = S.hit(G.screenPanel, org, d);
		      if (h) { gd[2 + s * 3] = 1; gd[3 + s * 3] = h.u; gd[4 + s * 3] = 1 - h.v; G.cur[s] = h; len = h.t; alpha = 0.85; }
		      else { len = 0.6; alpha = 0.3; }
		    } else if (G.mode === 1 && s === G.aim && G.laser !== 0) {
		      len = Math.abs(G.laser); alpha = G.laser > 0 ? 0.75 : 0.28;
		    }
		    if (len > 0) G.lasers.push([org[0], org[1], org[2], org[0] + d[0] * len, org[1] + d[1] * len, org[2] + d[2] * len, alpha]);
		  }
		};
		S.ensureOverlay = function (g) {
		  if (B.ovPanel) return true;
		  if (B.ovFailed) return false;
		  function sh(t, src) { var x = g.createShader(t); g.shaderSource(x, src); g.compileShader(x); return x; }
		  function prog(vs, fs) {
		    var p = g.createProgram(); g.attachShader(p, sh(g.VERTEX_SHADER, vs)); g.attachShader(p, sh(g.FRAGMENT_SHADER, fs)); g.linkProgram(p);
		    if (!g.getProgramParameter(p, g.LINK_STATUS)) { console.error('eaglerxr: overlay program failed: ' + g.getProgramInfoLog(p)); return null; }
		    return p;
		  }
		  var H = '#version 300 es\\nprecision highp float;\\n';
		  var pp = prog(H + 'uniform mat4 u_mvp;uniform vec2 u_size;out vec2 v_uv;void main(){int i=gl_VertexID;vec2 p=vec2((i==1||i==4||i==5)?1.0:0.0,(i==2||i==3||i==5)?1.0:0.0);v_uv=p;gl_Position=u_mvp*vec4((p.x-0.5)*u_size.x,(p.y-0.5)*u_size.y,0.0,1.0);}\\n',
		    H + 'uniform sampler2D u_tex;uniform vec2 u_size;uniform float u_back;uniform vec3 u_c0;uniform vec3 u_c1;in vec2 v_uv;out vec4 o;'
		    + 'vec4 cur(vec4 c,vec3 k){if(k.z<0.5)return c;float d=length((v_uv-k.xy)*u_size);if(d<0.011)return d<0.007?vec4(1.0):vec4(0.0,0.0,0.0,1.0);return c;}'
		    + 'void main(){vec4 c=texture(u_tex,v_uv);c+=vec4(0.0,0.0,0.0,u_back)*(1.0-c.a);c=cur(c,u_c0);c=cur(c,u_c1);o=c;}\\n');
		  var lp = prog(H + 'uniform mat4 u_vp;uniform vec3 u_a;uniform vec3 u_b;void main(){gl_Position=u_vp*vec4(gl_VertexID==0?u_a:u_b,1.0);}\\n',
		    H + 'uniform vec4 u_col;out vec4 o;void main(){o=u_col;}\\n');
		  // Hand boxes: 36 vertices of a unit cube generated from gl_VertexID; faces turned away from
		  // the eye are collapsed (the XR layer has no depth buffer), so a single box needs no sorting.
		  var hpv = prog(H + 'uniform mat4 u_vp;uniform mat4 u_m;uniform vec3 u_c;uniform vec3 u_s;uniform vec3 u_eye;out float v_sh;'
		    + 'void main(){int f=gl_VertexID/6;int k=gl_VertexID-f*6;int q=k<3?k:(k==3?0:(k==4?2:3));'
		    + 'vec2 uv=vec2((q==1||q==2)?1.0:-1.0,(q>=2)?1.0:-1.0);int ax=f/2;float sg=(f-ax*2)==0?1.0:-1.0;'
		    + 'vec3 n=ax==0?vec3(sg,0.0,0.0):(ax==1?vec3(0.0,sg,0.0):vec3(0.0,0.0,sg));'
		    + 'vec3 p=ax==0?vec3(sg,uv.x,uv.y):(ax==1?vec3(uv.x,sg,uv.y):vec3(uv.x,uv.y,sg));'
		    + 'vec4 w=u_m*vec4(u_c+0.5*p*u_s,1.0);vec3 wn=normalize(mat3(u_m)*n);'
		    + 'v_sh=0.55+0.45*max(dot(wn,normalize(vec3(0.3,1.0,0.5))),0.0);'
		    + 'gl_Position=dot(wn,u_eye-w.xyz)>0.0?u_vp*w:vec4(2.0,2.0,2.0,1.0);}\\n',
		    H + 'uniform vec3 u_col;in float v_sh;out vec4 o;void main(){o=vec4(u_col*v_sh,1.0);}\\n');
		  if (!pp || !lp || !hpv) { B.ovFailed = true; return false; }
		  B.ovPanel = pp; B.ovLine = lp; B.ovHand = hpv; B.ovVao = g.createVertexArray();
		  B.ovH = {
		    vp: g.getUniformLocation(hpv, 'u_vp'), m: g.getUniformLocation(hpv, 'u_m'), c: g.getUniformLocation(hpv, 'u_c'),
		    s: g.getUniformLocation(hpv, 'u_s'), eye: g.getUniformLocation(hpv, 'u_eye'), col: g.getUniformLocation(hpv, 'u_col')
		  };
		  B.ovU = {
		    mvp: g.getUniformLocation(pp, 'u_mvp'), size: g.getUniformLocation(pp, 'u_size'), tex: g.getUniformLocation(pp, 'u_tex'),
		    back: g.getUniformLocation(pp, 'u_back'), c0: g.getUniformLocation(pp, 'u_c0'), c1: g.getUniformLocation(pp, 'u_c1'),
		    vp: g.getUniformLocation(lp, 'u_vp'), a: g.getUniformLocation(lp, 'u_a'), b: g.getUniformLocation(lp, 'u_b'), col: g.getUniformLocation(lp, 'u_col')
		  };
		  return true;
		};
		S.drawOverlays = function (g) {
		  var G = B.gui, views = B.views, layer = B.layer;
		  var panel = G.ready && G.tex ? (G.screenPanel || G.hudPanel) : null;
		  var hands = S.handModels();
		  if (!views || !layer || (!panel && !G.lasers.length && !hands.length)) return;
		  if (!S.ensureProgram(g) || !S.ensureOverlay(g)) return;
		  var bl = [g.getParameter(g.BLEND_SRC_RGB), g.getParameter(g.BLEND_DST_RGB), g.getParameter(g.BLEND_SRC_ALPHA), g.getParameter(g.BLEND_DST_ALPHA),
		    g.getParameter(g.BLEND_EQUATION_RGB), g.getParameter(g.BLEND_EQUATION_ALPHA)];
		  try {
		    g.enable(g.BLEND); g.blendEquation(g.FUNC_ADD);
		    g.blendFuncSeparate(g.ONE, g.ONE_MINUS_SRC_ALPHA, g.ONE, g.ONE_MINUS_SRC_ALPHA);
		    g.bindVertexArray(B.ovVao);
		    for (var i = 0; i < views.length && i < 2; ++i) {
		      var v = views[i], vp = layer.getViewport(v);
		      if (!vp) continue;
		      g.viewport(vp.x, vp.y, vp.width, vp.height);
		      var pv = S.mul(v.projectionMatrix, v.transform.inverse.matrix);
		      if (hands.length) {
		        g.disable(g.BLEND);
		        g.useProgram(B.ovHand);
		        g.uniformMatrix4fv(B.ovH.vp, false, pv);
		        var ep = v.transform.position;
		        g.uniform3f(B.ovH.eye, ep.x, ep.y, ep.z);
		        for (var hi = 0; hi < hands.length; ++hi) {
		          var hb = hands[hi];
		          g.uniformMatrix4fv(B.ovH.m, false, hb.m);
		          g.uniform3f(B.ovH.c, hb.c[0], hb.c[1], hb.c[2]); g.uniform3f(B.ovH.s, hb.s[0], hb.s[1], hb.s[2]);
		          g.uniform3f(B.ovH.col, hb.col[0], hb.col[1], hb.col[2]);
		          g.drawArrays(g.TRIANGLES, 0, 36);
		        }
		        g.enable(g.BLEND);
		      }
		      if (panel) {
		        g.useProgram(B.ovPanel);
		        g.uniformMatrix4fv(B.ovU.mvp, false, S.mul(pv, S.model(panel)));
		        g.uniform2f(B.ovU.size, panel.w, panel.h);
		        g.uniform1f(B.ovU.back, panel === G.screenPanel ? 0.45 : (panel.back || 0.0));
		        var c0 = G.cur[0], c1 = G.cur[1];
		        g.uniform3f(B.ovU.c0, c0 ? c0.u : 0, c0 ? c0.v : 0, c0 ? 1 : 0);
		        g.uniform3f(B.ovU.c1, c1 ? c1.u : 0, c1 ? c1.v : 0, c1 ? 1 : 0);
		        g.activeTexture(g.TEXTURE0); g.bindTexture(g.TEXTURE_2D, G.tex); g.bindSampler(0, B.sampler);
		        g.uniform1i(B.ovU.tex, 0);
		        g.drawArrays(g.TRIANGLES, 0, 6);
		      }
		      if (G.lasers.length) {
		        g.useProgram(B.ovLine);
		        g.uniformMatrix4fv(B.ovU.vp, false, pv);
		        for (var k = 0; k < G.lasers.length; ++k) {
		          var L = G.lasers[k];
		          g.uniform3f(B.ovU.a, L[0], L[1], L[2]); g.uniform3f(B.ovU.b, L[3], L[4], L[5]);
		          g.uniform4f(B.ovU.col, L[6], L[6], L[6], L[6]);
		          g.drawArrays(g.LINES, 0, 2);
		        }
		      }
		    }
		  } finally {
		    g.blendFuncSeparate(bl[0], bl[1], bl[2], bl[3]); g.blendEquationSeparate(bl[4], bl[5]);
		  }
		};
		// Simple Minecraft-style arms at each controller's grip pose (target-ray pose as a fallback):
		// a skin-coloured hand cube and a sleeve towards the elbow (grip +Z). VR Settings "Show hands"
		// (showHands: 0 off, 1 both hands, 2 main hand only).
		S.handModels = function () {
		  var out = [], mode = S.cfg('showHands', 1) | 0;
		  if (!mode || !B.stereo) return out;
		  var main = S.cfg('leftHanded', 0) ? 0 : 1, gp = B.gp, c = B.cd;
		  for (var s = 0; s < 2; ++s) {
		    if (mode === 2 && s !== main) continue;
		    var o = s * 8, q, x, y, z;
		    if (gp[o]) { q = { x: gp[o + 4], y: gp[o + 5], z: gp[o + 6], w: gp[o + 7] }; x = gp[o + 1]; y = gp[o + 2]; z = gp[o + 3]; }
		    else if (c[s * 16]) { var co = s * 16; q = { x: c[co + 5], y: c[co + 6], z: c[co + 7], w: c[co + 8] }; x = c[co + 2]; y = c[co + 3]; z = c[co + 4]; }
		    else continue;
		    var m = S.qmat(q, x, y, z);
		    out.push({ m: m, c: [0, 0, 0.015], s: [0.07, 0.07, 0.085], col: [0.84, 0.64, 0.52] });
		    out.push({ m: m, c: [0, 0, 0.13], s: [0.075, 0.075, 0.15], col: [0.0, 0.62, 0.64] });
		  }
		  return out;
		};
		// Copies the game's GUI image (drawn over a transparent clear, premultiplied alpha) for the panel.
		S.copyGui = function (tex, w, h, mode, laser, aim) {
		  var g = B.gl, G = B.gui;
		  G.mode = mode | 0; G.laser = +laser || 0; G.aim = aim | 0;
		  if (!B.stereo || !g || !tex || w <= 0 || h <= 0 || !G.mode) { G.ready = false; return; }
		  var st = S.save(g);
		  try {
		    if (!G.tex || G.w !== w || G.h !== h) {
		      S.freeGui(g);
		      G.tex = g.createTexture(); g.bindTexture(g.TEXTURE_2D, G.tex);
		      g.texStorage2D(g.TEXTURE_2D, 1, g.RGBA8, w, h);
		      G.fbo = g.createFramebuffer(); G.w = w; G.h = h;
		      B.readFbo = B.readFbo || g.createFramebuffer();
		    }
		    S.plain(g);
		    g.bindFramebuffer(g.READ_FRAMEBUFFER, B.readFbo);
		    g.framebufferTexture2D(g.READ_FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, tex, 0);
		    g.bindFramebuffer(g.DRAW_FRAMEBUFFER, G.fbo);
		    g.framebufferTexture2D(g.DRAW_FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, G.tex, 0);
		    g.blitFramebuffer(0, 0, w, h, 0, 0, w, h, g.COLOR_BUFFER_BIT, g.NEAREST);
		    g.framebufferTexture2D(g.READ_FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, null, 0);
		    G.ready = true;
		  } finally { S.restore(g, st); }
		};
		S.freeGui = function (g) {
		  var G = B.gui;
		  if (G.fbo) g.deleteFramebuffer(G.fbo);
		  if (G.tex) g.deleteTexture(G.tex);
		  G.fbo = null; G.tex = null; G.w = 0; G.h = 0; G.ready = false;
		};
		// Controllers: target-ray pose (the pointing ray, not the grip) plus trigger /
		// squeeze / thumbstick / A,B for each tracked-pointer input source, read in
		// the same XR frame as the viewer pose. select/squeeze events are OR-ed in so
		// sources without a gamepad (e.g. tracked hands pinching) can still act.
		S.captureInputs = function (frame) {
		  var c = B.cd; c.fill(0); B.gp.fill(0);
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
		    c[o + 13] = bv(4); c[o + 14] = bv(5); c[o + 15] = bv(3);
		    if (s.gripSpace) {
		      var gpo = frame.getPose(s.gripSpace, B.ref);
		      if (gpo) {
		        var go = slot * 8, gpp = gpo.transform.position, gq = gpo.transform.orientation;
		        B.gp[go] = 1; B.gp[go + 1] = gpp.x; B.gp[go + 2] = gpp.y; B.gp[go + 3] = gpp.z;
		        B.gp[go + 4] = gq.x; B.gp[go + 5] = gq.y; B.gp[go + 6] = gq.z; B.gp[go + 7] = gq.w;
		      }
		    }
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
		  var pose = null;
		  try {
		    pose = frame.getViewerPose(B.ref);
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
		    B.views = pose && B.layer ? pose.views : null;
		  } catch (e) { B.views = null; console.error('eaglerxr: pose capture failed', e); }
		  try { S.captureInputs(frame); } catch (e) { B.cd.fill(0); console.error('eaglerxr: controller capture failed', e); }
		  try { S.updatePanels(B.views ? pose : null); } catch (e) { B.gd.fill(0); console.error('eaglerxr: panel update failed', e); }
		  S.flushVsync();
		  S.present();
		};
		S.cleanup = function (reason) {
		  var wasStereo = B.stereo || B.pending;
		  B.stereo = false; B.pending = false;
		  if (B.session && B.session.__eaglerStereo) { B.session = null; B.ref = null; B.active = false; }
		  B.layer = null; B.sd[0] = 0; B.cd.fill(0); B.gp.fill(0);
		  if (B.gl) { try { S.freeAtlas(B.gl); S.freeGui(B.gl); } catch (e) { } }
		  B.views = null; B.gd.fill(0); B.gui.mode = 0; B.gui.lastMode = 0; B.gui.screenPanel = null; B.gui.hudPanel = null; B.gui.hudYaw = null; B.gui.lasers = []; B.gui.cur = [null, null];
		  S.flushVsync();
		  if (wasStereo) B.msg = reason || 'VR session ended.';
		  S.updateButton();
		};
		S.enter = function () {
		  if (B.active || B.pending) { B.msg = B.active ? 'A VR session is already running.' : 'A VR session is already starting.'; return false; }
		  if (!S.supported()) { B.msg = 'Immersive VR needs a WebXR browser and the WebGL 2 renderer.'; return false; }
		  var g = B.gl, sess = null, ended = false;
		  B.pending = true; B.msg = 'Starting VR...'; S.updateButton();
		  S.stage('xr-request-session');
		  var req;
		  try { req = navigator.xr.requestSession('immersive-vr', { optionalFeatures: ['local-floor'] }); }
		  catch (e) { B.pending = false; B.msg = 'Could not start VR: ' + (e && e.message || e); S.updateButton(); return false; }
		  req.then(function (s) {
		    sess = s; s.__eaglerStereo = true;
		    S.hookInputEvents(s);
		    s.addEventListener('end', function () { ended = true; S.stage('xr-session-ended'); if (B.session === s || B.pending) S.cleanup('VR session ended.'); });
		    S.stage('xr-make-xr-compatible');
		    return g.makeXRCompatible ? g.makeXRCompatible() : null;
		  }).then(function () {
		    if (ended) throw new Error('session ended during setup');
		    S.stage('xr-create-layer');
		    var layer = new XRWebGLLayer(sess, g, { antialias: false, depth: false, stencil: false, alpha: false });
		    sess.updateRenderState({ baseLayer: layer }); /* returns undefined per WebXR spec, not a Promise */
		    return layer;
		  }).then(function (layer) {
		    B.layer = layer;
		    return sess.requestReferenceSpace('local-floor').catch(function () { return sess.requestReferenceSpace('local'); });
		  }).then(function (ref) {
		    if (ended) throw new Error('session ended during setup');
		    B.session = sess; B.ref = ref; B.frame = 0; B.controllers = []; B.sd[0] = 0;
		    B.active = true; B.stereo = true; B.pending = false; B.msg = null;
		    sess.requestAnimationFrame(S.onFrame);
		    S.stage('xr-presenting');
		    S.updateButton();
		  }).catch(function (e) {
		    S.stage('xr-start-failed');
		    S.report('enter VR failed', e);
		    B.pending = false; B.layer = null;
		    B.msg = 'Could not start VR: ' + (e && e.message || e);
		    if (sess && !ended) { try { sess.end(); } catch (x) { } }
		    S.updateButton();
		  });
		  return true;
		};
		S.exit = function () { if (B.stereo && B.session) { try { B.session.end(); } catch (e) { } } };
		S.requestFrame = function (cb) { if (B.stereo && B.session) { B.vsync.push(cb); return true; } return false; };
		S.setWorld = function (on) {
		  B.world = !!on; if (!on) B.atlasReady = false;
		  if (on && !B.probed && !B.probeTimer) B.probeTimer = setTimeout(function () { B.probeTimer = 0; if (B.world) S.probe(); }, 4000);
		  S.updateButton();
		};
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
		// Asking the browser whether immersive VR is supported is deferred until the player
		// is in a world (never during loading); it only decides whether to show the
		// floating "Enter VR" button. Everything else (makeXRCompatible, layers,
		// framebuffers, sessions) waits for the Enter VR press.
		S.probe = function () {
		  if (B.probed) return;
		  B.probed = true;
		  try {
		    if (!(globalThis.navigator && navigator.xr && typeof navigator.xr.isSessionSupported === 'function')) return;
		    navigator.xr.isSessionSupported('immersive-vr').then(function (ok) { B.xrOk = !!ok; B.xrKnown = true; S.updateButton(); }, function () { B.xrOk = false; B.xrKnown = true; });
		    if (navigator.xr.addEventListener) navigator.xr.addEventListener('devicechange', function () {
		      try { navigator.xr.isSessionSupported('immersive-vr').then(function (ok) { B.xrOk = !!ok; S.updateButton(); }, function () { }); } catch (e) { }
		    });
		  } catch (e) { B.xrOk = false; S.report('isSessionSupported failed', e); }
		};
		globalThis.eaglerXREnterVR = function () { try { return S.enter(); } catch (e) { B.pending = false; S.report('enter VR threw', e); return false; } };
		globalThis.eaglerXRExitVR = S.exit;
		S.stage('xr-api-installed');
		""")
	public static native void installStereo(JSObject gl);

	@JSBody(script = "var B=globalThis.__eaglerXR;try{return !!(B&&B.stereoApi&&B.stereoApi.supported());}catch(e){return false;}")
	public static native boolean isStereoSupported();

	@JSBody(script = "var B=globalThis.__eaglerXR;return !!(B&&B.stereo&&B.layer);")
	public static native boolean isStereoPresenting();

	@JSBody(script = "var B=globalThis.__eaglerXR;try{return B&&B.stereoApi?!!B.stereoApi.enter():false;}catch(e){if(B){B.pending=false;B.msg='Could not start VR: '+(e&&e.message||e);if(B.stereoApi)B.stereoApi.report('enter VR threw',e);}return false;}")
	public static native boolean enterStereoSession();

	@JSBody(script = "var B=globalThis.__eaglerXR;try{if(B&&B.stereoApi)B.stereoApi.exit();}catch(e){}")
	public static native void exitStereoSession();

	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.msg?String(B.msg):null;")
	public static native String stereoStatusMessage();

	@JSBody(params = { "on" }, script = "var B=globalThis.__eaglerXR;try{if(B&&B.stereoApi)B.stereoApi.setWorld(on);}catch(e){if(B&&B.stereoApi)B.stereoApi.report('setWorld failed',e);}")
	public static native void setStereoWorldActive(boolean on);

	/** Latest controller snapshot (2 slots x 16 floats, see PlatformWebXR), or null. */
	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.stereo&&B.cd?B.cd:null;")
	public static native Float32Array stereoControllerData();

	/** Latest per-frame stereo data (see PlatformWebXR for the layout), or null. */
	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.stereo&&B.sd?B.sd:null;")
	public static native Float32Array stereoFrameData();

	@JSBody(params = { "view", "tex", "width", "height" }, script = "var B=globalThis.__eaglerXR;try{if(B&&B.stereoApi)B.stereoApi.copyEye(view,tex,width,height);}catch(e){if(!B.copyEyeErr){B.copyEyeErr=1;B.stereoApi.report('copyEye failed',e);}}")
	public static native void copyEye(int view, JSObject tex, int width, int height);

	/** Latest VR GUI panel / pointer data (8 floats, see PlatformWebXR), or null. */
	@JSBody(script = "var B=globalThis.__eaglerXR;return B&&B.stereo&&B.gd?B.gd:null;")
	public static native Float32Array stereoGuiData();

	@JSBody(params = { "tex", "width", "height", "mode", "laser", "aim" }, script = "var B=globalThis.__eaglerXR;try{if(B&&B.stereoApi)B.stereoApi.copyGui(tex,width,height,mode,laser,aim);}catch(e){if(!B.copyGuiErr){B.copyGuiErr=1;B.stereoApi.report('copyGui failed',e);}}")
	public static native void copyGui(JSObject tex, int width, int height, int mode, float laser, int aim);

	/**
	 * Numeric VR option: {@code globalThis.eaglerXRConfig[key]} (number or boolean)
	 * if set, else {@code localStorage['eaglerxr.' + key]}, else {@code def}.
	 */
	@JSBody(params = { "key", "def" }, script = "var c=globalThis.eaglerXRConfig;if(c&&typeof c[key]==='number'&&isFinite(c[key]))return c[key];"
		+ "if(c&&typeof c[key]==='boolean')return c[key]?1:0;"
		+ "try{var s=globalThis.localStorage&&localStorage.getItem('eaglerxr.'+key);if(s==='true')return 1;if(s==='false')return 0;if(s!==null&&s!==undefined&&s!==''&&isFinite(+s))return +s;}catch(e){}"
		+ "return def;")
	public static native double configValue(String key, double def);

	/**
	 * Persists a VR option to {@code localStorage['eaglerxr.' + key]} and drops the JS-side option
	 * cache so the web layer (HUD placement, hands) picks it up on the next XR frame. A value set in
	 * {@code globalThis.eaglerXRConfig} still wins, as with {@link #configValue}.
	 */
	@JSBody(params = { "key", "value" }, script = "try{if(globalThis.localStorage)localStorage.setItem('eaglerxr.'+key,String(value));}catch(e){}"
		+ "var B=globalThis.__eaglerXR;if(B)B.cfgCache=null;")
	public static native void setConfigValue(String key, double value);

	/**
	 * Immersive-VR support for the title-screen button: starts the (deferred) isSessionSupported
	 * probe if it has not run yet; returns -1 while unknown, 0 unsupported, 1 supported.
	 */
	@JSBody(script = "var B=globalThis.__eaglerXR;if(!B||!B.stereoApi)return -1;try{if(!B.probed)B.stereoApi.probe();}catch(e){return 0;}"
		+ "return B.xrKnown?(B.xrOk?1:0):(B.probed&&!(globalThis.navigator&&navigator.xr)?0:-1);")
	public static native int immersiveSupportState();

	/** Queues {@code cb} for the next XR animation frame; returns false when no stereo session is presenting. */
	@JSBody(params = { "cb" }, script = "var B=globalThis.__eaglerXR;try{return !!(B&&B.stereoApi&&B.stereoApi.requestFrame(cb));}catch(e){return false;}")
	public static native boolean requestStereoFrame(FrameCallback cb);

	/**
	 * User kill switch for all in-tab VR code: {@code ?novr} in the page URL or
	 * {@code localStorage['eaglerxr.disable'] = '1'}. Lets a crash be bisected on the
	 * headset without a rebuild.
	 */
	@JSBody(script = "try{if(/[?&]novr(?:[=&]|$)/.test(String(globalThis.location&&location.search||'')))return true;"
		+ "var v=globalThis.localStorage&&localStorage.getItem('eaglerxr.disable');return v==='1'||v==='true';}catch(e){return false;}")
	public static native boolean isDisabledByUser();

	/** Load-stage breadcrumb for the index.html early diagnostics (no-op without them). */
	@JSBody(params = { "stage" }, script = "try{if(globalThis.__eaglerXRStage)globalThis.__eaglerXRStage(stage);}catch(e){}")
	public static native void markStage(String stage);

	/** Shows a VR-side failure on the index.html error overlay (and the console). */
	@JSBody(params = { "where", "message" }, script = "try{console.error('eaglerxr: '+where+': '+message);"
		+ "if(globalThis.__eaglerXRReportError)globalThis.__eaglerXRReportError('webxr:'+where,message);}catch(e){}")
	public static native void reportError(String where, String message);
}
