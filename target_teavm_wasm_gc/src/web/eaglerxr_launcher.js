/* EaglerXR launcher bridge; preserves the normal Eaglercraft startup path. */
(function (global) {
    "use strict";
    var buttonId = "eaglerxr-launch-button";
    function loadRuntime() {
        if (global.eaglerXR) return Promise.resolve(global.eaglerXR);
        return new Promise(function (resolve, reject) {
            var script = document.createElement("script");
            script.src = "eaglerxr_runtime.js?v=1";
            script.onload = function () { resolve(global.eaglerXR); };
            script.onerror = function () { reject(new Error("Unable to load EaglerXR runtime")); };
            document.head.appendChild(script);
        });
    }
    function install() {
        if (document.getElementById(buttonId)) return;
        var button = document.createElement("button");
        button.id = buttonId;
        button.type = "button";
        button.textContent = "Enter WebXR";
        button.disabled = true;
        button.style.cssText = "position:fixed;right:18px;bottom:18px;z-index:2147483647;padding:10px 16px;font:600 14px sans-serif";
        document.body.appendChild(button);
        loadRuntime().then(function (runtime) {
            runtime.setSessionChangeListener(function (event, data) {
                if (event === "supported") button.disabled = !data;
                if (event === "started") button.textContent = "Exit WebXR";
                if (event === "ended") button.textContent = "Enter WebXR";
            });
            button.addEventListener("click", function () {
                var canvas = document.querySelector("canvas");
                var gl = canvas && (canvas.getContext("webgl2") || canvas.getContext("webgl"));
                if (!gl) return;
                runtime.attachGLContext(gl);
                Promise.resolve(runtime.state.active ? runtime.endVRSession() : runtime.requestVRSession()).catch(function (error) {
                    button.title = error.message;
                });
            });
        }).catch(function (error) { button.title = error.message; });
    }
    global.EaglerXRLauncher = { install: install, loadRuntime: loadRuntime };
    // EaglerXR: the in-game "Enter VR" button (WebXRBridge stereo path) replaces this legacy
    // launcher. Its button sat on top of that one and its runtime queried WebXR at page load,
    // so it now installs only on request: ?xrlauncher=1.
    if (/[?&]xrlauncher=1(?:&|$)/.test((global.location && global.location.search) || "")) {
        global.addEventListener("load", install, { once: true });
    }
}(typeof window !== "undefined" ? window : this));
