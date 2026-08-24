package com.nubasu.nuchematica.gui.screen

import com.mojang.blaze3d.vertex.PoseStack
import com.nubasu.nuchematica.printer.PrinterSettings
import com.nubasu.nuchematica.printer.PrinterSettingsHolder
import com.nubasu.nuchematica.printer.PrinterSettingsIO
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.TextComponent
import net.minecraft.network.chat.TranslatableComponent

public class PrinterSettingsScreen(
    private val parent: Screen,
) : Screen(TextComponent("Printer Settings")) {

    private val printerSettings: PrinterSettings = PrinterSettingsHolder.printerSettings

    override fun init() {
        addAttemptsControls()
        addIntervalControls()
        addReachControls()
        addWaterlogDryToggle()
        addSubstituteLookalikesToggle()
        addFacePlacementToggle()
        addPlanFirstModeToggle()
        addBackButton()
    }

    private fun savePrinterSettings(): Unit {
        PrinterSettingsHolder.printerSettings = printerSettings
        PrinterSettingsIO.save(printerSettings)
    }

    private fun addAttemptsControls() {
        val attemptsInput = EditBox(
            font,
            ATTEMPTS_TEXT_X,
            ATTEMPTS_Y,
            NUMBER_TEXT_WIDTH,
            NUMBER_TEXT_HEIGHT,
            TextComponent("Attempts per Tick"),
        ).apply {
            value = printerSettings.attemptsPerTick.toString()
            setResponder {
                it.toIntOrNull()?.let { value ->
                    printerSettings.attemptsPerTick = value.coerceIn(
                        MIN_PRINTER_ATTEMPTS_PER_TICK,
                        MAX_PRINTER_ATTEMPTS_PER_TICK,
                    )
                    savePrinterSettings()
                }
            }
        }
        val attemptsPlus = Button(
            ATTEMPTS_PLUS_X,
            ATTEMPTS_Y,
            MINI_BUTTON_SIZE,
            MINI_BUTTON_SIZE,
            TextComponent("+"),
        ) {
            printerSettings.attemptsPerTick = (printerSettings.attemptsPerTick + 1).coerceIn(
                MIN_PRINTER_ATTEMPTS_PER_TICK,
                MAX_PRINTER_ATTEMPTS_PER_TICK,
            )
            attemptsInput.value = printerSettings.attemptsPerTick.toString()
            savePrinterSettings()
        }
        val attemptsMinus = Button(
            ATTEMPTS_MINUS_X,
            ATTEMPTS_Y,
            MINI_BUTTON_SIZE,
            MINI_BUTTON_SIZE,
            TextComponent("-"),
        ) {
            printerSettings.attemptsPerTick = (printerSettings.attemptsPerTick - 1).coerceIn(
                MIN_PRINTER_ATTEMPTS_PER_TICK,
                MAX_PRINTER_ATTEMPTS_PER_TICK,
            )
            attemptsInput.value = printerSettings.attemptsPerTick.toString()
            savePrinterSettings()
        }
        addRenderableWidget(attemptsInput)
        addRenderableWidget(attemptsPlus)
        addRenderableWidget(attemptsMinus)
    }

    private fun addIntervalControls() {
        val intervalInput = EditBox(
            font,
            INTERVAL_TEXT_X,
            INTERVAL_Y,
            NUMBER_TEXT_WIDTH,
            NUMBER_TEXT_HEIGHT,
            TranslatableComponent(PRINTER_INTERVAL_LABEL_KEY),
        ).apply {
            value = printerSettings.placementIntervalTicks.toString()
            setResponder {
                it.toIntOrNull()?.let { value ->
                    printerSettings.placementIntervalTicks = value.coerceIn(
                        MIN_PRINTER_PLACEMENT_INTERVAL_TICKS,
                        MAX_PRINTER_PLACEMENT_INTERVAL_TICKS,
                    )
                    savePrinterSettings()
                }
            }
        }
        val intervalPlus = Button(
            INTERVAL_PLUS_X,
            INTERVAL_Y,
            MINI_BUTTON_SIZE,
            MINI_BUTTON_SIZE,
            TextComponent("+"),
        ) {
            printerSettings.placementIntervalTicks =
                (printerSettings.placementIntervalTicks + 1).coerceIn(
                    MIN_PRINTER_PLACEMENT_INTERVAL_TICKS,
                    MAX_PRINTER_PLACEMENT_INTERVAL_TICKS,
                )
            intervalInput.value = printerSettings.placementIntervalTicks.toString()
            savePrinterSettings()
        }
        val intervalMinus = Button(
            INTERVAL_MINUS_X,
            INTERVAL_Y,
            MINI_BUTTON_SIZE,
            MINI_BUTTON_SIZE,
            TextComponent("-"),
        ) {
            printerSettings.placementIntervalTicks =
                (printerSettings.placementIntervalTicks - 1).coerceIn(
                    MIN_PRINTER_PLACEMENT_INTERVAL_TICKS,
                    MAX_PRINTER_PLACEMENT_INTERVAL_TICKS,
                )
            intervalInput.value = printerSettings.placementIntervalTicks.toString()
            savePrinterSettings()
        }
        addRenderableWidget(intervalInput)
        addRenderableWidget(intervalPlus)
        addRenderableWidget(intervalMinus)
    }

    private fun addReachControls() {
        val reachInput = EditBox(
            font,
            REACH_TEXT_X,
            REACH_Y,
            NUMBER_TEXT_WIDTH,
            NUMBER_TEXT_HEIGHT,
            TextComponent("Reach"),
        ).apply {
            value = printerSettings.reach.toString()
            setResponder {
                it.toDoubleOrNull()?.let { value ->
                    printerSettings.reach = value.coerceIn(MIN_PRINTER_REACH, MAX_PRINTER_REACH)
                    savePrinterSettings()
                }
            }
        }
        val reachPlus = Button(
            REACH_PLUS_X,
            REACH_Y,
            MINI_BUTTON_SIZE,
            MINI_BUTTON_SIZE,
            TextComponent("+"),
        ) {
            printerSettings.reach = (printerSettings.reach + PRINTER_REACH_STEP)
                .coerceIn(MIN_PRINTER_REACH, MAX_PRINTER_REACH)
            reachInput.value = printerSettings.reach.toString()
            savePrinterSettings()
        }
        val reachMinus = Button(
            REACH_MINUS_X,
            REACH_Y,
            MINI_BUTTON_SIZE,
            MINI_BUTTON_SIZE,
            TextComponent("-"),
        ) {
            printerSettings.reach = (printerSettings.reach - PRINTER_REACH_STEP)
                .coerceIn(MIN_PRINTER_REACH, MAX_PRINTER_REACH)
            reachInput.value = printerSettings.reach.toString()
            savePrinterSettings()
        }
        addRenderableWidget(reachInput)
        addRenderableWidget(reachPlus)
        addRenderableWidget(reachMinus)
    }

    private fun addWaterlogDryToggle() {
        val button = Button(
            WATERLOG_DRY_BUTTON_X,
            WATERLOG_DRY_Y,
            NUMBER_TEXT_WIDTH,
            NUMBER_TEXT_HEIGHT,
            TextComponent(if (printerSettings.placeWaterloggedDry) "ON" else "OFF"),
        ) {
            printerSettings.placeWaterloggedDry = !printerSettings.placeWaterloggedDry
            it.message = TextComponent(if (printerSettings.placeWaterloggedDry) "ON" else "OFF")
            savePrinterSettings()
        }
        addRenderableWidget(button)
    }

    private fun addSubstituteLookalikesToggle() {
        val button = Button(
            SUBSTITUTE_LOOKALIKES_BUTTON_X,
            SUBSTITUTE_LOOKALIKES_Y,
            NUMBER_TEXT_WIDTH,
            NUMBER_TEXT_HEIGHT,
            TextComponent(if (printerSettings.substituteLookalikes) "ON" else "OFF"),
        ) {
            printerSettings.substituteLookalikes = !printerSettings.substituteLookalikes
            it.message = TextComponent(if (printerSettings.substituteLookalikes) "ON" else "OFF")
            savePrinterSettings()
        }
        addRenderableWidget(button)
    }

    private fun addFacePlacementToggle() {
        val button = Button(
            FACE_PLACEMENT_BUTTON_X,
            FACE_PLACEMENT_Y,
            NUMBER_TEXT_WIDTH,
            NUMBER_TEXT_HEIGHT,
            TextComponent(if (printerSettings.facePlacement) "ON" else "OFF"),
        ) {
            printerSettings.facePlacement = !printerSettings.facePlacement
            it.message = TextComponent(if (printerSettings.facePlacement) "ON" else "OFF")
            savePrinterSettings()
        }
        addRenderableWidget(button)
    }

    private fun addPlanFirstModeToggle() {
        val button = Button(
            PLAN_FIRST_MODE_BUTTON_X,
            PLAN_FIRST_MODE_Y,
            NUMBER_TEXT_WIDTH,
            NUMBER_TEXT_HEIGHT,
            TextComponent(if (printerSettings.planFirstMode) "ON" else "OFF"),
        ) {
            printerSettings.planFirstMode = !printerSettings.planFirstMode
            it.message = TextComponent(if (printerSettings.planFirstMode) "ON" else "OFF")
            savePrinterSettings()
        }
        addRenderableWidget(button)
    }

    private fun addBackButton() {
        val button = Button(
            BACK_BUTTON_X,
            BACK_BUTTON_Y,
            BACK_BUTTON_WIDTH,
            NUMBER_TEXT_HEIGHT,
            TextComponent("Back"),
        ) {
            Minecraft.getInstance().setScreen(parent)
        }
        addRenderableWidget(button)
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(poseStack)
        drawText(poseStack, "Attempts: ", LABEL_X, ATTEMPTS_Y)
        drawText(
            poseStack,
            TranslatableComponent(PRINTER_INTERVAL_LABEL_KEY).string + ": ",
            LABEL_X,
            INTERVAL_Y,
        )
        drawText(poseStack, "Reach: ", LABEL_X, REACH_Y)
        drawText(poseStack, "Waterlog dry: ", LABEL_X, WATERLOG_DRY_Y)
        drawText(
            poseStack,
            TranslatableComponent(PRINTER_SUBSTITUTE_LOOKALIKES_LABEL_KEY).string + ": ",
            LABEL_X,
            SUBSTITUTE_LOOKALIKES_Y,
        )
        drawText(
            poseStack,
            TranslatableComponent(PRINTER_FACE_PLACEMENT_LABEL_KEY).string + ": ",
            LABEL_X,
            FACE_PLACEMENT_Y,
        )
        drawText(
            poseStack,
            TranslatableComponent(PRINTER_PLAN_FIRST_MODE_LABEL_KEY).string + ": ",
            LABEL_X,
            PLAN_FIRST_MODE_Y,
        )
        super.render(poseStack, mouseX, mouseY, partialTicks)
    }

    override fun isPauseScreen(): Boolean = false

    public fun drawText(poseStack: PoseStack, text: String, x: Int, y: Int) {
        font.draw(poseStack, text, x.toFloat(), y.toFloat(), 0xFFFFFF)
    }

    private companion object {
        private const val PADDING = 5
        private const val MINI_BUTTON_SIZE = 20
        private const val NUMBER_TEXT_WIDTH = 80
        private const val NUMBER_TEXT_HEIGHT = 20
        private const val ROW_HEIGHT = NUMBER_TEXT_HEIGHT + PADDING

        private const val FIRST_LINE_BASELINE = 10
        private const val LABEL_X = FIRST_LINE_BASELINE
        private const val PRINTER_LABEL_WIDTH = 55
        private const val PRINTER_INTERVAL_LABEL_WIDTH = 90
        private const val TOGGLE_LABEL_WIDTH = NUMBER_TEXT_WIDTH
        private const val BACK_BUTTON_WIDTH = 80

        private const val MIN_PRINTER_ATTEMPTS_PER_TICK = 1
        private const val MAX_PRINTER_ATTEMPTS_PER_TICK = 8
        private const val MIN_PRINTER_PLACEMENT_INTERVAL_TICKS = 1
        private const val MAX_PRINTER_PLACEMENT_INTERVAL_TICKS = 40
        private const val MIN_PRINTER_REACH = 1.0
        private const val MAX_PRINTER_REACH = 4.0
        private const val PRINTER_REACH_STEP = 0.5

        private const val PRINTER_INTERVAL_LABEL_KEY =
            "screen.nuchematica.printer.interval_ticks"
        private const val PRINTER_FACE_PLACEMENT_LABEL_KEY =
            "screen.nuchematica.printer.face_placement"
        private const val PRINTER_SUBSTITUTE_LOOKALIKES_LABEL_KEY =
            "screen.nuchematica.printer.substitute_lookalikes"
        private const val PRINTER_PLAN_FIRST_MODE_LABEL_KEY =
            "screen.nuchematica.printer.plan_first_mode"

        private const val ATTEMPTS_Y = FIRST_LINE_BASELINE
        private const val INTERVAL_Y = ATTEMPTS_Y + ROW_HEIGHT
        private const val REACH_Y = INTERVAL_Y + ROW_HEIGHT
        private const val WATERLOG_DRY_Y = REACH_Y + ROW_HEIGHT
        private const val SUBSTITUTE_LOOKALIKES_Y = WATERLOG_DRY_Y + ROW_HEIGHT
        private const val FACE_PLACEMENT_Y = SUBSTITUTE_LOOKALIKES_Y + ROW_HEIGHT
        private const val PLAN_FIRST_MODE_Y = FACE_PLACEMENT_Y + ROW_HEIGHT
        private const val BACK_BUTTON_Y = PLAN_FIRST_MODE_Y + ROW_HEIGHT
        private const val BACK_BUTTON_X = LABEL_X

        private const val ATTEMPTS_MINUS_X = LABEL_X + PRINTER_LABEL_WIDTH
        private const val ATTEMPTS_TEXT_X = ATTEMPTS_MINUS_X + MINI_BUTTON_SIZE + PADDING
        private const val ATTEMPTS_PLUS_X = ATTEMPTS_TEXT_X + NUMBER_TEXT_WIDTH + PADDING

        private const val INTERVAL_MINUS_X = LABEL_X + PRINTER_INTERVAL_LABEL_WIDTH
        private const val INTERVAL_TEXT_X = INTERVAL_MINUS_X + MINI_BUTTON_SIZE + PADDING
        private const val INTERVAL_PLUS_X = INTERVAL_TEXT_X + NUMBER_TEXT_WIDTH + PADDING

        private const val REACH_MINUS_X = LABEL_X + PRINTER_LABEL_WIDTH
        private const val REACH_TEXT_X = REACH_MINUS_X + MINI_BUTTON_SIZE + PADDING
        private const val REACH_PLUS_X = REACH_TEXT_X + NUMBER_TEXT_WIDTH + PADDING

        private const val WATERLOG_DRY_BUTTON_X = LABEL_X + TOGGLE_LABEL_WIDTH
        private const val SUBSTITUTE_LOOKALIKES_BUTTON_X = LABEL_X + TOGGLE_LABEL_WIDTH
        private const val FACE_PLACEMENT_BUTTON_X = LABEL_X + TOGGLE_LABEL_WIDTH
        private const val PLAN_FIRST_MODE_BUTTON_X = LABEL_X + TOGGLE_LABEL_WIDTH
    }
}
