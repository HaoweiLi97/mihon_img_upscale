package eu.kanade.tachiyomi.ui.reader.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

class SpatialSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var sceneRenderer: SpatialSceneRenderer? = null
    private var referenceHorizontalTilt: Float? = null
    private var referencePitch: Float? = null
    private var referenceScreenTwist: Float? = null
    private var lastSensorHorizontalTilt: Float? = null
    private var lastSensorPitch: Float? = null
    private var lastSensorScreenTwist: Float? = null
    private var lastSensorTimestampNs = 0L
    private var stableSinceTimestampNs = 0L
    private var filteredX = 0f
    private var filteredY = 0f
    private var filteredZ = 0f
    private var motionStarted = false
    private var touchMotionX = 0f
    private var touchMotionY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartMotionX = 0f
    private var touchStartMotionY = 0f
    private var touchDragging = false
    private var multiTouchGesture = false
    private var anchorSelectionCallback: (() -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var cameraZoom = 1f
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean =
                anchorSelectionCallback == null

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                cameraZoom = (cameraZoom * detector.scaleFactor).coerceIn(
                    MIN_CAMERA_ZOOM,
                    MAX_CAMERA_ZOOM,
                )
                sceneRenderer?.updateCameraZoom(cameraZoom)
                requestRender()
                return true
            }
        },
    )

    @Volatile
    private var motionSensitivity = 1f

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
    }

    fun showScene(scene: SpatialDepthScene) {
        check(sceneRenderer == null) { "A spatial scene is already attached" }
        sceneRenderer = SpatialSceneRenderer(scene).also(::setRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        requestRender()
    }

    fun beginRotationAnchorSelection(onSelected: () -> Unit) {
        anchorSelectionCallback = onSelected
        recenterMotion()
    }

    fun resetGyroscope() {
        referenceHorizontalTilt = null
        referencePitch = null
        referenceScreenTwist = null
        lastSensorHorizontalTilt = null
        lastSensorPitch = null
        lastSensorScreenTwist = null
        lastSensorTimestampNs = 0L
        stableSinceTimestampNs = 0L
        filteredX = 0f
        filteredY = 0f
        filteredZ = 0f
        sceneRenderer?.updateMotion(0f, 0f, 0f)
        requestRender()
    }

    private fun recenterMotion() {
        referenceHorizontalTilt = null
        referencePitch = null
        referenceScreenTwist = null
        lastSensorHorizontalTilt = null
        lastSensorPitch = null
        lastSensorScreenTwist = null
        lastSensorTimestampNs = 0L
        stableSinceTimestampNs = 0L
        filteredX = 0f
        filteredY = 0f
        filteredZ = 0f
        touchMotionX = 0f
        touchMotionY = 0f
        sceneRenderer?.updateMotion(0f, 0f, 0f)
        sceneRenderer?.updateTouchMotion(0f, 0f)
        requestRender()
    }

    fun setMotionSensitivity(sensitivity: Float) {
        motionSensitivity = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
    }

    fun setDepthStrength(strength: Float) {
        sceneRenderer?.updateDepthStrength(strength)
        requestRender()
    }

    fun setRotationAngles(xDegrees: Float, yDegrees: Float, zDegrees: Float) {
        sceneRenderer?.updateRotationAngles(xDegrees, yDegrees, zDegrees)
        requestRender()
    }

    fun startMotion() {
        if (motionStarted || rotationSensor == null) return
        referenceHorizontalTilt = null
        referencePitch = null
        referenceScreenTwist = null
        lastSensorHorizontalTilt = null
        lastSensorPitch = null
        lastSensorScreenTwist = null
        lastSensorTimestampNs = 0L
        stableSinceTimestampNs = 0L
        filteredX = 0f
        filteredY = 0f
        filteredZ = 0f
        sceneRenderer?.updateMotion(0f, 0f, 0f)
        requestRender()
        motionStarted = sensorManager.registerListener(
            this,
            rotationSensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
    }

    fun stopMotion() {
        if (!motionStarted) return
        sensorManager.unregisterListener(this)
        motionStarted = false
    }

    fun release() {
        stopMotion()
        queueEvent { sceneRenderer?.release() }
        sceneRenderer = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor != rotationSensor) return
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        // Use screen roll for horizontal parallax. Negating it matches the existing touch
        // convention: rotating the screen to the right has the same effect as swiping right.
        val horizontalTilt = -orientation[2]
        val pitch = orientation[1]
        // Azimuth becomes a small Z-axis twist around the selected spatial anchor.
        val screenTwist = -orientation[0]
        var neutralHorizontalTilt = referenceHorizontalTilt
        var neutralPitch = referencePitch
        var neutralScreenTwist = referenceScreenTwist
        if (neutralHorizontalTilt == null || neutralPitch == null || neutralScreenTwist == null) {
            referenceHorizontalTilt = horizontalTilt
            referencePitch = pitch
            referenceScreenTwist = screenTwist
            lastSensorHorizontalTilt = horizontalTilt
            lastSensorPitch = pitch
            lastSensorScreenTwist = screenTwist
            lastSensorTimestampNs = event.timestamp
            stableSinceTimestampNs = 0L
            return
        }

        if (anchorSelectionCallback != null) {
            referenceHorizontalTilt = horizontalTilt
            referencePitch = pitch
            referenceScreenTwist = screenTwist
            lastSensorHorizontalTilt = horizontalTilt
            lastSensorPitch = pitch
            lastSensorScreenTwist = screenTwist
            lastSensorTimestampNs = event.timestamp
            stableSinceTimestampNs = 0L
            return
        }

        val elapsedSeconds = if (lastSensorTimestampNs > 0L) {
            ((event.timestamp - lastSensorTimestampNs).coerceIn(MIN_SENSOR_INTERVAL_NS, MAX_SENSOR_INTERVAL_NS) /
                NANOS_PER_SECOND).toFloat()
        } else {
            DEFAULT_SENSOR_INTERVAL_SECONDS
        }
        val sensorMovement = hypot(
            hypot(
                wrappedAngle(horizontalTilt - (lastSensorHorizontalTilt ?: horizontalTilt)),
                wrappedAngle(pitch - (lastSensorPitch ?: pitch)),
            ),
            wrappedAngle(screenTwist - (lastSensorScreenTwist ?: screenTwist)),
        )
        lastSensorHorizontalTilt = horizontalTilt
        lastSensorPitch = pitch
        lastSensorScreenTwist = screenTwist
        lastSensorTimestampNs = event.timestamp
        val sensorSpeed = sensorMovement / elapsedSeconds
        stableSinceTimestampNs = if (sensorSpeed < STABLE_SENSOR_SPEED) {
            stableSinceTimestampNs.takeIf { it != 0L } ?: event.timestamp
        } else {
            0L
        }

        // Like Apple's spatial-photo viewer, treat the device pose as a temporary impulse.
        // Once the tablet is held steady, slowly adopt that pose as the new neutral position so
        // the scene eases home instead of remaining permanently tilted.
        if (
            stableSinceTimestampNs != 0L &&
            event.timestamp - stableSinceTimestampNs >= RECENTER_DELAY_NS
        ) {
            val recenterAmount = smoothingAmount(elapsedSeconds, RECENTER_TIME_CONSTANT_SECONDS)
            neutralHorizontalTilt = moveAngleToward(neutralHorizontalTilt, horizontalTilt, recenterAmount)
            neutralPitch = moveAngleToward(neutralPitch, pitch, recenterAmount)
            neutralScreenTwist = moveAngleToward(neutralScreenTwist, screenTwist, recenterAmount)
            referenceHorizontalTilt = neutralHorizontalTilt
            referencePitch = neutralPitch
            referenceScreenTwist = neutralScreenTwist
        }

        val targetX = motionFromAngle(wrappedAngle(horizontalTilt - neutralHorizontalTilt))
        val targetY = motionFromAngle(wrappedAngle(pitch - neutralPitch))
        val targetZ = motionFromAngle(wrappedAngle(screenTwist - neutralScreenTwist))
        val filterAmount = smoothingAmount(elapsedSeconds, MOTION_FILTER_TIME_CONSTANT_SECONDS)
        filteredX += (targetX - filteredX) * filterAmount
        filteredY += (targetY - filteredY) * filterAmount
        filteredZ += (targetZ - filteredZ) * filterAmount
        sceneRenderer?.updateMotion(filteredX, filteredY, filteredZ)
        requestRender()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (anchorSelectionCallback == null) {
            scaleGestureDetector.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                touchStartMotionX = touchMotionX
                touchStartMotionY = touchMotionY
                touchDragging = false
                multiTouchGesture = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouchGesture = true
                touchDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouchGesture || scaleGestureDetector.isInProgress) return true
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (!touchDragging && hypot(dx, dy) >= touchSlop) touchDragging = true
                if (touchDragging && anchorSelectionCallback == null) {
                    touchMotionX = (touchStartMotionX - dx / (width.coerceAtLeast(1) * 0.22f)).coerceIn(-1f, 1f)
                    touchMotionY = (touchStartMotionY - dy / (height.coerceAtLeast(1) * 0.22f)).coerceIn(-1f, 1f)
                    sceneRenderer?.updateTouchMotion(touchMotionX, touchMotionY)
                    requestRender()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                touchDragging = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (multiTouchGesture) {
                    multiTouchGesture = false
                    return true
                }
                if (!touchDragging) {
                    val callback = anchorSelectionCallback
                    val selected = callback != null &&
                        sceneRenderer?.updateRotationAnchorFromView(
                            event.x,
                            event.y,
                            width,
                            height,
                        ) == true
                    if (selected) {
                        anchorSelectionCallback = null
                        callback.invoke()
                        requestRender()
                    } else if (callback == null) {
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                multiTouchGesture = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }

    private fun wrappedAngle(value: Float): Float {
        var result = value
        val twoPi = (2.0 * PI).toFloat()
        while (result > PI) result -= twoPi
        while (result < -PI) result += twoPi
        return result
    }

    private fun moveAngleToward(from: Float, to: Float, amount: Float): Float =
        wrappedAngle(from + wrappedAngle(to - from) * amount)

    private fun motionFromAngle(angle: Float): Float {
        val magnitude = ((abs(angle) - MOTION_DEAD_ZONE) / (MOTION_MAX_ANGLE - MOTION_DEAD_ZONE))
            .coerceIn(0f, 1f) * motionSensitivity
        return (if (angle < 0f) -magnitude else magnitude).coerceIn(-MAX_MOTION, MAX_MOTION)
    }

    private fun smoothingAmount(elapsedSeconds: Float, timeConstantSeconds: Float): Float =
        (1f - exp(-elapsedSeconds / timeConstantSeconds)).coerceIn(0f, 1f)

    private companion object {
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 3f
        const val MIN_CAMERA_ZOOM = 0.65f
        const val MAX_CAMERA_ZOOM = 1.8f
        val MOTION_DEAD_ZONE = Math.toRadians(0.25).toFloat()
        val MOTION_MAX_ANGLE = Math.toRadians(10.0).toFloat()
        val STABLE_SENSOR_SPEED = Math.toRadians(1.0).toFloat()
        const val MAX_MOTION = 1f
        const val MOTION_FILTER_TIME_CONSTANT_SECONDS = 0.16f
        const val RECENTER_TIME_CONSTANT_SECONDS = 0.60f
        const val RECENTER_DELAY_NS = 300_000_000L
        const val MIN_SENSOR_INTERVAL_NS = 1_000_000L
        const val MAX_SENSOR_INTERVAL_NS = 100_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val DEFAULT_SENSOR_INTERVAL_SECONDS = 1f / 60f
    }
}
