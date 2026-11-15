/**
 * EaglerXR - WebXR Device API Bridge for Eaglercraft 26.2
 * Provides hardware abstraction, session management, stereoscopic matrix extraction,
 * and 6DOF controller tracking for WebGL/WebGL2 canvases.
 */
(function(global) {
    'use strict';

    class EaglerXRRuntime {
        constructor() {
            this.supported = false;
            this.session = null;
            this.refSpace = null;
            this.gl = null;
            this.xrGLLayer = null;
            this.renderCallback = null;
            this.onSessionChange = null;

            this.state = {
                active: false,
                frameIndex: 0,
                lastTime: 0,
                fps: 0,
                hmd: {
                    position: [0, 1.6, 0],
                    orientation: [0, 0, 0, 1],
                    valid: false
                },
                views: {
                    left: {
                        projectionMatrix: new Float32Array(16),
                        viewMatrix: new Float32Array(16),
                        transformMatrix: new Float32Array(16),
                        viewport: { x: 0, y: 0, width: 0, height: 0 }
                    },
                    right: {
                        projectionMatrix: new Float32Array(16),
                        viewMatrix: new Float32Array(16),
                        transformMatrix: new Float32Array(16),
                        viewport: { x: 0, y: 0, width: 0, height: 0 }
                    }
                },
                controllers: {
                    left: this._createEmptyController('left'),
                    right: this._createEmptyController('right')
                }
            };

            this._checkSupport();
        }

        _createEmptyController(hand) {
            return {
                hand: hand,
                connected: false,
                position: [0, 0, 0],
                orientation: [0, 0, 0, 1],
                rayPosition: [0, 0, 0],
                rayDirection: [0, 0, -1],
                trigger: 0,
                grip: 0,
                primaryButton: false,
                secondaryButton: false,
                thumbstickClick: false,
                thumbstickAxes: [0, 0]
            };
        }

        async _checkSupport() {
            if (navigator.xr && navigator.xr.isSessionSupported) {
                try {
                    this.supported = await navigator.xr.isSessionSupported('immersive-vr');
                } catch (e) {
                    this.supported = false;
                }
            } else {
                this.supported = false;
            }
            if (this.onSessionChange) {
                this.onSessionChange('supported', this.supported);
            }
        }

        attachGLContext(glContext) {
            this.gl = glContext;
        }

        setRenderCallback(cb) {
            this.renderCallback = cb;
        }

        setSessionChangeListener(cb) {
            this.onSessionChange = cb;
        }

        async requestVRSession() {
            if (!this.supported || !navigator.xr) {
                throw new Error('WebXR immersive-vr is not supported in this browser/device.');
            }
            if (!this.gl) {
                throw new Error('WebGL context must be attached before starting WebXR session.');
            }
            if (this.session) {
                return; // already active
            }

            try {
                // Ensure makeXRCompatible is called on the WebGL context
                if (this.gl.makeXRCompatible) {
                    await this.gl.makeXRCompatible();
                }

                const session = await navigator.xr.requestSession('immersive-vr', {
                    requiredFeatures: ['local-floor'],
                    optionalFeatures: ['hand-tracking', 'bounded-floor']
                });

                this.session = session;
                this.xrGLLayer = new XRWebGLLayer(session, this.gl);
                await session.updateRenderState({ baseLayer: this.xrGLLayer });

                // Try local-floor first, then fallback to local
                try {
                    this.refSpace = await session.requestReferenceSpace('local-floor');
                } catch (e) {
                    this.refSpace = await session.requestReferenceSpace('local');
                }

                this.state.active = true;
                session.addEventListener('end', () => this._onSessionEnd());

                if (this.onSessionChange) {
                    this.onSessionChange('started', session);
                }

                session.requestAnimationFrame((time, frame) => this._onXRFrame(time, frame));
            } catch (err) {
                this.state.active = false;
                if (this.onSessionChange) {
                    this.onSessionChange('error', err.message);
                }
                throw err;
            }
        }

        async endVRSession() {
            if (this.session) {
                await this.session.end();
            }
        }

        _onSessionEnd() {
            this.session = null;
            this.refSpace = null;
            this.xrGLLayer = null;
            this.state.active = false;
            this.state.hmd.valid = false;
            this.state.controllers.left.connected = false;
            this.state.controllers.right.connected = false;

            if (this.onSessionChange) {
                this.onSessionChange('ended', null);
            }
        }

        _onXRFrame(time, frame) {
            const session = frame.session;
            if (!session || !this.state.active) {
                return;
            }

            session.requestAnimationFrame((t, f) => this._onXRFrame(t, f));

            // Calculate FPS
            if (this.state.lastTime > 0) {
                const dt = (time - this.state.lastTime) / 1000.0;
                if (dt > 0) {
                    this.state.fps = 0.9 * this.state.fps + 0.1 * (1.0 / dt);
                }
            }
            this.state.lastTime = time;
            this.state.frameIndex++;

            const pose = frame.getViewerPose(this.refSpace);
            if (pose) {
                this.state.hmd.valid = true;
                const pos = pose.transform.position;
                const ori = pose.transform.orientation;
                this.state.hmd.position[0] = pos.x;
                this.state.hmd.position[1] = pos.y;
                this.state.hmd.position[2] = pos.z;
                this.state.hmd.orientation[0] = ori.x;
                this.state.hmd.orientation[1] = ori.y;
                this.state.hmd.orientation[2] = ori.z;
                this.state.hmd.orientation[3] = ori.w;

                // Extract views
                for (const view of pose.views) {
                    const eyeKey = view.eye === 'left' ? 'left' : (view.eye === 'right' ? 'right' : null);
                    if (eyeKey && this.state.views[eyeKey]) {
                        const targetView = this.state.views[eyeKey];
                        targetView.projectionMatrix.set(view.projectionMatrix);
                        targetView.viewMatrix.set(view.transform.inverse.matrix);
                        targetView.transformMatrix.set(view.transform.matrix);

                        const vp = this.xrGLLayer.getViewport(view);
                        if (vp) {
                            targetView.viewport.x = vp.x;
                            targetView.viewport.y = vp.y;
                            targetView.viewport.width = vp.width;
                            targetView.viewport.height = vp.height;
                        }
                    }
                }
            } else {
                this.state.hmd.valid = false;
            }

            // Update controllers from input sources
            this._updateControllers(frame);

            // Bind XR framebuffer
            const fb = this.xrGLLayer ? this.xrGLLayer.framebuffer : null;
            this.gl.bindFramebuffer(this.gl.FRAMEBUFFER, fb);

            // Execute client render callback
            if (this.renderCallback && pose) {
                this.renderCallback(this.state, this.gl);
            }
        }

        _updateControllers(frame) {
            const sources = this.session.inputSources;
            this.state.controllers.left.connected = false;
            this.state.controllers.right.connected = false;

            for (const source of sources) {
                if (!source.handedness || source.handedness === 'none') {
                    continue;
                }
                const ctrl = this.state.controllers[source.handedness];
                if (!ctrl) continue;

                ctrl.connected = true;

                // Grip pose
                if (source.gripSpace) {
                    const gripPose = frame.getPose(source.gripSpace, this.refSpace);
                    if (gripPose) {
                        const gp = gripPose.transform.position;
                        const go = gripPose.transform.orientation;
                        ctrl.position[0] = gp.x;
                        ctrl.position[1] = gp.y;
                        ctrl.position[2] = gp.z;
                        ctrl.orientation[0] = go.x;
                        ctrl.orientation[1] = go.y;
                        ctrl.orientation[2] = go.z;
                        ctrl.orientation[3] = go.w;
                    }
                }

                // Target ray pose
                if (source.targetRaySpace) {
                    const rayPose = frame.getPose(source.targetRaySpace, this.refSpace);
                    if (rayPose) {
                        const rp = rayPose.transform.position;
                        const ro = rayPose.transform.orientation;
                        ctrl.rayPosition[0] = rp.x;
                        ctrl.rayPosition[1] = rp.y;
                        ctrl.rayPosition[2] = rp.z;

                        // Calculate forward ray direction from quaternion
                        ctrl.rayDirection[0] = 2 * (ro.x * ro.z + ro.w * ro.y);
                        ctrl.rayDirection[1] = 2 * (ro.y * ro.z - ro.w * ro.x);
                        ctrl.rayDirection[2] = -(1 - 2 * (ro.x * ro.x + ro.y * ro.y));
                    }
                }

                // Gamepad inputs
                if (source.gamepad) {
                    const gp = source.gamepad;
                    // Standard Gamepad mapping for WebXR:
                    // 0: trigger
                    // 1: squeeze/grip
                    // 2: touchpad
                    // 3: thumbstick
                    // 4: primary button (A/X)
                    // 5: secondary button (B/Y)
                    if (gp.buttons.length > 0) ctrl.trigger = gp.buttons[0].value;
                    if (gp.buttons.length > 1) ctrl.grip = gp.buttons[1].value;
                    if (gp.buttons.length > 3) ctrl.thumbstickClick = gp.buttons[3].pressed;
                    if (gp.buttons.length > 4) ctrl.primaryButton = gp.buttons[4].pressed;
                    if (gp.buttons.length > 5) ctrl.secondaryButton = gp.buttons[5].pressed;

                    if (gp.axes.length >= 4) {
                        // Standard XR thumbstick axes are typically 2 and 3
                        ctrl.thumbstickAxes[0] = gp.axes[2];
                        ctrl.thumbstickAxes[1] = gp.axes[3];
                    } else if (gp.axes.length >= 2) {
                        ctrl.thumbstickAxes[0] = gp.axes[0];
                        ctrl.thumbstickAxes[1] = gp.axes[1];
                    }
                }
            }
        }
    }

    global.EaglerXRRuntime = EaglerXRRuntime;
    global.eaglerXR = new EaglerXRRuntime();

})(typeof window !== 'undefined' ? window : this);
