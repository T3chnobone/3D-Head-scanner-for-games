package com.headscan

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import com.headscan.core.ScanResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/** Interactive 3D preview of a finished scan: drag to spin, pinch to zoom, it slowly turns on its own otherwise. */
class PreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    private class MeshData(val pos: FloatBuffer, val nrm: FloatBuffer, val st: FloatBuffer, val count: Int,
                           val cx: Float, val cy: Float, val cz: Float, val radius: Float, val texture: Bitmap)

    @Volatile private var pending: MeshData? = null
    @Volatile private var pendingTexture: Bitmap? = null

    /** Swaps the texture of the mesh being shown (used for live hair recolouring). */
    fun replaceTexture(argb: IntArray, size: Int) {
        pendingTexture = Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888)
    }
    private var current: MeshData? = null
    private var program = 0
    private var textureId = 0
    private var aPos = 0; private var aNrm = 0; private var aSt = 0
    private var uMvp = 0; private var uModel = 0; private var uTex = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val mvp = FloatArray(16)

    /** View zoom from pinching (1 = whole head in view). Only the view changes, not the saved model. */
    @Volatile private var zoom = 1f
    private var pinching = false
    private val scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { pinching = true; return true }
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            return true
        }
    })

    /** Back to the whole head in view. */
    fun resetZoom() { zoom = 1f }

    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f
    @Volatile private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastFrameMs = 0L

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun show(result: ScanResult) {
        val (p, n, st) = result.previewArrays()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until p.size / 3) {
            minX = minOf(minX, p[3 * i]); maxX = maxOf(maxX, p[3 * i])
            minY = minOf(minY, p[3 * i + 1]); maxY = maxOf(maxY, p[3 * i + 1])
            minZ = minOf(minZ, p[3 * i + 2]); maxZ = maxOf(maxZ, p[3 * i + 2])
        }
        val r = max(maxX - minX, max(maxY - minY, maxZ - minZ)) / 2
        val tex = Bitmap.createBitmap(result.texture, result.textureSize, result.textureSize, Bitmap.Config.ARGB_8888)
        pending = MeshData(buf(p), buf(n), buf(st), p.size / 3,
            (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, r, tex)
        yaw = 0f; pitch = 0f
    }

    private fun buf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; pinching = false; lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> pinching = true
            MotionEvent.ACTION_MOVE -> {
                // Spin with one finger only; while pinching, the fingers' movement is zoom.
                if (!pinching && e.pointerCount == 1) {
                    yaw += (e.x - lastX) * 0.4f
                    pitch = (pitch + (e.y - lastY) * 0.3f).coerceIn(-60f, 60f)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; pinching = false }
        }
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.08f, 0.08f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        program = link(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNrm = GLES20.glGetAttribLocation(program, "aNrm")
        aSt = GLES20.glGetAttribLocation(program, "aSt")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textureId = t[0]
        // The GL context may be new: re-upload whatever we are showing.
        current?.let { pending = pending ?: it }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(proj, 0, 35f, width.toFloat() / maxOf(1, height), 0.01f, 10f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        pending?.let { m ->
            pending = null
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, m.texture, 0)
            current = m
        }
        val m = current ?: return
        pendingTexture?.let { tex ->
            pendingTexture = null
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, tex, 0)
            tex.recycle()
        }
        val now = System.currentTimeMillis()
        if (!dragging && lastFrameMs != 0L) yaw += (now - lastFrameMs) * 0.02f
        lastFrameMs = now

        val dist = m.radius * 5.2f / zoom
        Matrix.setLookAtM(view, 0, 0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.translateM(model, 0, -m.cx, -m.cy, -m.cz)
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTex, 0)
        m.pos.position(0); m.nrm.position(0); m.st.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, m.pos)
        GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 0, m.nrm)
        GLES20.glVertexAttribPointer(aSt, 2, GLES20.GL_FLOAT, false, 0, m.st)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNrm)
        GLES20.glEnableVertexAttribArray(aSt)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.count)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNrm)
        GLES20.glDisableVertexAttribArray(aSt)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    companion object {
        private const val MIN_ZOOM = 0.5f
        private const val MAX_ZOOM = 4f
        private const val VS = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPos;
            attribute vec3 aNrm;
            attribute vec2 aSt;
            varying vec2 vSt;
            varying vec3 vN;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vN = (uModel * vec4(aNrm, 0.0)).xyz;
                vSt = aSt;
            }
        """
        private const val FS = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vSt;
            varying vec3 vN;
            void main() {
                vec3 n = normalize(vN);
                float light = 0.45 + 0.55 * max(dot(n, normalize(vec3(0.3, 0.5, 1.0))), 0.0);
                gl_FragColor = vec4(texture2D(uTex, vSt).rgb * light, 1.0);
            }
        """
    }
}
