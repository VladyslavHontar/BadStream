package com.example.plohoystream.camera

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * SDR (8-bit RGBA) external-texture renderer.
 *
 * This is a faithful Kotlin port of the SDR subset of androidx CameraX's
 * `androidx.camera.core.processing.OpenGlRenderer` and its companion
 * `androidx.camera.core.processing.util.GLUtils` / `ShaderProviders`
 * (camera-core 1.6.1, `androidx-main` reference). The advanced-styling
 * (corner radius / border) and 10-bit HDR (YUV `__samplerExternal2DY2YEXT`,
 * BT2020 HLG colorspace) paths are intentionally omitted — see the HDR TODO in
 * [createEglContext] / [createWindowSurface].
 *
 * Pipeline:
 *  1. An EGL14 context + a GLES2 program with a `samplerExternalOES` passthrough
 *     fragment shader.
 *  2. A single external texture id ([textureName]) that the camera renders into
 *     (via a SurfaceTexture wrapped in a Surface, owned by the caller).
 *  3. On each frame: caller calls [render] once per output Surface, passing the
 *     SurfaceTexture transform matrix; we make that output's EGL window surface
 *     current, draw the textured quad with the transform applied, set the
 *     presentation timestamp and `eglSwapBuffers`.
 *
 * All methods MUST be called on the GL thread (the thread that called [init]).
 */
class GlRenderer {

