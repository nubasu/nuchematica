package com.nubasu.nuchematica.gui.screen

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextComponent
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.registries.ForgeRegistries

class BlockPickerGridScreen(
    private val titleText: Component = TextComponent("Select Block"),
    private val parent: Screen,
    private val onBlockSelected: (Block) -> Unit,
) : Screen(titleText) {

    private lateinit var blockGrid: BlockIconGrid
    private lateinit var closeButton: Button

    override fun init() {
        val blockList = ForgeRegistries.BLOCKS.values.filter { it != Blocks.AIR }
        blockGrid = BlockIconGrid(blockList) { selected ->
            onBlockSelected(selected)
            Minecraft.getInstance().setScreen(parent)
        }
        blockGrid.setPosition(10, 40, width - 30, height - 75)
        addRenderableWidget(blockGrid)

        closeButton = Button(width / 2 - 40, height - 30, 80, 20, TextComponent("Cancel")) {
            Minecraft.getInstance().setScreen(parent)
        }
        addRenderableWidget(closeButton)
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(poseStack)
        drawCenteredString(poseStack, font, titleText, width / 2, 10, 0xFFFFFF)
        super.render(poseStack, mouseX, mouseY, partialTicks)

        blockGrid.hoveredStack?.let {
            renderTooltip(poseStack, it, mouseX, mouseY)
        }

        val totalRows = blockGrid.getRowCount()
        val visibleRows = blockGrid.getVisibleRowCount()
        val barX = width - 10
        val barY = 40
        val barHeight = height - 80

        if (totalRows > visibleRows) {
            val sliderHeight = (barHeight * visibleRows.toFloat() / totalRows).toInt().coerceAtLeast(10)
            val maxScroll = (totalRows - visibleRows).coerceAtLeast(1)
            val sliderY = barY + (blockGrid.scrollOffset * (barHeight - sliderHeight) / maxScroll)

            fill(poseStack, barX, barY, barX + 5, barY + barHeight, 0xFFCCCCCC.toInt())
            fill(poseStack, barX, sliderY, barX + 5, sliderY + sliderHeight, 0xFF888888.toInt())
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        blockGrid.mouseScrolled(delta)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}