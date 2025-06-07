package com.nubasu.nuchematica.gui.screen

import com.mojang.blaze3d.vertex.PoseStack
import com.nubasu.nuchematica.common.Vector3
import com.nubasu.nuchematica.gui.DirectionSetting
import com.nubasu.nuchematica.gui.DisplayFlag
import com.nubasu.nuchematica.gui.RenderSettings
import com.nubasu.nuchematica.gui.RenderSettingsIO
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import com.nubasu.nuchematica.schematic.SchematicEditor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.TextComponent
import kotlin.math.ceil

public class SchematicSettingsScreen(
    private val settings: RenderSettings
) : Screen(TextComponent("Rendering Setting")) {

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
        addMoveHere()
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
        val button = Button(SELECT_SCHEMATIC_BUTTON_X, SELECT_SCHEMATIC_BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT, TextComponent("Select Schematic")) {
            Minecraft.getInstance().setScreen(SchematicListScreen())
        }
        addRenderableWidget(button)
    }

    private fun addFilterSettingButton() {
        val button = Button(FILTER_SETTING_BUTTON_X, FILTER_SETTING_BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT, TextComponent("FilterSetting")) {
            Minecraft.getInstance().setScreen(FilterSettingScreen(settings, this) { notifySettingsChanged() } )
        }
        addRenderableWidget(button)
    }

    private fun addOpacityControls() {
        val label = TextComponent("Alpha:")
        val opacityInput = EditBox(font, ALPHA_TEXT_X, ALPHA_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, label).apply {
            value = settings.opacity.toString()
            setResponder {
                it.toFloatOrNull()?.let { v ->
                    settings.opacity = (ceil(v * 10) / 10f).coerceIn(0f, 1f)
                    notifySettingsChanged()
                }
            }
        }
        val plus = Button(ALPHA_PLUS_X, ALPHA_PLUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("+")) {
            val a = (settings.opacity * 10).toInt() + 1
            settings.opacity = (a / 10f).coerceIn(0f, 1f)
            opacityInput.value = settings.opacity.toString()
            notifySettingsChanged()
        }
        val minus = Button(ALPHA_MINUS_X, ALPHA_MINUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("-")) {
            val a = (settings.opacity * 10).toInt() - 1
            settings.opacity = (a / 10f).coerceIn(0f, 1f)
            opacityInput.value = settings.opacity.toString()
            notifySettingsChanged()
        }
        addRenderableWidget(opacityInput)
        addRenderableWidget(plus)
        addRenderableWidget(minus)
    }

    private fun addOffsetControls() {
        val labelX = TextComponent("X:")
        val inputX = EditBox(font, OFFSET_X_TEXT_X, OFFSET_X_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, labelX).apply {
            value = settings.offsetX.toString()
            setResponder {
                it.toIntOrNull()?.let { v ->
                    settings.offsetX = v
                    notifySettingsChanged()
                }
            }
        }
        val plusX = Button(OFFSET_X_PLUS_X, OFFSET_X_PLUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("+")) {
            settings.offsetX += 1
            inputX.value = settings.offsetX.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }
        val minusX = Button(OFFSET_X_MINUS_X, OFFSET_X_MINUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("-")) {
            settings.offsetX -= 1
            inputX.value = settings.offsetX.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }
        addRenderableWidget(inputX)
        addRenderableWidget(plusX)
        addRenderableWidget(minusX)

        val labelY = TextComponent("Y:")
        val inputY = EditBox(font, OFFSET_Y_TEXT_X, OFFSET_Y_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, labelY).apply {
            value = settings.offsetY.toString()
            setResponder {
                it.toIntOrNull()?.let { v ->
                    settings.offsetY = v
                    notifySettingsChanged()
                }
            }
        }
        val plusY = Button(OFFSET_Y_PLUS_X, OFFSET_Y_PLUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("+")) {
            settings.offsetY += 1
            inputY.value = settings.offsetY.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }
        val minusY = Button(OFFSET_Y_MINUS_X, OFFSET_Y_MINUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("-")) {
            settings.offsetY -= 1
            inputY.value = settings.offsetY.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }
        addRenderableWidget(inputY)
        addRenderableWidget(plusY)
        addRenderableWidget(minusY)

        val labelZ = TextComponent("Z:")
        val inputZ = EditBox(font, OFFSET_Z_TEXT_X, OFFSET_Z_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, labelZ).apply {
            value = settings.offsetZ.toString()
            setResponder {
                it.toIntOrNull()?.let { v ->
                    settings.offsetZ = v
                    notifySettingsChanged()
                }
            }
        }
        val plusZ = Button(OFFSET_Z_PLUS_X, OFFSET_Z_PLUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("+")) {
            settings.offsetZ += 1
            inputZ.value = settings.offsetZ.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }
        val minusZ = Button(OFFSET_Z_MINUS_X, OFFSET_Z_MINUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("-")) {
            settings.offsetZ -= 1
            inputZ.value = settings.offsetZ.toString()
            SchematicEditor.translate(Vector3(settings.offsetX, settings.offsetY, settings.offsetZ))
            notifySettingsChanged()
        }
        addRenderableWidget(inputZ)
        addRenderableWidget(plusZ)
        addRenderableWidget(minusZ)
    }

    private fun addRotationButton() {

        val rotationButton = Button(ROTATION_BUTTON_X, ROTATION_BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT, TextComponent(settings.rotation.name)) {
            settings.rotation = when (settings.rotation) {
                DirectionSetting.CLOCKWISE_0 -> DirectionSetting.CLOCKWISE_90
                DirectionSetting.CLOCKWISE_90 -> DirectionSetting.CLOCKWISE_180
                DirectionSetting.CLOCKWISE_180 -> DirectionSetting.CLOCKWISE_270
                DirectionSetting.CLOCKWISE_270 -> DirectionSetting.CLOCKWISE_0
            }
            Minecraft.getInstance().setScreen(this)
            SchematicEditor.rotate(settings.rotation)
            notifySettingsChanged()
        }
        rotationButton.message = TextComponent(settings.rotation.name)
        addRenderableWidget(rotationButton)
    }

    private fun addHeightControl() {
        val heightInput = EditBox(font, DISPLAY_HEIGHT_TEXT_X, DISPLAY_HEIGHT_TEXT_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, TextComponent("Height Control")).apply {
            value = settings.heightLimit.toString()
            setResponder {
                it.toIntOrNull()?.let { v -> settings.heightLimit = v; notifySettingsChanged() }
            }
        }
        val plus = Button(DISPLAY_HEIGHT_PLUS_X, DISPLAY_HEIGHT_PLUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("+")) {
            settings.heightLimit++
            heightInput.value = settings.heightLimit.toString()
            notifySettingsChanged()
        }
        val minus = Button(DISPLAY_HEIGHT_MINUS_X, DISPLAY_HEIGHT_MINUS_Y, MINI_BUTTON_SIZE, MINI_BUTTON_SIZE, TextComponent("-")) {
            settings.heightLimit--
            heightInput.value = settings.heightLimit.toString()
            notifySettingsChanged()
        }
        addRenderableWidget(heightInput)
        addRenderableWidget(plus)
        addRenderableWidget(minus)
    }

    private fun addDisplayFlagToggles() {
        val button = Button(DISPLAY_TYPE_BUTTON_X, DISPLAY_TYPE_BUTTON_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, TextComponent( settings.displayFlags.name)) {
            settings.displayFlags = when (settings.displayFlags) {
                DisplayFlag.ALL -> DisplayFlag.UP_TO_HEIGHT
                DisplayFlag.UP_TO_HEIGHT -> DisplayFlag.ONLY_HEIGHT
                DisplayFlag.ONLY_HEIGHT -> DisplayFlag.ALL
            }
            Minecraft.getInstance().setScreen(this)
            notifySettingsChanged()
        }
        button.message = TextComponent(settings.displayFlags.name)
        addRenderableWidget(button)
    }

    private fun addMoveHere() {
        val button = Button(MOVE_HERE_BUTTON_X, MOVE_HERE_BUTTON_Y, NUMBER_TEXT_WIDTH, NUMBER_TEXT_HEIGHT, TextComponent( "Move Here")) {
            SchematicRenderManager.updateInitialPosition()
        }
        button.message = TextComponent("Move Here")
        addRenderableWidget(button)
    }


    private fun addPresetControls() {
        val presets = RenderSettingsIO.listPresets()
        presets.forEachIndexed { i, name ->
            addRenderableWidget(Button(10, 360 + i * 25, 100, 20, TextComponent(name)) {
                RenderSettingsIO.load(name)?.let {
                    settings.applyFrom(it)
                    notifySettingsChanged()
                }
            })
        }
        addRenderableWidget(Button(120, 360, 60, 20, TextComponent("Save")) {
            RenderSettingsIO.save(settings, "custom_preset")
        })
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(poseStack)
        drawText(poseStack, "Alpha: ", ALPHA_HEADER_X, ALPHA_HEADER_Y)
        drawText(poseStack, "Offset: ", OFFSET_HEADER_X, OFFSET_HEADER_Y)
        drawText(poseStack, "x: ", OFFSET_X_HEADER_X, OFFSET_X_HEADER_Y)
        drawText(poseStack, "y: ", OFFSET_Y_HEADER_X, OFFSET_Y_HEADER_Y)
        drawText(poseStack, "z: ", OFFSET_Z_HEADER_X, OFFSET_Z_HEADER_Y)
        drawText(poseStack, "Rotation: ", ROTATION_HEADER_X, ROTATION_HEADER_Y)
        drawText(poseStack, "Display Height: ", DISPLAY_HEIGHT_HEADER_X, DISPLAY_HEIGHT_HEADER_Y)
        drawText(poseStack, "Display Type: ", DISPLAY_TYPE_HEADER_X, DISPLAY_TYPE_HEADER_Y)


        super.render(poseStack, mouseX, mouseY, partialTicks)
    }

    override fun isPauseScreen(): Boolean = false

    public fun drawText(poseStack: PoseStack, text: String, x: Int, y: Int) {
        font.draw(poseStack, text, x.toFloat(), y.toFloat(), 0xFFFFFF)
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

    private var MOVE_HERE_HEADER_X = SECOND_LINE_BASELINE
    private var MOVE_HERE_HEADER_Y = DISPLAY_TYPE_BUTTON_Y + BUTTON_HEIGHT + PADDING
    private var MOVE_HERE_BUTTON_X = SECOND_LINE_BASELINE
    private var MOVE_HERE_BUTTON_Y = MOVE_HERE_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING

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

        MOVE_HERE_HEADER_X = SECOND_LINE_BASELINE
        MOVE_HERE_HEADER_Y = DISPLAY_TYPE_BUTTON_Y + BUTTON_HEIGHT + PADDING
        MOVE_HERE_BUTTON_X = SECOND_LINE_BASELINE
        MOVE_HERE_BUTTON_Y = MOVE_HERE_HEADER_Y + HEADER_TEXT_HEIGHT + PADDING
    }
}
