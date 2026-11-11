package eu.kanade.tachiyomi.ui.reader.spatial

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal class SpatialSceneRenderer(
    private val scene: SpatialDepthScene,
) : GLSurfaceView.Renderer {

    @Volatile
    private var motionX = 0f

    @Volatile
    private var motionY = 0f

    @Volatile
    private var motionZ = 0f

    @Volatile
    private var depthStrength = 1f

    @Volatile
    private var rotationAngleX = DEFAULT_ROTATION_X_RADIANS

    @Volatile
    private var rotationAngleY = DEFAULT_ROTATION_Y_RADIANS

    @Volatile
    private var rotationAngleZ = DEFAULT_ROTATION_Z_RADIANS

    @Volatile
    private var cameraZoom = 1f

    @Volatile
    private var touchMotionX = 0f

    @Volatile
    private var touchMotionY = 0f

    @Volatile
    private var rotationAnchorX = 0f

    @Volatile
    private var rotationAnchorY = 0f

    @Volatile
    private var rotationAnchorZ = scene.focusDepth

    private var program = 0
    private var vertexBuffer = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    fun updateMotion(x: Float, y: Float, z: Float) {
        motionX = x.coerceIn(-1f, 1f)
        motionY = y.coerceIn(-1f, 1f)
        motionZ = z.coerceIn(-1f, 1f)
    }

    fun updateDepthStrength(strength: Float) {
        depthStrength = strength.coerceIn(0.5f, 2f)
    }

    fun updateRotationAngles(xDegrees: Float, yDegrees: Float, zDegrees: Float) {
        rotationAngleX = Math.toRadians(xDegrees.coerceIn(0f, 20f).toDouble()).toFloat()
        rotationAngleY = Math.toRadians(yDegrees.coerceIn(0f, 20f).toDouble()).toFloat()
        rotationAngleZ = Math.toRadians(zDegrees.coerceIn(0f, 20f).toDouble()).toFloat()
    }

    fun updateCameraZoom(zoom: Float) {
        cameraZoom = zoom.coerceIn(MIN_CAMERA_ZOOM, MAX_CAMERA_ZOOM)
    }

    fun updateTouchMotion(x: Float, y: Float) {
        touchMotionX = x.coerceIn(-1f, 1f)
        touchMotionY = y.coerceIn(-1f, 1f)
    }

    fun updateRotationAnchorFromView(
        touchX: Float,
        touchY: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0) return false
        val fitScale = minOf(
            viewWidth.toFloat() / scene.imageWidth,
            viewHeight.toFloat() / scene.imageHeight,
        )
        val fittedWidth = scene.imageWidth * fitScale
        val fittedHeight = scene.imageHeight * fitScale
        val offsetX = (viewWidth - fittedWidth) * 0.5f
        val offsetY = (viewHeight - fittedHeight) * 0.5f
        val localZoom = cameraZoom
        val zoomedWidth = fittedWidth * localZoom
        val zoomedHeight = fittedHeight * localZoom
        val zoomedLeft = viewWidth * 0.5f - zoomedWidth * 0.5f
        val zoomedTop = viewHeight * 0.5f - zoomedHeight * 0.5f
        if (touchX !in zoomedLeft..zoomedLeft + zoomedWidth || touchY !in zoomedTop..zoomedTop + zoomedHeight) {
            return false
        }
        val localDepthStrength = depthStrength
        val cameraZ = scene.focusDepth * (1f - 1f / localZoom)
        var closestPoint = 0
        var closestDistanceSquared = Float.POSITIVE_INFINITY
        for (point in 0 until scene.pointCount) {
            val vertex = point * FLOATS_PER_VERTEX
            val originalDepth = scene.vertices[vertex + DEPTH_OFFSET]
            val adjustedDepth = scene.focusDepth + (originalDepth - scene.focusDepth) * localDepthStrength
            val depthScale = adjustedDepth / originalDepth.coerceAtLeast(0.001f)
            val viewDepth = (adjustedDepth - cameraZ).coerceAtLeast(0.05f)
            val imageX = scene.focalLengthPx * scene.vertices[vertex] * depthScale / viewDepth +
                scene.imageWidth * 0.5f
            val imageY = scene.focalLengthPx * scene.vertices[vertex + 1] * depthScale / viewDepth +
                scene.imageHeight * 0.5f
            val screenX = offsetX + imageX * fitScale
            val screenY = offsetY + imageY * fitScale
            val dx = screenX - touchX
            val dy = screenY - touchY
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < closestDistanceSquared) {
                closestDistanceSquared = distanceSquared
                closestPoint = point
            }
        }
        val vertex = closestPoint * FLOATS_PER_VERTEX
        rotationAnchorX = scene.vertices[vertex]
        rotationAnchorY = scene.vertices[vertex + 1]
        rotationAnchorZ = scene.vertices[vertex + DEPTH_OFFSET]
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val handles = IntArray(1)
        GLES30.glGenBuffers(1, handles, 0)
        vertexBuffer = handles[0]

        uploadVertices(vertexBuffer, scene.vertices)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (program == 0 || vertexBuffer == 0) return

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uViewport"),
            viewportWidth.toFloat(),
            viewportHeight.toFloat(),
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uImageSize"),
            scene.imageWidth.toFloat(),
            scene.imageHeight.toFloat(),
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uFocal"), scene.focalLengthPx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uFocusDepth"), scene.focusDepth)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uMotion"), motionX, motionY, motionZ)
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, "uRotationAnchor"),
            rotationAnchorX,
            rotationAnchorY,
            rotationAnchorZ,
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uDepthStrength"), depthStrength)
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, "uRotationAngles"),
            rotationAngleX,
            rotationAngleY,
            rotationAngleZ,
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uCameraZoom"), cameraZoom)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uTouchMotion"),
            touchMotionX,
            touchMotionY,
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uParticleTime"),
            (android.os.SystemClock.elapsedRealtime() % 120_000L) / 1000f,
        )

        bindVertices(vertexBuffer)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uParticlePass"), 0)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, scene.pointCount)

        // A second additive pass turns only genuine depth discontinuities into a subtle
        // animated hologram cloud. The photograph itself remains stable in the first pass.
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uParticlePass"), 1)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(false)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, scene.pointCount)
        GLES30.glDepthMask(true)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (vertexBuffer != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
            vertexBuffer = 0
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }

    private fun uploadVertices(buffer: Int, vertices: FloatArray) {
        val data = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            data,
            GLES30.GL_STATIC_DRAW,
        )
    }

    private fun bindVertices(buffer: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        val stride = FLOATS_PER_VERTEX * Float.SIZE_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, 4 * Float.SIZE_BYTES)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also { result ->
            GLES30.glAttachShader(result, vertex)
            GLES30.glAttachShader(result, fragment)
            GLES30.glLinkProgram(result)
            val status = IntArray(1)
            GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(result)
                GLES30.glDeleteProgram(result)
                error("Unable to link spatial renderer: $log")
            }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES30.glCreateShader(type).also { shader ->
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                error("Unable to compile spatial renderer: $log")
            }
        }
    }

    private companion object {
        const val FLOATS_PER_VERTEX = 8
        const val DEPTH_OFFSET = 2
        const val MIN_CAMERA_ZOOM = 0.65f
        const val MAX_CAMERA_ZOOM = 1.8f
        const val DEFAULT_ROTATION_X_RADIANS = 0.138f
        const val DEFAULT_ROTATION_Y_RADIANS = 0.108f
        const val DEFAULT_ROTATION_Z_RADIANS = 0.078f

        const val VERTEX_SHADER = """#version 300 es
            precision highp float;
            precision highp int;
            layout(location = 0) in vec3 aMean;
            layout(location = 1) in vec4 aColorOpacity;
            layout(location = 2) in float aPointSize;

            uniform vec2 uViewport;
            uniform vec2 uImageSize;
            uniform vec3 uMotion;
            uniform vec2 uTouchMotion;
            uniform vec3 uRotationAnchor;
            uniform float uFocal;
            uniform float uFocusDepth;
            uniform float uDepthStrength;
            uniform vec3 uRotationAngles;
            uniform float uCameraZoom;
            uniform int uParticlePass;
            uniform float uParticleTime;

            out vec4 vColorOpacity;
            out float vParticlePulse;
            out float vParticleSeed;
            out float vMotionActivity;

            float particleHash(vec2 value) {
                return fract(sin(dot(value, vec2(127.1, 311.7))) * 43758.5453);
            }

            void main() {
                // Exaggerate or compress depth around the focus plane without changing the
                // neutral 2D projection of any vertex.
                vec3 mean = aMean;
                float adjustedDepth = uFocusDepth + (aMean.z - uFocusDepth) * uDepthStrength;
                mean.xy *= adjustedDepth / max(aMean.z, 0.001);
                mean.z = adjustedDepth;
                vec3 p = mean;
                vec3 camera = vec3(
                    uMotion.x * uFocusDepth * 0.050,
                    uMotion.y * uFocusDepth * 0.052,
                    0.0
                );

                vec3 rotationAnchor = uRotationAnchor;
                float adjustedAnchorDepth = uFocusDepth +
                    (rotationAnchor.z - uFocusDepth) * uDepthStrength;
                rotationAnchor.xy *= adjustedAnchorDepth / max(rotationAnchor.z, 0.001);
                rotationAnchor.z = adjustedAnchorDepth;

                // Device pitch, roll and yaw drive X/Y/Z rotation around one spatial anchor.
                float xRotation = uMotion.y * uRotationAngles.x;
                float yRotation = uMotion.x * uRotationAngles.y;
                float zRotation = uMotion.z * uRotationAngles.z;
                p -= rotationAnchor;
                float cx = cos(xRotation);
                float sx = sin(xRotation);
                p = vec3(p.x, cx * p.y - sx * p.z, sx * p.y + cx * p.z);
                float cy = cos(yRotation);
                float sy = sin(yRotation);
                p = vec3(cy * p.x - sy * p.z, p.y, sy * p.x + cy * p.z);
                float cz = cos(zRotation);
                float sz = sin(zRotation);
                p = vec3(cz * p.x - sz * p.y, sz * p.x + cz * p.y, p.z);
                p += rotationAnchor;

                // Dragging remains a manual rotation around the selected anchor.
                float touchXRotation = uTouchMotion.y * 0.052;
                float touchYRotation = uTouchMotion.x * 0.043;
                p -= rotationAnchor;
                float tcx = cos(touchXRotation);
                float tsx = sin(touchXRotation);
                p = vec3(p.x, tcx * p.y - tsx * p.z, tsx * p.y + tcx * p.z);
                float tcy = cos(touchYRotation);
                float tsy = sin(touchYRotation);
                p = vec3(tcy * p.x - tsy * p.z, p.y, tsy * p.x + tcy * p.z);
                p += rotationAnchor;

                // Keep the selected XYZ point fixed on screen while preserving translation
                // parallax in front of and behind its zero-parallax depth plane. Applying one
                // uniform camera offset here would move the pivot after rotation and make the
                // gyroscope appear to ignore the selected anchor.
                float anchorDepth = max(rotationAnchor.z, 0.001);
                p.xy -= camera.xy * (1.0 - p.z / anchorDepth);

                // Pinching moves the virtual camera along Z. At the focus plane the resulting
                // magnification is exactly uCameraZoom, while near/far layers retain parallax.
                float cameraZ = uFocusDepth * (1.0 - 1.0 / uCameraZoom);
                float z = max(p.z - cameraZ, 0.05);
                vec2 imagePixel = vec2(
                    uFocal * p.x / z + uImageSize.x * 0.5,
                    uFocal * p.y / z + uImageSize.y * 0.5
                );
                float fitScale = min(uViewport.x / uImageSize.x, uViewport.y / uImageSize.y);
                vec2 fittedSize = uImageSize * fitScale;
                vec2 offset = (uViewport - fittedSize) * 0.5;
                vec2 screenPixel = offset + imagePixel * fitScale;
                vec2 ndc = vec2(
                    screenPixel.x / uViewport.x * 2.0 - 1.0,
                    1.0 - screenPixel.y / uViewport.y * 2.0
                );

                float edgeParticle = aColorOpacity.a;
                float particleSeed = particleHash(aMean.xy * 8192.0 + aMean.zz);
                float scatterSeed = particleHash(aMean.yz * 6137.0 + aMean.xx * 0.37);
                float particlePulse = 0.62 + 0.38 * sin(
                    uParticleTime * (1.4 + particleSeed * 2.2) + particleSeed * 6.28318
                );
                float motionActivity = clamp(
                    length(uMotion.xy) * 2.2 + abs(uMotion.z) * 1.2,
                    0.0,
                    1.0
                );
                if (uParticlePass == 1) {
                    float drift = edgeParticle * (1.2 + particleSeed * 3.6);
                    vec2 driftPixels = vec2(
                        sin(uParticleTime * 0.85 + particleSeed * 17.0),
                        cos(uParticleTime * 0.67 + particleSeed * 23.0)
                    ) * drift;
                    // Spread converted edge surfels into a small irregular cloud while the
                    // device moves. This occupies the disocclusion without recreating rows.
                    vec2 motionScatter = vec2(
                        particleSeed - 0.5,
                        scatterSeed - 0.5
                    ) * edgeParticle * smoothstep(0.03, 0.32, motionActivity) * 10.0;
                    driftPixels += motionScatter;
                    ndc += vec2(
                        driftPixels.x / uViewport.x * 2.0,
                        -driftPixels.y / uViewport.y * 2.0
                    );
                }

                float normalizedDepth = clamp((z - 0.05) / 2.00, 0.0, 0.99);
                gl_Position = vec4(ndc, normalizedDepth * 2.0 - 1.0, 1.0);
                float perspectiveScale = adjustedDepth / z;
                // The scene encodes up to 65% extra coverage at depth cuts. Remove all of
                // that regular square expansion from the photograph pass: even a small
                // remainder lines the samples up into comb-like parallax streaks. The soft,
                // source-coloured particle pass below now owns the transient disocclusion.
                float encodedEdgeCoverage = 1.0 + edgeParticle * 0.65;
                float stablePointSize = aPointSize / encodedEdgeCoverage;
                if (uParticlePass == 0) {
                    gl_PointSize = clamp(
                        stablePointSize * fitScale * perspectiveScale * 1.22,
                        1.75,
                        20.0
                    );
                } else {
                    gl_PointSize = mix(2.6, 7.5, particleSeed) *
                        mix(0.82, 1.12, particlePulse);
                }
                vColorOpacity = aColorOpacity;
                vParticlePulse = particlePulse;
                vParticleSeed = particleSeed;
                vMotionActivity = motionActivity;
            }
        """

        const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            precision highp int;
            in vec4 vColorOpacity;
            in float vParticlePulse;
            in float vParticleSeed;
            in float vMotionActivity;
            uniform int uParticlePass;
            out vec4 outColor;

            void main() {
                if (uParticlePass == 0) {
                    // At rest, keep the photograph intact. During parallax, progressively
                    // convert only strong depth-cut surfels into irregular rounded fragments.
                    // Whole-point dropout breaks the grid rows; the matching low-seed points
                    // are rendered as particles in the second pass.
                    float breakup = smoothstep(0.04, 0.32, vMotionActivity) *
                        smoothstep(0.14, 0.78, vColorOpacity.a);
                    if (breakup > 0.001) {
                        if (vParticleSeed < breakup * 0.28) discard;
                        vec2 fragmentPoint = gl_PointCoord * 2.0 - 1.0;
                        float fragmentRadiusSquared = dot(fragmentPoint, fragmentPoint);
                        float irregularRadius = mix(0.76, 1.10, vParticleSeed);
                        float radiusLimit = mix(2.05, irregularRadius, breakup);
                        if (fragmentRadiusSquared > radiusLimit) discard;
                    }
                    outColor = vec4(vColorOpacity.rgb, 1.0);
                    return;
                }

                float edgeParticle = vColorOpacity.a;
                if (edgeParticle < 0.18) discard;
                float hologramStrength = smoothstep(0.18, 0.82, edgeParticle);
                // Keep particles cinematic instead of turning every contour into a dotted
                // outline. Stronger depth cuts retain a slightly denser cloud.
                float activeCloud = smoothstep(0.03, 0.30, vMotionActivity);
                if (vParticleSeed > mix(0.20, 0.58, hologramStrength * activeCloud)) discard;
                vec2 particle = gl_PointCoord * 2.0 - 1.0;
                float radiusSquared = dot(particle, particle);
                if (radiusSquared > 1.0) discard;
                float halo = exp(-radiusSquared * 3.2);
                float core = 1.0 - smoothstep(0.0, 0.16, radiusSquared);
                vec3 cyan = mix(
                    vec3(0.18, 0.46, 0.72),
                    vec3(0.34, 0.76, 0.82),
                    vParticleSeed
                );
                // Let the particles inherit the photograph instead of reading as a cyan
                // outline. The small cool component is only a material cue while moving.
                vec3 sourceTint = mix(cyan, vColorOpacity.rgb, 0.82);
                float motionFade = mix(0.04, 0.96, activeCloud);
                float energy = hologramStrength * vParticlePulse * motionFade *
                    (halo * 0.34 + core * 0.82);
                outColor = vec4(sourceTint * energy, energy);
            }
        """
    }
}
