package com.nubasu.nuchematica.gui.screen

import com.mojang.blaze3d.vertex.PoseStack
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.gui.DisplayFlag
import com.nubasu.nuchematica.gui.RenderSettings
import com.nubasu.nuchematica.gui.RenderSettingsIO
import com.nubasu.nuchematica.schematic.SchematicEditor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.ceil

public class SchematicSettingsScreen(
    private val settings: RenderSettings
) : Screen(Component.literal("Rendering Setting")) {

    private val listeners = mutableListOf<() -> Unit>()
    private var screenHeight = 0
    private var screenWidth = 0

    override fun init() {
        initializeGuiSize()

        addOpacityControls()
        addOffsetControls()
        addRotationButton()

        addSelectSchematicButton()
        addFilterSettingButton()
        addHeightControl()
        addDisplayFlagToggles()
//        addPresetControls()

        screenHeight = this.height
        screenWidth = this.width

    }

    public fun onSettingsChanged(listener: () -> Unit) {
        listeners += listener
    }

    private fun notifySettingsChanged() {
        listeners.forEach { it() }
    }

    private fun addSelectSchematicButton() {
        val button = Button.builder(Component.literal("Select Schematic")) {
            Minecraft.getInstance().setScreen(SchematicListScreen())
        }.pos(SELECT_SCHEMATIC_BUTTON_X, SELECT_SCHEMATIC_BUTTON_Y).size(BUTTON_WIDTH, BUTTON_HEIGHT).build()
        addRenderableWidget(button)
    }

    private fun addFilterSettingButton() {
        val button = Button.builder(Component.literal("FilterSetting")) {
            Minecraft.getInstance().setScreen(FilterSettingScreen(settings, this) { notifySettingsChanged() } )
        }.pos(FILTER_SETTING_BUTTON_X, FILTER_SETTING_BUTTON_Y).size(BUTTON_WIDTH, BUTTON_HEIGHT).build()
        addRenderableWidget(button)
    }

    private fun addOpacityControls() {
        drawText("Alpha: ", ALPHA_HEADER_X, ALPHA_HEADER_Y)
        val label = Component.literal("Alpha:")
        val opacityInput = EditBox(font, ALPHA_TEXT_X, ALPHA_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, label).apply {
            value = settings.opacity.toString()
            setResponder {
                it.toFloatOrNull()?.let { v ->
                    settings.opacity = (ceil(v * 10) / 10f).coerceIn(0f, 1f)
                    notifySettingsChanged()
                }
            }
        }
        val plus = Button.builder(Component.literal("+")) {
            val a = (settings.opacity * 10).toInt() + 1
            settings.opacity = (a / 10f).coerceIn(0f, 1f)
            opacityInput.value = settings.opacity.toString()
            notifySettingsChanged()
        }.pos(ALPHA_PLUS_X, ALPHA_PLUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        val minus = Button.builder(Component.literal("-")) {
            val a = (settings.opacity * 10).toInt() - 1
            settings.opacity = (a / 10f).coerceIn(0f, 1f)
            opacityInput.value = settings.opacity.toString()
            notifySettingsChanged()
        }.pos(ALPHA_MINUS_X, ALPHA_MINUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        addRenderableWidget(opacityInput)
        addRenderableWidget(plus)
        addRenderableWidget(minus)
    }

    private fun addOffsetControls() {
        drawText("Offset: ", OFFSET_HEADER_X, OFFSET_HEADER_Y)

        drawText("x: ", OFFSET_X_HEADER_X, OFFSET_X_HEADER_Y)
        val labelX = Component.literal("X:")
        val inputX = EditBox(font, OFFSET_X_TEXT_X, OFFSET_X_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, labelX).apply {
            value = settings.offsetX.toString()
            setResponder {
                it.toIntOrNull()?.let { v ->
                    settings.offsetX = v
                    notifySettingsChanged()
                }
            }
        }
        val plusX = Button.builder(Component.literal("+")) {
            settings.offsetX += 1
            inputX.value = settings.offsetX.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }.pos(OFFSET_X_PLUS_X, OFFSET_X_PLUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        val minusX = Button.builder(Component.literal("-")) {
            settings.offsetX -= 1
            inputX.value = settings.offsetX.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }.pos(OFFSET_X_MINUS_X, OFFSET_X_MINUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        addRenderableWidget(inputX)
        addRenderableWidget(plusX)
        addRenderableWidget(minusX)

        drawText("y: ", OFFSET_Y_HEADER_X, OFFSET_Y_HEADER_Y)
        val labelY = Component.literal("Y:")
        val inputY = EditBox(font, OFFSET_Y_TEXT_X, OFFSET_Y_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, labelY).apply {
            value = settings.offsetY.toString()
            setResponder {
                it.toIntOrNull()?.let { v ->
                    settings.offsetY = v
                    notifySettingsChanged()
                }
            }
        }
        val plusY = Button.builder(Component.literal("+")) {
            settings.offsetY += 1
            inputY.value = settings.offsetY.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }.pos(OFFSET_Y_PLUS_X, OFFSET_Y_PLUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        val minusY = Button.builder(Component.literal("-")) {
            settings.offsetY -= 1
            inputY.value = settings.offsetY.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }.pos(OFFSET_Y_MINUS_X, OFFSET_Y_MINUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        addRenderableWidget(inputY)
        addRenderableWidget(plusY)
        addRenderableWidget(minusY)

        drawText("z: ", OFFSET_Z_HEADER_X, OFFSET_Z_HEADER_Y)
        val labelZ = Component.literal("Z:")
        val inputZ = EditBox(font, OFFSET_Z_TEXT_X, OFFSET_Z_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, labelZ).apply {
            value = settings.offsetZ.toString()
            setResponder {
                it.toIntOrNull()?.let { v ->
                    settings.offsetZ = v
                    notifySettingsChanged()
                }
            }
        }
        val plusZ = Button.builder(Component.literal("+")) {
            settings.offsetZ += 1
            inputZ.value = settings.offsetZ.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }.pos(OFFSET_Z_PLUS_X, OFFSET_Z_PLUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        val minusZ = Button.builder(Component.literal("-")) {
            settings.offsetZ -= 1
            inputZ.value = settings.offsetZ.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }.pos(OFFSET_Z_MINUS_X, OFFSET_Z_MINUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        addRenderableWidget(inputZ)
        addRenderableWidget(plusZ)
        addRenderableWidget(minusZ)
    }

    private fun addRotationButton() {
        drawText("Rotation: ", ROTATION_HEADER_X, ROTATION_HEADER_Y)

        val rotationButton = Button.builder(Component.literal(settings.rotation.name)) {
            settings.rotation = when (settings.rotation) {
                DirectionSetting.CLOCKWISE_0 -> DirectionSetting.CLOCKWISE_90
                DirectionSetting.CLOCKWISE_90 -> DirectionSetting.CLOCKWISE_180
                DirectionSetting.CLOCKWISE_180 -> DirectionSetting.CLOCKWISE_270
                DirectionSetting.CLOCKWISE_270 -> DirectionSetting.CLOCKWISE_0
            }
            Minecraft.getInstance().setScreen(this)
            SchematicEditor.rotate(settings.rotation)
            notifySettingsChanged()
        }.pos(ROTATION_BUTTON_X, ROTATION_BUTTON_Y).size(BUTTON_WIDTH, BUTTON_HEIGHT).build()
        rotationButton.message = Component.literal(settings.rotation.name)
        addRenderableWidget(rotationButton)
    }

    private fun addHeightControl() {
        drawText("Display Height: ", DISPLAY_HEIGHT_HEADER_X, DISPLAY_HEIGHT_HEADER_Y)

        val heightInput = EditBox(font, DISPLAY_HEIGHT_TEXT_X, DISPLAY_HEIGHT_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, Component.literal("Height Control")).apply {
            value = settings.heightLimit.toString()
            setResponder {
                it.toIntOrNull()?.let { v -> settings.heightLimit = v; notifySettingsChanged() }
            }
        }
        val plus = Button.builder(Component.literal("+")) {
            settings.heightLimit++
            heightInput.value = settings.heightLimit.toString()
            notifySettingsChanged()
        }.pos(DISPLAY_HEIGHT_PLUS_X, DISPLAY_HEIGHT_PLUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        val minus = Button.builder(Component.literal("-")) {
            settings.heightLimit--
            heightInput.value = settings.heightLimit.toString()
            notifySettingsChanged()
        }.pos(DISPLAY_HEIGHT_MINUS_X, DISPLAY_HEIGHT_MINUS_Y).size(MINI_BUTTON_SIZE, MINI_BUTTON_SIZE).build()
        addRenderableWidget(heightInput)
        addRenderableWidget(plus)
        addRenderableWidget(minus)
    }

    private fun addDisplayFlagToggles() {
        drawText("Display Type: ", DISPLAY_TYPE_HEADER_X, DISPLAY_TYPE_HEADER_Y)
        val button = Button.builder(Component.literal( settings.displayFlags.name)) {
            settings.displayFlags = when (settings.displayFlags) {
                DisplayFlag.ALL -> DisplayFlag.UP_TO_HEIGHT
                DisplayFlag.UP_TO_HEIGHT -> DisplayFlag.ONLY_HEIGHT
                DisplayFlag.ONLY_HEIGHT -> DisplayFlag.ALL
            }
            Minecraft.getInstance().setScreen(this)
            notifySettingsChanged()
        }.pos(DISPLAY_TYPE_BUTTON_X, DISPLAY_TYPE_BUTTON_Y).size(NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT).build()
        button.message = Component.literal(settings.displayFlags.name)
        addRenderableWidget(button)
    }

    private fun addPresetControls() {
        val presets = RenderSettingsIO.listPresets()
        presets.forEachIndexed { i, name ->
            addRenderableWidget(Button.builder(Component.literal(name)) {
                RenderSettingsIO.load(name)?.let {
                    settings.applyFrom(it)
                    notifySettingsChanged()
                }
            }.pos(10, 360 + i * 25).size(100, 20).build())
        }
        addRenderableWidget(Button.builder(Component.literal("Save")) {
            RenderSettingsIO.save(settings, "custom_preset")
        }.pos(120, 360).size(60, 20).build())
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(poseStack)
        super.render(poseStack, mouseX, mouseY, partialTicks)
    }

    override fun isPauseScreen(): Boolean = false

    public fun drawText(text: String, x: Int, y: Int) {
        addRenderableWidget(
            StringWidget(
                x,
                y,
                font.width(text),
                HEADER_TEXT_HEIGHT,
                Component.literal(text),
                font
            )
        )
    }

    private companion object {
        private const val HEADER_TEXT_HEIGHT = 15
        private const val PADDING = 5
        private const val MINI_BUTTON_SIZE = 20
        private const val MINI_HEADER_SIZE = 10
        private const val NUMBER_TEXT_WIDTH = 80
        private const val NUMBER_TEXT_HEIGHT= 20

        private const val FIRST_LINE_BASELINE = 10

        private const val ALPHA_HEADER_X = FIRST_LINE_BASELINE
        private const val ALPHA_HEADER_Y = 10
        private const val ALPHA_MINUS_X = ALPHA_HEADER_X
        private const val ALPHA_MINUS_Y = ALPHA_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING
        private const val ALPHA_TEXT_X = ALPHA_MINUS_X + MINI_BUTTON_SIZE + PADDING
        private const val ALPHA_TEXT_Y = ALPHA_MINUS_Y
        private const val ALPHA_PLUS_X = ALPHA_TEXT_X + NUMBER_TEXT_WIDTH + PADDING
        private const val ALPHA_PLUS_Y = ALPHA_TEXT_Y

        private const val OFFSET_HEADER_X = FIRST_LINE_BASELINE
        private const val OFFSET_HEADER_Y = ALPHA_PLUS_Y + NUMBER_TEXT_HEIGHT + PADDING

        private const val OFFSET_X_HEADER_X = FIRST_LINE_BASELINE
        private const val OFFSET_X_HEADER_Y = OFFSET_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING
        private const val OFFSET_X_MINUS_X = OFFSET_X_HEADER_X + MINI_HEADER_SIZE + PADDING
        private const val OFFSET_X_MINUS_Y = OFFSET_X_HEADER_Y
        private const val OFFSET_X_TEXT_X = OFFSET_X_MINUS_X + MINI_BUTTON_SIZE + PADDING
        private const val OFFSET_X_TEXT_Y = OFFSET_X_MINUS_Y
        private const val OFFSET_X_PLUS_X = OFFSET_X_TEXT_X + NUMBER_TEXT_WIDTH + PADDING
        private const val OFFSET_X_PLUS_Y = OFFSET_X_TEXT_Y

        private const val OFFSET_Y_HEADER_X = FIRST_LINE_BASELINE
        private const val OFFSET_Y_HEADER_Y = OFFSET_X_MINUS_Y + NUMBER_TEXT_HEIGHT + PADDING
        private const val OFFSET_Y_MINUS_X = OFFSET_Y_HEADER_X + MINI_HEADER_SIZE + PADDING
        private const val OFFSET_Y_MINUS_Y = OFFSET_Y_HEADER_Y
        private const val OFFSET_Y_TEXT_X = OFFSET_Y_MINUS_X + MINI_BUTTON_SIZE + PADDING
        private const val OFFSET_Y_TEXT_Y = OFFSET_Y_MINUS_Y
        private const val OFFSET_Y_PLUS_X = OFFSET_Y_TEXT_X + NUMBER_TEXT_WIDTH + PADDING
        private const val OFFSET_Y_PLUS_Y = OFFSET_Y_TEXT_Y

        private const val OFFSET_Z_HEADER_X = FIRST_LINE_BASELINE
        private const val OFFSET_Z_HEADER_Y = OFFSET_Y_PLUS_Y + NUMBER_TEXT_HEIGHT + PADDING
        private const val OFFSET_Z_MINUS_X = OFFSET_Z_HEADER_X + MINI_HEADER_SIZE + PADDING
        private const val OFFSET_Z_MINUS_Y = OFFSET_Z_HEADER_Y
        private const val OFFSET_Z_TEXT_X = OFFSET_Z_MINUS_X + MINI_BUTTON_SIZE + PADDING
        private const val OFFSET_Z_TEXT_Y = OFFSET_Z_MINUS_Y
        private const val OFFSET_Z_PLUS_X = OFFSET_Z_TEXT_X + NUMBER_TEXT_WIDTH + PADDING
        private const val OFFSET_Z_PLUS_Y = OFFSET_Z_TEXT_Y

        private const val DISPLAY_HEIGHT_HEADER_X = FIRST_LINE_BASELINE
        private const val DISPLAY_HEIGHT_HEADER_Y = OFFSET_Z_PLUS_Y + NUMBER_TEXT_HEIGHT + PADDING
        private const val DISPLAY_HEIGHT_MINUS_X = DISPLAY_HEIGHT_HEADER_X
        private const val DISPLAY_HEIGHT_MINUS_Y = DISPLAY_HEIGHT_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING
        private const val DISPLAY_HEIGHT_TEXT_X = DISPLAY_HEIGHT_MINUS_X + MINI_BUTTON_SIZE + PADDING
        private const val DISPLAY_HEIGHT_TEXT_Y = DISPLAY_HEIGHT_MINUS_Y
        private const val DISPLAY_HEIGHT_PLUS_X = DISPLAY_HEIGHT_TEXT_X + NUMBER_TEXT_WIDTH + PADDING
        private const val DISPLAY_HEIGHT_PLUS_Y = DISPLAY_HEIGHT_TEXT_Y
    }


    // based on width
    private var BUTTON_WIDTH = 100
    private var BUTTON_HEIGHT = 20
    private var SECOND_LINE_BASELINE = width - 130

    private var SELECT_SCHEMATIC_BUTTON_X = SECOND_LINE_BASELINE
    private var SELECT_SCHEMATIC_BUTTON_Y = 20

    private var FILTER_SETTING_BUTTON_X = SECOND_LINE_BASELINE
    private var FILTER_SETTING_BUTTON_Y = SELECT_SCHEMATIC_BUTTON_Y + BUTTON_HEIGHT + PADDING

    private var ROTATION_HEADER_X = SECOND_LINE_BASELINE
    private var ROTATION_HEADER_Y = FILTER_SETTING_BUTTON_Y + BUTTON_HEIGHT + PADDING
    private var ROTATION_BUTTON_X = SECOND_LINE_BASELINE
    private var ROTATION_BUTTON_Y = ROTATION_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING

    private var DISPLAY_TYPE_HEADER_X = SECOND_LINE_BASELINE
    private var DISPLAY_TYPE_HEADER_Y = ROTATION_BUTTON_Y + BUTTON_HEIGHT + PADDING
    private var DISPLAY_TYPE_BUTTON_X = SECOND_LINE_BASELINE
    private var DISPLAY_TYPE_BUTTON_Y = DISPLAY_TYPE_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING

    private fun initializeGuiSize() {
        SECOND_LINE_BASELINE = this.width - 130
        SELECT_SCHEMATIC_BUTTON_X = SECOND_LINE_BASELINE
        SELECT_SCHEMATIC_BUTTON_Y = 20
        FILTER_SETTING_BUTTON_X = SECOND_LINE_BASELINE
        FILTER_SETTING_BUTTON_Y = SELECT_SCHEMATIC_BUTTON_Y + BUTTON_HEIGHT + PADDING
        ROTATION_HEADER_X = SECOND_LINE_BASELINE
        ROTATION_HEADER_Y = FILTER_SETTING_BUTTON_Y + BUTTON_HEIGHT + PADDING
        ROTATION_BUTTON_X = SECOND_LINE_BASELINE
        ROTATION_BUTTON_Y = ROTATION_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING
        DISPLAY_TYPE_HEADER_X = SECOND_LINE_BASELINE
        DISPLAY_TYPE_HEADER_Y = ROTATION_BUTTON_Y + BUTTON_HEIGHT + PADDING
        DISPLAY_TYPE_BUTTON_X = SECOND_LINE_BASELINE
        DISPLAY_TYPE_BUTTON_Y = DISPLAY_TYPE_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING
    }
}
