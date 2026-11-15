/*
 * EaglerXR launcher bridge for the Eaglercraft 26.2 Web target.
 *
 * This file deliberately does not replace Eaglercraft's renderer or startup
 * path. It exposes a user-gesture WebXR entry point and a small integration
 * contract for a renderer/mod that wants to consume EaglerXRRuntime state.
 * When WebXR is absent, the launcher remains completely unchanged.
 */
(function (global) {
    "use strict";

    var BUTTON_ID = "eaglerxr-launch-button";
    var STATUS_ID = "eaglerxr-launch-status";

    function createRuntimeScript() {
        if (global.eaglerXR || document.querySelector("script[data-eaglerxr-runtime]")) {
            return Promise.resolve(global.eaglerXR || null);
        }
        return new Promise(function (resolve, reject) {
            var script = document.createElement("script");
            script.async = false;
            script.dataset.eaglerxrRuntime = "true";
            script.src = "eaglerxr_runtime.js?v=1";
            script.onload = function () { resolve(global.eaglerXR || null); };
            script.onerror = function () { reject(new Error("Unable to load eaglerxr_runtime.js")); };
            document.head.appendChild(script);
        });
    }

    function setStatus(text) {
        var status = document.getElementById(STATUS_ID);
        if (status) status.textContent = text;
    }

    function addButton() {
        if (document.getElementById(BUTTON_ID)) return;
        var button = document.createElement("button");
        button.id = BUTTON_ID;
        button.type = "button";
        button.textContent = "Enter WebXR";
        button.disabled = true;
        button.style.cssText = "position:fixed;right:18px;bottom:18px;z-index:2147483647;padding:10px 16px;font:600 14px sans-serif;border:1px solid #777;border-radius:5px;background:#202124;color:#fff;cursor:pointer;opacity:.92";
        var status = document.createElement("span");
        status.id = STATUS_ID;
        status.style.cssText = "position:fixed;right:18px;bottom:58px;z-index:2147483647;color:#fff;background:rgba(0,0,0,.65);padding:4px 7px;font:12px sans-serif;display:none";
        document.body.appendChild(status);
        document.body.appendChild(button);

        createRuntimeScript().then(function (runtime) {
            if (!runtime) throw new Error("WebXR runtime did not initialize");
            runtime.setSessionChangeListener(function (event, data) {
                if (event === "supported") {
                    button.disabled = !data;
                    setStatus(data ? "WebXR ready" : "WebXR immersive VR unavailable");
                } else if (event === "started") {
                    button.textContent = "Exit WebXR";
                    setStatus("WebXR session active");
                } else if (event === "ended") {
                    button.textContent = "Enter WebXR";
                    setStatus("WebXR session ended");
                } else if (event === "error") {
                    setStatus("WebXR error: " + data);
                }
                status.style.display = "inline-block";
            });
            button.addEventListener("click", function () {
                var canvas = document.querySelector("canvas");
                if (!canvas) {
                    setStatus("Waiting for the Eaglercraft canvas");
                    status.style.display = "inline-block";
                    return;
                }
                var gl = canvas.getContext("webgl2") || canvas.getContext("webgl");
                if (!gl) {
                    setStatus("Eaglercraft WebGL canvas unavailable");
                    status.style.display = "inline-block";
                    return;
                }
                runtime.attachGLContext(gl);
                var action = runtime.state.active ? runtime.endVRSession() : runtime.requestVRSession();
                Promise.resolve(action).catch(function (error) {
                    setStatus("WebXR error: " + error.message);
                    status.style.display = "inline-block";
                });
            });
        }).catch(function (error) {
            setStatus(error.message);
            status.style.display = "inline-block";
        });
    }

    global.EaglerXRLauncher = {
        install: addButton,
        loadRuntime: createRuntimeScript
    };

    global.addEventListener("load", addButton, { once: true });
}(typeof window !== "undefined" ? window : this));
