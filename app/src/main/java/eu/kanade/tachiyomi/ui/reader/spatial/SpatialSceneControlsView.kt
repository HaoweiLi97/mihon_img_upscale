package eu.kanade.tachiyomi.ui.reader.spatial

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Floating spatial controls, kept separate from the reader toolbar. */
class SpatialSceneControlsView(context: Context) : LinearLayout(context) {
    private val anchorButton = floatingButton()
    private val gyroResetButton = floatingButton()
    private val sensitivityButton = floatingButton()
    private val depthButton = floatingButton()
    private val rotationButton = floatingButton()
    private val sensitivityLabel = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
    }
    private val sensitivitySlider = SeekBar(context).apply {
        max = SENSITIVITY_STEPS
        splitTrack = false
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        progressTintList = ColorStateList.valueOf(Color.rgb(166, 200, 255))
        progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
    }
    private val sensitivityPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = View.GONE
        isClickable = true
        val panelPadding = 12.dp
        setPadding(panelPadding, 8.dp, panelPadding, 4.dp)
        background = roundedBackground(Color.argb(220, 28, 27, 31), 14.dp)
        addView(sensitivityLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(sensitivitySlider, LayoutParams(220.dp, 36.dp))
    }
    private val depthLabel = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
    }
    private val depthSlider = SeekBar(context).apply {
        max = DEPTH_STEPS
        splitTrack = false
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        progressTintList = ColorStateList.valueOf(Color.rgb(166, 200, 255))
        progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
    }
    private val depthPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = View.GONE
        isClickable = true
        val panelPadding = 12.dp
        setPadding(panelPadding, 8.dp, panelPadding, 4.dp)
        background = roundedBackground(Color.argb(220, 28, 27, 31), 14.dp)
        addView(depthLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(depthSlider, LayoutParams(220.dp, 36.dp))
    }
    private val rotationXLabel = sliderLabel()
    private val rotationYLabel = sliderLabel()
    private val rotationZLabel = sliderLabel()
    private val rotationXSlider = angleSlider()
    private val rotationYSlider = angleSlider()
    private val rotationZSlider = angleSlider()
    private val rotationResetButton = floatingButton()
    private val rotationPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = View.GONE
        isClickable = true
        val panelPadding = 12.dp
        setPadding(panelPadding, 8.dp, panelPadding, 8.dp)
        background = roundedBackground(Color.argb(220, 28, 27, 31), 14.dp)
        addView(rotationXLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rotationXSlider, LayoutParams(220.dp, 36.dp))
        addView(rotationYLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rotationYSlider, LayoutParams(220.dp, 36.dp))
        addView(rotationZLabel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rotationZSlider, LayoutParams(220.dp, 36.dp))
        addView(
            rotationResetButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 38.dp).apply { gravity = Gravity.END },
        )
    }
    private var anchorIdleText = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.END
        isClickable = false
        addView(sensitivityPanel, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(depthPanel, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rotationPanel, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            anchorButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dp).apply { topMargin = 8.dp },
        )
        addView(
            gyroResetButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dp).apply { topMargin = 8.dp },
        )
        addView(
            sensitivityButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dp).apply { topMargin = 8.dp },
        )
        addView(
            depthButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dp).apply { topMargin = 8.dp },
        )
        addView(
            rotationButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44.dp).apply { topMargin = 8.dp },
        )
    }

    fun configure(
        sensitivity: Float,
        depthStrength: Float,
        rotationAngleX: Float,
        rotationAngleY: Float,
        rotationAngleZ: Float,
        anchorText: String,
        anchorPickText: String,
        sensitivityText: (Float) -> String,
        depthText: (Float) -> String,
        rotationText: (String, Float) -> String,
        rotationButtonText: String,
        rotationResetText: String,
        gyroResetText: String,
        onAnchorRequested: () -> Unit,
        onGyroscopeReset: () -> Unit,
        onSensitivityChanged: (Float) -> Unit,
        onDepthStrengthChanged: (Float) -> Unit,
        onRotationAnglesChanged: (Float, Float, Float) -> Unit,
    ) {
        anchorIdleText = anchorText
        anchorButton.text = anchorText
        anchorButton.setOnClickListener {
            sensitivityPanel.visibility = View.GONE
            depthPanel.visibility = View.GONE
            rotationPanel.visibility = View.GONE
            anchorButton.text = anchorPickText
            onAnchorRequested()
        }
        gyroResetButton.text = gyroResetText
        gyroResetButton.setOnClickListener {
            sensitivityPanel.visibility = View.GONE
            depthPanel.visibility = View.GONE
            rotationPanel.visibility = View.GONE
            onGyroscopeReset()
        }

        val initialSensitivity = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        sensitivityButton.setOnClickListener {
            depthPanel.visibility = View.GONE
            rotationPanel.visibility = View.GONE
            sensitivityPanel.visibility = if (sensitivityPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        sensitivitySlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = MIN_SENSITIVITY + progress / 100f
                    updateSensitivityText(value, sensitivityText)
                    if (fromUser) onSensitivityChanged(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
        sensitivitySlider.progress = ((initialSensitivity - MIN_SENSITIVITY) * 100f).toInt()
        updateSensitivityText(initialSensitivity, sensitivityText)

        val initialDepth = depthStrength.coerceIn(MIN_DEPTH, MAX_DEPTH)
        depthButton.setOnClickListener {
            sensitivityPanel.visibility = View.GONE
            rotationPanel.visibility = View.GONE
            depthPanel.visibility = if (depthPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        depthSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = MIN_DEPTH + progress / 100f
                    updateDepthText(value, depthText)
                    if (fromUser) onDepthStrengthChanged(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
        depthSlider.progress = ((initialDepth - MIN_DEPTH) * 100f).toInt()
        updateDepthText(initialDepth, depthText)

        var currentRotationX = rotationAngleX.coerceIn(MIN_ROTATION_ANGLE, MAX_ROTATION_ANGLE)
        var currentRotationY = rotationAngleY.coerceIn(MIN_ROTATION_ANGLE, MAX_ROTATION_ANGLE)
        var currentRotationZ = rotationAngleZ.coerceIn(MIN_ROTATION_ANGLE, MAX_ROTATION_ANGLE)
        fun updateRotationUi() {
            rotationXLabel.text = rotationText("X", currentRotationX)
            rotationYLabel.text = rotationText("Y", currentRotationY)
            rotationZLabel.text = rotationText("Z", currentRotationZ)
            rotationButton.text = "$rotationButtonText  X ${"%.1f".format(currentRotationX)}°  " +
                "Y ${"%.1f".format(currentRotationY)}°  Z ${"%.1f".format(currentRotationZ)}°"
        }
        fun notifyRotationChanged() {
            updateRotationUi()
            onRotationAnglesChanged(currentRotationX, currentRotationY, currentRotationZ)
        }
        rotationXSlider.setOnSeekBarChangeListener(angleListener { value ->
            currentRotationX = value
            notifyRotationChanged()
        })
        rotationYSlider.setOnSeekBarChangeListener(angleListener { value ->
            currentRotationY = value
            notifyRotationChanged()
        })
        rotationZSlider.setOnSeekBarChangeListener(angleListener { value ->
            currentRotationZ = value
            notifyRotationChanged()
        })
        rotationXSlider.progress = angleToProgress(currentRotationX)
        rotationYSlider.progress = angleToProgress(currentRotationY)
        rotationZSlider.progress = angleToProgress(currentRotationZ)
        rotationResetButton.text = rotationResetText
        rotationResetButton.setOnClickListener {
            currentRotationX = DEFAULT_ROTATION_X
            currentRotationY = DEFAULT_ROTATION_Y
            currentRotationZ = DEFAULT_ROTATION_Z
            rotationXSlider.progress = angleToProgress(currentRotationX)
            rotationYSlider.progress = angleToProgress(currentRotationY)
            rotationZSlider.progress = angleToProgress(currentRotationZ)
            notifyRotationChanged()
        }
        rotationButton.setOnClickListener {
            sensitivityPanel.visibility = View.GONE
            depthPanel.visibility = View.GONE
            rotationPanel.visibility = if (rotationPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        updateRotationUi()
        sensitivityPanel.visibility = View.GONE
        depthPanel.visibility = View.GONE
        rotationPanel.visibility = View.GONE
    }

    fun completeAnchorSelection() {
        anchorButton.text = anchorIdleText
    }

    private fun updateSensitivityText(value: Float, formatter: (Float) -> String) {
        val text = formatter(value)
        sensitivityLabel.text = text
        sensitivityButton.text = text
    }

    private fun updateDepthText(value: Float, formatter: (Float) -> String) {
        val text = formatter(value)
        depthLabel.text = text
        depthButton.text = text
    }

    private fun floatingButton() = MaterialButton(
        context,
        null,
        com.google.android.material.R.attr.materialButtonStyle,
    ).apply {
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        setPadding(16.dp, 0, 16.dp, 0)
        setTextColor(Color.WHITE)
        textSize = 13f
        isAllCaps = false
        backgroundTintList = ColorStateList.valueOf(Color.argb(225, 45, 45, 52))
        cornerRadius = 22.dp
        elevation = 8.dp.toFloat()
    }

    private fun sliderLabel() = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
    }

    private fun angleSlider() = SeekBar(context).apply {
        max = ROTATION_ANGLE_STEPS
        splitTrack = false
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        progressTintList = ColorStateList.valueOf(Color.rgb(166, 200, 255))
        progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
    }

    private fun angleListener(onChanged: (Float) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChanged(MIN_ROTATION_ANGLE + progress / 10f)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun angleToProgress(value: Float): Int =
        ((value.coerceIn(MIN_ROTATION_ANGLE, MAX_ROTATION_ANGLE) - MIN_ROTATION_ANGLE) * 10f).toInt()

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        this.color = ColorStateList.valueOf(color)
        cornerRadius = radius.toFloat()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 3f
        const val SENSITIVITY_STEPS = 250
        const val MIN_DEPTH = 0.5f
        const val MAX_DEPTH = 2f
        const val DEPTH_STEPS = 150
        const val MIN_ROTATION_ANGLE = 0f
        const val MAX_ROTATION_ANGLE = 20f
        const val ROTATION_ANGLE_STEPS = 200
        const val DEFAULT_ROTATION_X = 7.9f
        const val DEFAULT_ROTATION_Y = 6.2f
        const val DEFAULT_ROTATION_Z = 4.5f
    }
}
