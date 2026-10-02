package com.nubasu.nuchematica.gui.screen

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

class BlockIconGrid(
    private val blocks: List<Block>,
    private val onBlockSelected: (Block) -> Unit
) : AbstractWidget(0, 0, 0, 0, Component.nullToEmpty("")) {

    private var columns = 10
    private val iconSize = 20
    private val padding = 4
    private val itemRenderer: ItemRenderer = Minecraft.getInstance().itemRenderer
    private val itemStacks = blocks.map { ItemStack(it.asItem()) }

    var hoveredStack: ItemStack? = null
    var scrollOffset = 0

    fun setPosition(x: Int, y: Int, width: Int, height: Int) {
        this.x = x
        this.y = y
        this.width = width
        columns = (this.width / (iconSize + padding))
        this.height = height
    }

    fun getRowCount(): Int = (itemStacks.size + columns - 1) / columns
    fun getVisibleRowCount(): Int = (this.height / (iconSize + padding)).coerceAtLeast(1)

    fun scroll(delta: Int) {
        val maxOffset = getRowCount() - getVisibleRowCount()
        scrollOffset = (scrollOffset - delta).coerceIn(0, maxOffset)
    }

    fun mouseScrolled(delta: Double) {
        scroll(delta.toInt())
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTicks: Float) {
        hoveredStack = null
        val visibleRows = getVisibleRowCount()
        for (row in 0 until visibleRows) {
            val actualRow = row + scrollOffset
            for (col in 0 until columns) {
                val index = actualRow * columns + col
                if (index >= itemStacks.size) break
                val stack = itemStacks[index]
                val xPos = x + col * (iconSize + padding)
                val yPos = y + row * (iconSize + padding)
                itemRenderer.renderAndDecorateItem(stack, xPos, yPos)
                if (isMouseOver(mouseX, mouseY, xPos, yPos)) {
                    hoveredStack = stack
                }
            }
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val visibleRows = getVisibleRowCount()
        for (row in 0 until visibleRows) {
            val actualRow = row + scrollOffset
            for (col in 0 until columns) {
                val index = actualRow * columns + col
                if (index >= itemStacks.size) break
                val xPos = x + col * (iconSize + padding)
                val yPos = y + row * (iconSize + padding)
                if (mouseX >= xPos && mouseX < xPos + iconSize && mouseY >= yPos && mouseY < yPos + iconSize) {
                    onBlockSelected(blocks[index])
                    return true
                }
            }
        }
        return false
    }

    private fun isMouseOver(mouseX: Int, mouseY: Int, iconX: Int, iconY: Int): Boolean {
        return mouseX >= iconX && mouseX < iconX + iconSize && mouseY >= iconY && mouseY < iconY + iconSize
    }

    override fun updateNarration(p_169152_: NarrationElementOutput) {

    }
}