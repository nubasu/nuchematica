package com.nubasu.nuchematica.gui.screen

import com.mojang.blaze3d.vertex.PoseStack
import com.nubasu.nuchematica.common.PlacedBlockMap
import com.nubasu.nuchematica.gui.RenderSettings
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.TextComponent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraftforge.registries.ForgeRegistries

class FilterSettingScreen(
    private val settings: RenderSettings,
    private val parent: Screen,
    private val onSettingsChanged: () -> Unit,
) : Screen(TextComponent("Filter Setting")) {
    private val blockCounts: Map<Block, Int> = PlacedBlockMap.blockList
    private val placedBlockCounts: Map<Block, Int> = mapOf()

    private val blockPickerLiteral = TextComponent("Select Block")

    private val itemRenderer = Minecraft.getInstance().itemRenderer
    private var scrollOffset = 0
    private val rowHeight = 24
    private val visibleRows get() = (height - 50) / rowHeight

    private var isVisibleAll = true

    private companion object {

        private const val PADDING = 5

        private const val Y_START = 10

        private const val HEADER_Y = Y_START
        private const val HEADER_TEXT_HEIGHT = 15

        private const val BLOCK_HEADER_X = 35
        private const val BLOCK_HEADER_Y = HEADER_Y

        private const val REPLACE_HEADER_X = 160
        private const val REPLACE_HEADER_Y = HEADER_Y
        private const val REPLACE_BUTTON_X = REPLACE_HEADER_X
        private const val REPLACE_BUTTON_WIDTH = 60

        private const val PLACED_HEADER_X = REPLACE_BUTTON_X + REPLACE_BUTTON_WIDTH + PADDING
        private const val PLACED_HEADER_Y = HEADER_Y
        private const val PLACED_TEXT_WIDTH = 70

        private const val VISIBILITY_HEADER_X = PLACED_HEADER_X + PLACED_TEXT_WIDTH + PADDING
        private const val VISIBILITY_HEADER_Y = HEADER_Y
        private const val VISIBILITY_BUTTON_WIDTH = 60
    }

    private val buttons = mutableListOf<Button>()
    private val BLOCKS = ForgeRegistries.BLOCKS

    private fun getToggleAllLabel(): String {
        return if (isVisibleAll) {
            "All Visible"
        } else {
            "All Hidden"
        }
    }
    private fun getToggleLabel(block: Block): String {
        return if (settings.hiddenBlocks.contains(getBlockId(block))) {
            "Hidden"
        } else {
            "Visible"
        }
    }

    private fun getReplaceLabel(block: Block?): String {
        if (block == null) {
            return "Select"
        }
        return getBlockId(block) ?: "unknown"
    }

    private fun getBlockId(block: Block): String? {
        val name = BLOCKS.getKey(block)?.toString()
        return name?.split(":")?.last()
    }

    override fun init() {
        buttons.clear()

        val toggleAllButton = Button(VISIBILITY_HEADER_X, height - 30, VISIBILITY_BUTTON_WIDTH, 20, TextComponent(getToggleAllLabel())) {
            isVisibleAll = !isVisibleAll
            if (isVisibleAll) {
                settings.visibleBlocks.clear()
                settings.hiddenBlocks.clear()
                settings.visibleBlocks.addAll(blockCounts.keys.mapNotNull { getBlockId(it) })
            } else {
                settings.visibleBlocks.clear()
                settings.hiddenBlocks.clear()
                settings.hiddenBlocks.addAll(blockCounts.keys.mapNotNull { getBlockId(it) })
            }
            Minecraft.getInstance().setScreen(this)
        }

        addRenderableWidget(toggleAllButton)

        addRenderableWidget(Button(10, height - 30, 80, 20, TextComponent("Back")) {
            Minecraft.getInstance().setScreen(parent)
        })

        // items
        val visible = blockCounts.entries.toList().drop(scrollOffset).take(visibleRows)
        var y = Y_START + 20

        visible.forEach { (block, _) ->
            val replaced = settings.blockReplaceMap[getBlockId((block))]

            val replaceButton = Button(REPLACE_BUTTON_X, y + 6, REPLACE_BUTTON_WIDTH, 20, TextComponent(replaced ?: "unknown")) {
                Minecraft.getInstance().setScreen(BlockPickerGridScreen(blockPickerLiteral, this) { selected ->
                    settings.blockReplaceMap[getBlockId(block)!!] = getBlockId(selected)
                })
            }
            addRenderableWidget(replaceButton)

            val toggleButton = Button(VISIBILITY_HEADER_X, y, VISIBILITY_BUTTON_WIDTH, 20, TextComponent(getToggleLabel(block))) {
                val blockId = getBlockId(block)
                if (settings.hiddenBlocks.contains(blockId)) {
                    settings.hiddenBlocks.remove(blockId)
                    settings.visibleBlocks.add(blockId!!)
                } else {
                    settings.visibleBlocks.remove(blockId)
                    settings.hiddenBlocks.add(blockId!!)
                }
                Minecraft.getInstance().setScreen(this)
            }
            addRenderableWidget(toggleButton)

            y += rowHeight
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        val maxScroll = (blockCounts.size - visibleRows).coerceAtLeast(0)
        scrollOffset = (scrollOffset - delta.toInt()).coerceIn(0, maxScroll)
        Minecraft.getInstance().setScreen(this)
        return true
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTicks: Float) {
        renderBackground(poseStack)

        // header
        drawText(poseStack, "Block", BLOCK_HEADER_X, BLOCK_HEADER_Y)
        drawText(poseStack, "Replace To", REPLACE_HEADER_X, REPLACE_HEADER_Y)
        drawText(poseStack, "Placed", PLACED_HEADER_X, PLACED_HEADER_Y)
        drawText(poseStack, "Visibility", VISIBILITY_HEADER_X, VISIBILITY_HEADER_Y)

        // items
        val visible = blockCounts.entries.toList().drop(scrollOffset).take(visibleRows)
        var y = Y_START + 20

        visible.forEach { (block, totalCount) ->
            val stack = ItemStack(block)
            var name = ForgeRegistries.BLOCKS.getKey(block)?.toString() ?: "unknown"
            name = name.split(":").last()
            val placed = placedBlockCounts[block] ?: 0

            itemRenderer.renderAndDecorateItem(stack, 10, y)
            font.draw(poseStack, name, BLOCK_HEADER_X.toFloat(), y + 6f, 0xFFFFFF)
            font.draw(poseStack, "$placed/$totalCount", PLACED_HEADER_X.toFloat(), y + 10f, 0xAAAAAA)

            y += rowHeight
        }

        val barX = width - 10
        val barY = Y_START + 20
        val barHeight = height - 70
        val totalRows = blockCounts.size

        if (totalRows > visibleRows) {
            val sliderHeight = (barHeight * visibleRows / totalRows).coerceAtLeast(10)
            val maxScroll = (totalRows - visibleRows).coerceAtLeast(1)
            val sliderY = barY + (scrollOffset * (barHeight - sliderHeight) / maxScroll)
            // background
            fill(poseStack, barX, barY, barX + 5, barY + barHeight, 0xFFCCCCCC.toInt())
            // bar
            fill(poseStack, barX, sliderY, barX + 5, sliderY + sliderHeight, 0xFF888888.toInt())
        }

        super.render(poseStack, mouseX, mouseY, partialTicks)
    }

    override fun isPauseScreen(): Boolean = false

    public fun drawText(poseStack: PoseStack, text: String, x: Int, y: Int) {
        font.draw(poseStack, text, x.toFloat(), y.toFloat(), 0xFFFFFF)
    }
}
