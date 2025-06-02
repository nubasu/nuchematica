package com.nubasu.nuchematica.gui.screen

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.renderer.SchematicRenderManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.TextComponent
import java.io.File

public class SchematicListScreen : Screen(TextComponent("Schematics")) {
    private val schematicsDir = File(Minecraft.getInstance().gameDirectory.absolutePath, "/schematics")
    private val schematicFiles = mutableListOf<File>()
    private var scrollOffset = 0
    private val rowHeight = 20
    private val paddingTopBottom = 32
    private var visibleRows = 15

    override fun init() {
        schematicFiles.clear()
        if (schematicsDir.exists() && schematicsDir.isDirectory) {
            schematicsDir.listFiles { _, name -> name.endsWith(".schematic") }?.let {
                schematicFiles.addAll(it)
            }
        }
        visibleRows = (this.height - paddingTopBottom) / rowHeight
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        scrollOffset = (scrollOffset - delta.toInt()).coerceIn(0, (schematicFiles.size - visibleRows).coerceAtLeast(0))
        return true
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(poseStack)

        val startY = paddingTopBottom / 2
        val visible = schematicFiles.drop(scrollOffset).take(visibleRows)

        visible.forEachIndexed { index, file ->
            val y = startY + index * rowHeight
            drawString(poseStack, font, file.name, 20, y, 0xFFFFFF)

            if (mouseX in 20..300 && mouseY in y until (y + rowHeight)) {
                fill(poseStack, 15, y, width - 15, y + rowHeight, 0x80FFFFFF.toInt())
            }
        }

        // scroll bar
        val scrollbarX = width - 10
        val scrollbarY = startY
        val scrollbarHeight = visibleRows * rowHeight
        val totalHeight = schematicFiles.size * rowHeight

        fill(poseStack, scrollbarX, scrollbarY, scrollbarX + 5, scrollbarY + scrollbarHeight, 0xFFAAAAAA.toInt())

        if (totalHeight > scrollbarHeight) {
            val sliderHeight = (scrollbarHeight * scrollbarHeight.toFloat() / totalHeight).toInt().coerceAtLeast(10)
            val sliderY = scrollbarY + (scrollOffset * (scrollbarHeight - sliderHeight) / (schematicFiles.size - visibleRows).coerceAtLeast(1))

            // slider
            fill(poseStack, scrollbarX, sliderY, scrollbarX + 5, sliderY + sliderHeight, 0xFF666666.toInt())
        }

        super.render(poseStack, mouseX, mouseY, partialTicks)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val startY = paddingTopBottom / 2
        val index = ((mouseY.toInt() - startY) / rowHeight)

        val actualIndex = scrollOffset + index
        if (index in 0 until visibleRows && actualIndex in schematicFiles.indices) {
            val file = schematicFiles[actualIndex]
            LogUtils.getLogger().info("clicked: ${file.name}")
            SchematicRenderManager.loadRenderBlocks(file.name)
            SchematicRenderManager.isRendering = true
            SchematicRenderManager.initialize()
            onClose()
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun isPauseScreen(): Boolean = true
}