    private companion object {
        const val TAG = "GlRenderer"

        // Passthrough SDR shaders — resolved form of androidx ShaderProviders
        // DEFAULT_VERTEX_SHADER + the SDR (non-HDR, non-advanced) default
        // fragment shader. Variable names match what loadLocations() looks up.
        const val VAR_TEXTURE_COORD = "vTextureCoord"
        const val VAR_TEXTURE = "sTexture"

        val VERTEX_SHADER =
            """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uTexMatrix;
            uniform mat4 uTransMatrix;
            varying vec2 $VAR_TEXTURE_COORD;
            varying vec2 vPosition;
            void main() {
                gl_Position = uTransMatrix * aPosition;
                $VAR_TEXTURE_COORD = (uTexMatrix * aTextureCoord).xy;
                vPosition = aPosition.xy;
            }
            """.trimIndent()

        val FRAGMENT_SHADER =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES $VAR_TEXTURE;
            uniform float uAlphaScale;
            varying vec2 $VAR_TEXTURE_COORD;
            varying vec2 vPosition;
            void main() {
                vec4 src = texture2D($VAR_TEXTURE, $VAR_TEXTURE_COORD);
                gl_FragColor = vec4(src.rgb, src.a * uAlphaScale);
            }
            """.trimIndent()

        // Freeze-blur (lens/camera switch transition): a plain 2D-sampler program. The blur is
        // achieved by capturing the live frame into a tiny FBO texture and bilinear-upscaling it.
        val BLUR_VERTEX_SHADER =
            """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vBlurCoord;
            void main() {
                gl_Position = aPosition;
                vBlurCoord = aTextureCoord.xy;
            }
            """.trimIndent()

        // 5-sample (9-tap, via linear) separable gaussian. uTexelOffset is the 1-tap step in the
        // blur direction (0 = passthrough, used for the final upscale). Weights sum to 1.
        val BLUR_FRAGMENT_SHADER =
            """
            precision mediump float;
            uniform sampler2D sBlurTexture;
            uniform vec2 uTexelOffset;
            varying vec2 vBlurCoord;
            void main() {
                vec4 sum = texture2D(sBlurTexture, vBlurCoord) * 0.2270270270;
                sum += texture2D(sBlurTexture, vBlurCoord + uTexelOffset * 1.3846153846) * 0.3162162162;
                sum += texture2D(sBlurTexture, vBlurCoord - uTexelOffset * 1.3846153846) * 0.3162162162;
                sum += texture2D(sBlurTexture, vBlurCoord + uTexelOffset * 3.2307692308) * 0.0702702703;
                sum += texture2D(sBlurTexture, vBlurCoord - uTexelOffset * 3.2307692308) * 0.0702702703;
                gl_FragColor = sum;
            }
            """.trimIndent()

        // Capture resolution for the frozen frame. Moderate (not tiny) so the gaussian — not raw
        // downscale aliasing — produces the blur. BLUR_SCALE widens the gaussian for a heavier look.
        const val FROZEN_W = 854
        const val FROZEN_H = 480
        const val BLUR_SCALE = 3.0f
        const val BLUR_ITERATIONS = 4   // each = one separable (H+V) gaussian pass

        const val METER_SIZE = 16       // luma-metering FBO side (auto-ISO): tiny, averaged on CPU

        const val SIZEOF_FLOAT = 4
        const val PIXEL_STRIDE = 4

        // Full-screen triangle strip (bottom-left, bottom-right, top-left, top-right).
        val VERTEX_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
        )
        val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
        )

        val EMPTY_ATTRIBS = intArrayOf(EGL14.EGL_NONE)

        fun createFloatBuffer(coords: FloatArray): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(coords.size * SIZEOF_FLOAT)
            bb.order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(coords)
            fb.position(0)
            return fb
        }

        fun checkGlErrorOrThrow(op: String) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw IllegalStateException("$op: GL error 0x${Integer.toHexString(error)}")
            }
        }

        fun checkEglErrorOrThrow(op: String) {
            val error = EGL14.eglGetError()
            if (error != EGL14.EGL_SUCCESS) {
                throw IllegalStateException("$op: EGL error 0x${Integer.toHexString(error)}")
            }
        }

        fun checkLocationOrThrow(location: Int, label: String) {
            if (location < 0) {
                throw IllegalStateException("Unable to locate '$label' in program")
            }
        }
    }

    /** Cached EGL window surface for an output [Surface]. */
    private data class OutputSurface(val eglSurface: EGLSurface, val width: Int, val height: Int)

    private val vertexBuf: FloatBuffer = createFloatBuffer(VERTEX_COORDS)
    private val texBuf: FloatBuffer = createFloatBuffer(TEX_COORDS)
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var initialized = false
    private var glThread: Thread? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var tempSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val outputSurfaceMap = HashMap<Surface, OutputSurface>()
    private var currentSurface: Surface? = null

    private var programHandle = -1
    private var positionLoc = -1
    private var texCoordLoc = -1
    private var transMatrixLoc = -1
    private var texMatrixLoc = -1
    private var samplerLoc = -1
    private var alphaScaleLoc = -1

    private var externalTextureId = -1
    private var externalTextureId2 = -1

    // Freeze-blur resources.
    private var blurProgramHandle = -1
    private var blurPositionLoc = -1
    private var blurTexCoordLoc = -1
    private var blurSamplerLoc = -1
    private var blurOffsetLoc = -1
    private var frozenFbo = -1
    private var frozenTexId = -1
    private var pingFbo = -1      // intermediate for the separable gaussian
    private var pingTexId = -1

    // Auto-ISO luma metering: render the live frame into a tiny FBO, read it back, average on CPU.
    private var meterFbo = -1
    private var meterTexId = -1
    private val meterBuf: ByteBuffer =
        ByteBuffer.allocateDirect(METER_SIZE * METER_SIZE * PIXEL_STRIDE).order(ByteOrder.nativeOrder())

    /** External OES texture id the camera renders into. Valid after [init]. */
    val textureName: Int
        get() {
            checkInitialized()
            checkGlThread()
            return externalTextureId
        }

    /** External OES texture id for the SECONDARY (PiP) camera. Valid after [init]. */
    val textureName2: Int
        get() { checkInitialized(); checkGlThread(); return externalTextureId2 }

    /** Initializes EGL, compiles the SDR program and creates the external texture. */
    fun init() {
        check(!initialized) { "GlRenderer is already initialized" }
        try {
            createEglContext()
            createTempSurface()
            makeCurrent(tempSurface)
            createProgram()
            useProgram()
            externalTextureId = createExternalTexture()
            activateExternalTexture(externalTextureId)
            externalTextureId2 = createExternalTexture()
            createBlurProgram()
            createFrozenFbo()
        } catch (e: RuntimeException) {
            releaseInternal()
            throw e
        }
        glThread = Thread.currentThread()
        initialized = true
    }

    /** Registers an output [Surface]. The EGL window surface is created lazily on first [render]. */
    fun registerOutputSurface(surface: Surface) {
        checkInitialized()
        checkGlThread()
        purgeInvalidOutputSurfaces()
        if (!outputSurfaceMap.containsKey(surface)) {
            outputSurfaceMap[surface] = NO_OUTPUT_SURFACE
            Log.d(TAG, "registerOutputSurface ${surface.hashCode()} valid=${surface.isValid} (total=${outputSurfaceMap.size})")
        }
    }

    /**
     * Destroys EGL window surfaces whose backing [Surface] is no longer valid (e.g. a TextureView
     * replaced on resume). Preview EGL surfaces are otherwise kept alive across camera/lens switches
     * (the close of a CameraX SurfaceOutput does NOT tear them down — see
     * [com.example.plohoystream.camera.EgressSurfaceProcessor.onOutputSurface]); this is the GC that
     * reclaims the ones that truly went away.
     */
    private fun purgeInvalidOutputSurfaces() {
        val dead = outputSurfaceMap.keys.filter { !it.isValid }
        for (s in dead) {
            Log.d(TAG, "purging invalid output surface ${s.hashCode()}")
            removeOutputSurfaceInternal(s, true)
        }
    }

    /** Unregisters an output [Surface] and destroys its EGL window surface. */
    fun unregisterOutputSurface(surface: Surface) {
        checkInitialized()
        checkGlThread()
        removeOutputSurfaceInternal(surface, true)
    }

    /**
     * Renders the current external texture into [surface] using [textureTransform]
     * (the SurfaceTexture transform matrix, already adjusted by the SurfaceOutput).
     */
    fun render(timestampNs: Long, textureTransform: FloatArray, surface: Surface) {
        checkInitialized()
        checkGlThread()

        var outputSurface = getOutSurfaceOrThrow(surface)

        // Lazily (re)create the EGL window surface.
        if (outputSurface === NO_OUTPUT_SURFACE) {
            val created = createOutputSurfaceInternal(surface) ?: return
            outputSurfaceMap[surface] = created
            outputSurface = created
        }

        if (surface !== currentSurface) {
            makeCurrent(outputSurface.eglSurface)
            currentSurface = surface
            GLES20.glViewport(0, 0, outputSurface.width, outputSurface.height)
            GLES20.glScissor(0, 0, outputSurface.width, outputSurface.height)
        }

        // Upload the texture transform matrix.
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, textureTransform, 0)
        checkGlErrorOrThrow("glUniformMatrix4fv")

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlErrorOrThrow("glDrawArrays")

        // Set presentation timestamp.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, outputSurface.eglSurface, timestampNs)

        // Swap buffer.
        if (!EGL14.eglSwapBuffers(eglDisplay, outputSurface.eglSurface)) {
            Log.w(
                TAG,
                "Failed to swap buffers with EGL error: 0x" +
                    Integer.toHexString(EGL14.eglGetError()),
            )
            removeOutputSurfaceInternal(surface, false)
        }
    }

    /**
     * Composite two camera textures to [surface]: [primaryTransform]/[secondaryTransform] are the
     * respective SurfaceTexture transforms; [pipLeft..pipBottom] is the inset rectangle in normalized
     * frame coords (0..1, origin top-left). Primary fills the frame; secondary draws into the inset.
     */
    fun renderComposite(
        timestampNs: Long,
        primaryTransform: FloatArray,
        secondaryTransform: FloatArray,
        pipLeft: Float, pipTop: Float, pipRight: Float, pipBottom: Float,
        secSrcW: Int, secSrcH: Int,
        surface: Surface,
    ) {
        checkInitialized(); checkGlThread()
        var outputSurface = getOutSurfaceOrThrow(surface)
        if (outputSurface === NO_OUTPUT_SURFACE) {
            val created = createOutputSurfaceInternal(surface) ?: return
            outputSurfaceMap[surface] = created
            outputSurface = created
        }
        makeCurrent(outputSurface.eglSurface)
        currentSurface = surface
        GLES20.glViewport(0, 0, outputSurface.width, outputSurface.height)

        // 1) Primary, full-frame.
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, identityMatrix, 0)
        GLES20.glUniform1f(alphaScaleLoc, 1.0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, primaryTransform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 2) Secondary into the inset rect, fitted to the camera's aspect (centered) so it isn't
        //    stretched. The secondary is rotated 90° (below), so its DISPLAY aspect swaps W/H:
        //    displayAspect = srcH/srcW. Area of the box not covered by the fitted content just shows
        //    the primary underneath (no letterbox bars). Quad spans [-1,1]; NDC center x = 2*cx-1,
        //    y = 1-2*cy (flip Y); scale = rect extent.
        val outW = outputSurface.width.toFloat()
        val outH = outputSurface.height.toFloat()
        val dispAspect = if (secSrcW > 0 && secSrcH > 0) secSrcH.toFloat() / secSrcW.toFloat() else 1f
        val boxWpx = (pipRight - pipLeft) * outW
        val boxHpx = (pipBottom - pipTop) * outH
        val fitWpx: Float
        val fitHpx: Float
        if (boxWpx / boxHpx > dispAspect) {
            fitHpx = boxHpx; fitWpx = boxHpx * dispAspect       // box wider than content → pillarbox
        } else {
            fitWpx = boxWpx; fitHpx = boxWpx / dispAspect       // box taller than content → letterbox
        }
        val fitWn = fitWpx / outW
        val fitHn = fitHpx / outH
        val fitL = pipLeft + ((pipRight - pipLeft) - fitWn) * 0.5f
        val fitT = pipTop + ((pipBottom - pipTop) - fitHn) * 0.5f
        val cx = fitL + fitWn * 0.5f
        val cy = fitT + fitHn * 0.5f
        val sx = fitWn
        val sy = fitHn
        val pipMatrix = FloatArray(16)
        Matrix.setIdentityM(pipMatrix, 0)
        Matrix.translateM(pipMatrix, 0, 2f * cx - 1f, 1f - 2f * cy, 0f)
        Matrix.scaleM(pipMatrix, 0, sx, sy, 1f)
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, pipMatrix, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId2)
        // The secondary (PiP) camera bypasses CameraX's orientation handling (it feeds this texture
        // directly), so upright the front camera by rotating the IMAGE 90° CCW. Texture-coord rotation
        // is the inverse of image rotation, so rotate the tex coords -90° about the texture centre.
        val secRot = FloatArray(16)
        Matrix.setIdentityM(secRot, 0)
        Matrix.translateM(secRot, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(secRot, 0, -90f, 0f, 0f, 1f)
        Matrix.translateM(secRot, 0, -0.5f, -0.5f, 0f)
        val secTex = FloatArray(16)
        Matrix.multiplyMM(secTex, 0, secRot, 0, secondaryTransform, 0)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, secTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Restore defaults for the next single-camera render()/frozen path.
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, identityMatrix, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, outputSurface.eglSurface, timestampNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, outputSurface.eglSurface)) {
            removeOutputSurfaceInternal(surface, false)
        }
    }

    /**
     * Freezes the current camera frame into the small FBO texture (downscaled), applying
     * [textureTransform] so it's oriented like the live preview. Call once when a switch begins.
     */
    fun captureFrozen(textureTransform: FloatArray) {
        checkInitialized()
        checkGlThread()
        // 1) Downscale the live frame into the frozen FBO (external-texture program).
        bindMainProgram()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frozenFbo)
        GLES20.glViewport(0, 0, FROZEN_W, FROZEN_H)
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, identityMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, textureTransform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // 2) Iterated separable gaussian: frozen --H--> ping --V--> frozen, widened by iterating.
        bindBlurProgram()
        repeat(BLUR_ITERATIONS) {
            blurPass(frozenTexId, pingFbo, BLUR_SCALE / FROZEN_W, 0f)
            blurPass(pingTexId, frozenFbo, 0f, BLUR_SCALE / FROZEN_H)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        bindMainProgram()
        currentSurface = null // force viewport reset on the next live render()
    }

    /**
     * Renders the frozen (blurred-by-upscale) frame to [surface]. Used by the transition loop while
     * the new camera reopens, so both preview and the encoder keep getting frames. Restores the main
     * program afterward so the next live [render] is unaffected.
     */
    fun renderFrozen(timestampNs: Long, surface: Surface) {
        checkInitialized()
        checkGlThread()
        // Best-effort cover: only render to a surface that already has a live EGL window surface.
        // Never CREATE one here — a surface mid-swap (e.g. during a camera switch) would fail with
        // EGL_BAD_ALLOC; the next live render() creates it cleanly once CameraX has settled.
        val outputSurface = outputSurfaceMap[surface]
        if (outputSurface == null || outputSurface === NO_OUTPUT_SURFACE) return
        makeCurrent(outputSurface.eglSurface)
        currentSurface = surface
        GLES20.glViewport(0, 0, outputSurface.width, outputSurface.height)
        bindBlurProgram()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, outputSurface.eglSurface, timestampNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, outputSurface.eglSurface)) {
            removeOutputSurfaceInternal(surface, false)
        }
        bindMainProgram()     // restore external-texture program for live render()
        currentSurface = null // force viewport reset on the next live render()
    }

    /** Releases all GL/EGL resources. Must run on the GL thread. */
    fun release() {
        if (!initialized) return
        checkGlThread()
        releaseInternal()
        initialized = false
    }

    // region EGL / GLES setup

    private fun createEglContext() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw IllegalStateException("Unable to initialize EGL14")
        }

        // SDR: 8-bit RGBA, GLES2, recordable.
        // TODO(HDR): for 10-bit/HLG select 10/10/10/2 bits, GLES3 renderable type
        //  and an HDR-aware EGLConfig (see androidx OpenGlRenderer.createEglContext).
        val rgbBits = 8
        val alphaBits = 8
        val attribToChooseConfig = intArrayOf(
            EGL14.EGL_RED_SIZE, rgbBits,
            EGL14.EGL_GREEN_SIZE, rgbBits,
            EGL14.EGL_BLUE_SIZE, rgbBits,
            EGL14.EGL_ALPHA_SIZE, alphaBits,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, EGL14.EGL_TRUE,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay, attribToChooseConfig, 0, configs, 0, configs.size, numConfigs, 0,
            )
        ) {
            throw IllegalStateException("Unable to find a suitable EGLConfig")
        }
        val config = configs[0]
        val attribToCreateContext = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE,
        )
        val context = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT, attribToCreateContext, 0,
        )
        checkEglErrorOrThrow("eglCreateContext")
        eglConfig = config
        eglContext = context

        val values = IntArray(1)
        EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values, 0)
        Log.d(TAG, "EGLContext created, client version ${values[0]}")
    }

    private fun createTempSurface() {
        val surfaceAttrib = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE,
        )
        tempSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, requireNotNull(eglConfig), surfaceAttrib, 0,
        )
        checkEglErrorOrThrow("eglCreatePbufferSurface")
        checkNotNull(tempSurface) { "surface was null" }
    }

    private fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
    }

    private fun createProgram() {
        var vertexShader = -1
        var fragmentShader = -1
        var program = -1
        try {
            vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram()
            checkGlErrorOrThrow("glCreateProgram")
            GLES20.glAttachShader(program, vertexShader)
            checkGlErrorOrThrow("glAttachShader")
            GLES20.glAttachShader(program, fragmentShader)
            checkGlErrorOrThrow("glAttachShader")
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                throw IllegalStateException(
                    "Could not link program: ${GLES20.glGetProgramInfoLog(program)}",
                )
            }
            programHandle = program
        } catch (e: RuntimeException) {
            if (vertexShader != -1) GLES20.glDeleteShader(vertexShader)
            if (fragmentShader != -1) GLES20.glDeleteShader(fragmentShader)
            if (program != -1) GLES20.glDeleteProgram(program)
            throw e
        }
        loadLocations()
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        checkGlErrorOrThrow("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val shaderLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Could not compile shader type $shaderType:$shaderLog")
        }
        return shader
    }

    private fun loadLocations() {
        positionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition")
        checkLocationOrThrow(positionLoc, "aPosition")
        transMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uTransMatrix")
        checkLocationOrThrow(transMatrixLoc, "uTransMatrix")
        alphaScaleLoc = GLES20.glGetUniformLocation(programHandle, "uAlphaScale")
        checkLocationOrThrow(alphaScaleLoc, "uAlphaScale")
        samplerLoc = GLES20.glGetUniformLocation(programHandle, VAR_TEXTURE)
        checkLocationOrThrow(samplerLoc, VAR_TEXTURE)
        texCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTextureCoord")
        checkLocationOrThrow(texCoordLoc, "aTextureCoord")
        texMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uTexMatrix")
        checkLocationOrThrow(texMatrixLoc, "uTexMatrix")
    }

    private fun useProgram() {
        GLES20.glUseProgram(programHandle)
        checkGlErrorOrThrow("glUseProgram")

        // Vertex positions.
        GLES20.glEnableVertexAttribArray(positionLoc)
        checkGlErrorOrThrow("glEnableVertexAttribArray")
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        checkGlErrorOrThrow("glVertexAttribPointer")

        // Default uniforms for the single-camera case.
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, identityMatrix, 0)
        checkGlErrorOrThrow("glUniformMatrix4fv")
        GLES20.glUniform1f(alphaScaleLoc, 1.0f)
        checkGlErrorOrThrow("glUniform1f")

        // Sampler -> texture unit 0.
        GLES20.glUniform1i(samplerLoc, 0)

        // Texture coordinates.
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        checkGlErrorOrThrow("glEnableVertexAttribArray")
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        checkGlErrorOrThrow("glVertexAttribPointer")
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlErrorOrThrow("glGenTextures")
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        checkGlErrorOrThrow("glBindTexture $texId")
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        checkGlErrorOrThrow("glTexParameter")
        return texId
    }

    private fun activateExternalTexture(textureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        checkGlErrorOrThrow("glActiveTexture")
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        checkGlErrorOrThrow("glBindTexture")
    }

    private fun createBlurProgram() {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, BLUR_VERTEX_SHADER)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, BLUR_FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "blur program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        blurProgramHandle = program
        blurPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        blurTexCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        blurSamplerLoc = GLES20.glGetUniformLocation(program, "sBlurTexture")
        blurOffsetLoc = GLES20.glGetUniformLocation(program, "uTexelOffset")
    }

    private fun newFboTexture(w: Int = FROZEN_W, h: Int = FROZEN_H): Pair<Int, Int> {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return fbo[0] to tex[0]
    }

    private fun createFrozenFbo() {
        val (ffbo, ftex) = newFboTexture(); frozenFbo = ffbo; frozenTexId = ftex
        val (pfbo, ptex) = newFboTexture(); pingFbo = pfbo; pingTexId = ptex
        val (mfbo, mtex) = newFboTexture(METER_SIZE, METER_SIZE); meterFbo = mfbo; meterTexId = mtex
        checkGlErrorOrThrow("createFrozenFbo")
    }

    /**
     * Measure the average luma (0..1) of the current camera frame for shutter-priority Auto-ISO.
     * Renders the external texture into the tiny [METER_SIZE]² FBO (the GPU box-filters it down via
     * the linear sampler) and reads it back. [textureTransform] orients the sample like the preview.
     * A glReadPixels stall — kept cheap by the size and the caller's throttling. Restores GL state.
     */
    fun meterLuma(textureTransform: FloatArray): Float {
        checkInitialized()
        checkGlThread()
        bindMainProgram()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meterFbo)
        GLES20.glViewport(0, 0, METER_SIZE, METER_SIZE)
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, identityMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, textureTransform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        meterBuf.rewind()
        GLES20.glReadPixels(0, 0, METER_SIZE, METER_SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, meterBuf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        currentSurface = null   // force viewport reset on the next live render()
        meterBuf.rewind()
        var sum = 0.0
        val px = METER_SIZE * METER_SIZE
        for (i in 0 until px) {
            val r = meterBuf.get().toInt() and 0xFF
            val g = meterBuf.get().toInt() and 0xFF
            val b = meterBuf.get().toInt() and 0xFF
            meterBuf.get()   // skip alpha
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return (sum / px / 255.0).toFloat()
    }

    /** One separable-gaussian pass: blur [srcTexId] into [dstFbo] with the given texel offset. */
    private fun blurPass(srcTexId: Int, dstFbo: Int, offX: Float, offY: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, FROZEN_W, FROZEN_H)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId)
        GLES20.glUniform2f(blurOffsetLoc, offX, offY)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Restore the external-texture program + its vertex attribs + texture (after a frozen render). */
    private fun bindMainProgram() {
        GLES20.glUseProgram(programHandle)
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glUniform1f(alphaScaleLoc, 1.0f)
        GLES20.glUniform1i(samplerLoc, 0)
        activateExternalTexture(externalTextureId)
    }

    private fun bindBlurProgram() {
        GLES20.glUseProgram(blurProgramHandle)
        GLES20.glEnableVertexAttribArray(blurPositionLoc)
        GLES20.glVertexAttribPointer(blurPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(blurTexCoordLoc)
        GLES20.glVertexAttribPointer(blurTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frozenTexId)
        GLES20.glUniform1i(blurSamplerLoc, 0)
        GLES20.glUniform2f(blurOffsetLoc, 0f, 0f)   // default: passthrough (final upscale)
    }

    // endregion

    // region Output surface management

    private fun getOutSurfaceOrThrow(surface: Surface): OutputSurface {
        check(outputSurfaceMap.containsKey(surface)) { "The surface is not registered." }
        return requireNotNull(outputSurfaceMap[surface])
    }

    private fun createOutputSurfaceInternal(surface: Surface): OutputSurface? {
        // EGL_BAD_ALLOC (0x3003) here almost always means another, stale EGL window surface still
        // holds this native window — e.g. a previous preview SurfaceOutput on the same TextureView
        // SurfaceTexture that outlived a background→resume rebind. A native window can feed only one
        // EGL window surface at a time. Reclaim every cached window surface (each live output lazily
        // recreates its own on the next render) to free the window, then try once more.
        val eglSurface = tryCreateWindowSurface(surface) ?: run {
            Log.w(TAG, "eglCreateWindowSurface failed; reclaiming stale window surfaces and retrying")
            reclaimAllWindowSurfaces()
            tryCreateWindowSurface(surface) ?: return null
        }
        val size = querySurfaceSize(eglSurface)
        return OutputSurface(eglSurface, size.width, size.height)
    }

    private fun tryCreateWindowSurface(surface: Surface): EGLSurface? = try {
        // TODO(HDR): pass an HLG colorspace attrib list
        //  (EGL_GL_COLORSPACE_KHR / EGL_GL_COLORSPACE_BT2020_HLG_EXT) here for 10-bit output.
        val s = EGL14.eglCreateWindowSurface(
            eglDisplay, requireNotNull(eglConfig), surface, EMPTY_ATTRIBS, 0,
        )
        checkEglErrorOrThrow("eglCreateWindowSurface")
        checkNotNull(s) { "surface was null" }
        s
    } catch (e: RuntimeException) {
        Log.w(TAG, "Failed to create EGL surface: ${e.message}", e)
        null
    }

    /**
     * Destroys every cached EGL window surface and resets each registered output to
     * [NO_OUTPUT_SURFACE]. Used to recover from a wedged native window (see
     * [createOutputSurfaceInternal]): the conflicting window surface may be keyed under a different,
     * now-stale [Surface], so we free all of them. Each still-live output recreates its own window
     * surface lazily on its next [render]; this costs at most one dropped frame per output.
     */
    private fun reclaimAllWindowSurfaces() {
        // Drop off the current window so its EGL surface can be destroyed.
        if (currentSurface != null) {
            currentSurface = null
            makeCurrent(tempSurface)
        }
        for (key in outputSurfaceMap.keys.toList()) {
            val out = outputSurfaceMap[key]
            if (out != null && out !== NO_OUTPUT_SURFACE && out.eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, out.eglSurface) }
            }
            outputSurfaceMap[key] = NO_OUTPUT_SURFACE
        }
    }

    private fun querySurfaceSize(eglSurface: EGLSurface): Size {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, value, 0)
        val width = value[0]
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, value, 0)
        val height = value[0]
        return Size(width, height)
    }

    private fun removeOutputSurfaceInternal(surface: Surface, unregister: Boolean) {
        if (currentSurface === surface) {
            currentSurface = null
            makeCurrent(tempSurface)
        }
        val removed = if (unregister) {
            outputSurfaceMap.remove(surface)
        } else {
            outputSurfaceMap.put(surface, NO_OUTPUT_SURFACE)
        }
        if (removed != null && removed !== NO_OUTPUT_SURFACE) {
            try {
                EGL14.eglDestroySurface(eglDisplay, removed.eglSurface)
                Log.d(TAG, "destroyed EGL surface for ${surface.hashCode()} (unregister=$unregister)")
            } catch (e: RuntimeException) {
                Log.w(TAG, "Failed to destroy EGL surface: ${e.message}", e)
            }
        }
    }

    private fun releaseInternal() {
        if (programHandle != -1) {
            GLES20.glDeleteProgram(programHandle)
            programHandle = -1
        }
        if (blurProgramHandle != -1) {
            GLES20.glDeleteProgram(blurProgramHandle)
            blurProgramHandle = -1
        }
        if (frozenFbo != -1) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(frozenFbo), 0)
            frozenFbo = -1
        }
        if (frozenTexId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(frozenTexId), 0)
            frozenTexId = -1
        }
        if (pingFbo != -1) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(pingFbo), 0)
            pingFbo = -1
        }
        if (pingTexId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(pingTexId), 0)
            pingTexId = -1
        }
        if (meterFbo != -1) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(meterFbo), 0)
            meterFbo = -1
        }
        if (meterTexId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(meterTexId), 0)
            meterTexId = -1
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            for (outputSurface in outputSurfaceMap.values) {
                if (outputSurface.eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, outputSurface.eglSurface)
                }
            }
            outputSurfaceMap.clear()
            if (tempSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, tempSurface)
                tempSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        eglConfig = null
        externalTextureId = -1
        // Reclaimed by eglTerminate above (like externalTextureId) — just clear the handle; a
        // glDeleteTextures here would run with no current context.
        externalTextureId2 = -1
        currentSurface = null
        glThread = null
    }

    // endregion

    private fun checkInitialized() = check(initialized) { "GlRenderer is not initialized" }

    private fun checkGlThread() =
        check(glThread == Thread.currentThread()) { "Method must be called on the GL thread." }

    private val NO_OUTPUT_SURFACE = OutputSurface(EGL14.EGL_NO_SURFACE, 0, 0)
}
