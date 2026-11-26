package io.voltset.sm8850

import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES30
import android.os.PowerManager
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class GpuStressStats(
    val renderer: String,
    val elapsedSeconds: Int,
    val frames: Int,
    val fps: Double,
    val worstFrameMs: Double,
    val verifiedSamples: Int,
    val stoppedForThermal: Boolean = false,
)

internal class GpuStress(private val context: Context) {
    suspend fun run(seconds: Int, onProgress: (GpuStressStats) -> Unit): GpuStressStats =
        withContext(Dispatchers.Default) {
            require(seconds in 5..180)
            val power = context.getSystemService(PowerManager::class.java)
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "无法连接 GPU 图形驱动" }
            var surface = EGL14.EGL_NO_SURFACE
            var glContext = EGL14.EGL_NO_CONTEXT
            try {
                check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "GPU 图形环境初始化失败" }
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val count = IntArray(1)
                val attributes = intArrayOf(
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE,
                )
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0) &&
                    count[0] > 0) { "当前 GPU 无法创建离屏 GLES 3 测试环境" }
                val config = checkNotNull(configs[0])
                glContext = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                )
                surface = EGL14.eglCreatePbufferSurface(
                    display, config, intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0,
                )
                check(glContext != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE &&
                    EGL14.eglMakeCurrent(display, surface, surface, glContext)) { "无法启动 GPU 离屏渲染" }
                measure(seconds, power, onProgress)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (glContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, glContext)
                EGL14.eglTerminate(display)
                EGL14.eglReleaseThread()
            }
        }

    private suspend fun measure(
        seconds: Int,
        power: PowerManager?,
        onProgress: (GpuStressStats) -> Unit,
    ): GpuStressStats {
        val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "OpenGL ES"
        val program = program()
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        try {
            GLES30.glBindVertexArray(vao[0])
            GLES30.glViewport(0, 0, SIZE, SIZE)
            GLES30.glUseProgram(program)
            val seed = GLES30.glGetUniformLocation(program, "seed")
            check(seed >= 0) { "GPU 测试着色器配置失败" }
            val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
            fun draw(value: Float) {
                GLES30.glUniform1f(seed, value)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                GLES30.glFinish()
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU 渲染返回错误" }
            }
            fun sample(): ByteArray {
                pixels.clear()
                GLES30.glReadPixels(SIZE / 2, SIZE / 2, 64, 64, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU 无法回读渲染结果" }
                pixels.rewind()
                return ByteArray(pixels.capacity()).also { pixels.get(it) }
            }
            fun hot() = (power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >=
                PowerManager.THERMAL_STATUS_SEVERE
            if (hot()) return GpuStressStats(renderer, 0, 0, 0.0, 0.0, 0, true)
            draw(REFERENCE_SEED)
            val reference = sample()
            check(reference.asList().distinct().size > 2) { "GPU 测试图样无有效输出" }
            val started = SystemClock.elapsedRealtimeNanos()
            var nextReport = started
            var frames = 0
            var worstNanos = 0L
            var samples = 0
            fun stats(now: Long, thermal: Boolean = false): GpuStressStats {
                val elapsed = (now - started) / 1e9
                return GpuStressStats(renderer, elapsed.toInt(), frames,
                    if (elapsed > 0) frames / elapsed else 0.0, worstNanos / 1e6, samples, thermal)
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - started >= seconds * 1_000_000_000L) return stats(now)
                if (now >= nextReport) {
                    if (hot()) return stats(now, thermal = true)
                    draw(REFERENCE_SEED)
                    check(sample().contentEquals(reference)) { "GPU 回读图样与基准不一致" }
                    samples++
                    onProgress(stats(SystemClock.elapsedRealtimeNanos()))
                    nextReport = now + 1_000_000_000L
                }
                val begin = SystemClock.elapsedRealtimeNanos()
                draw((frames % 181) / 181f + 0.1f)
                worstNanos = max(worstNanos, SystemClock.elapsedRealtimeNanos() - begin)
                frames++
            }
        } finally {
            GLES30.glDeleteVertexArrays(1, vao, 0)
            GLES30.glDeleteProgram(program)
        }
    }

    private fun program(): Int {
        fun shader(type: Int, content: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, content)
            GLES30.glCompileShader(id)
            val status = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                GLES30.glDeleteShader(id)
                error("GPU 测试着色器编译失败")
            }
            return id
        }
        val vertex = shader(GLES30.GL_VERTEX_SHADER, VERTEX)
        try {
            val fragment = shader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT)
            try {
                val program = GLES30.glCreateProgram()
                GLES30.glAttachShader(program, vertex)
                GLES30.glAttachShader(program, fragment)
                GLES30.glLinkProgram(program)
                val status = IntArray(1)
                GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    GLES30.glDeleteProgram(program)
                    error("GPU 测试程序链接失败")
                }
                return program
            } finally {
                GLES30.glDeleteShader(fragment)
            }
        } finally {
            GLES30.glDeleteShader(vertex)
        }
    }

    companion object {
        private const val SIZE = 640
        private const val REFERENCE_SEED = 0.375f
        private val VERTEX = """
            #version 300 es
            void main() {
                vec2 v = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()
        private val FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform float seed;
            out vec4 outColor;
            void main() {
                vec2 z = gl_FragCoord.xy / 640.0 + vec2(seed, seed * 0.47);
                float v = 0.0;
                for (int i = 0; i < 72; ++i) {
                    z = vec2(sin(z.y * 2.1 + z.x * 0.7), cos(z.x * 1.6 - z.y * 1.3));
                    v += dot(z, z) * 0.03;
                }
                outColor = vec4(fract(v), fract(v * 1.7), fract(v * 2.3), 1.0);
            }
        """.trimIndent()
    }
}

