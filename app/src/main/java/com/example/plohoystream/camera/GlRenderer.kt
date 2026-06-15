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

    /** External OES texture id the camera renders into. Valid after [init]. */
    val textureName: Int
        get() {
            checkInitialized()
            checkGlThread()
            return externalTextureId
        }

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
        if (!outputSurfaceMap.containsKey(surface)) {
            outputSurfaceMap[surface] = NO_OUTPUT_SURFACE
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

    // endregion

    // region Output surface management

    private fun getOutSurfaceOrThrow(surface: Surface): OutputSurface {
        check(outputSurfaceMap.containsKey(surface)) { "The surface is not registered." }
        return requireNotNull(outputSurfaceMap[surface])
    }

    private fun createOutputSurfaceInternal(surface: Surface): OutputSurface? {
        val eglSurface: EGLSurface = try {
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
            return null
        }
        val size = querySurfaceSize(eglSurface)
        return OutputSurface(eglSurface, size.width, size.height)
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
        currentSurface = null
        glThread = null
    }

    // endregion

    private fun checkInitialized() = check(initialized) { "GlRenderer is not initialized" }

    private fun checkGlThread() =
        check(glThread == Thread.currentThread()) { "Method must be called on the GL thread." }

    private val NO_OUTPUT_SURFACE = OutputSurface(EGL14.EGL_NO_SURFACE, 0, 0)
}
