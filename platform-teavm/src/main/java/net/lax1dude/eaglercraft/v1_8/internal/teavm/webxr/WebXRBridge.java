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
}
