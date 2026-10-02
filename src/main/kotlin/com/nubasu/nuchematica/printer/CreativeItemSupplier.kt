package com.nubasu.nuchematica.printer

import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState

public class CreativeItemSupplier(
    private val player: LocalPlayer,
    private val gameMode: MultiPlayerGameMode,
) : ItemSupplier {
    public override fun ensureHolding(state: BlockState): Boolean {
        val item = state.block.asItem()
        if (item == Items.AIR) return false

        val inventory = player.inventory
        val selected = inventory.selected
        if (inventory.getItem(selected).item == item) return true

        val stack = ItemStack(item)
        inventory.setItem(selected, stack)
        gameMode.handleCreativeModeItemAdd(
            stack.copy(),
            InventoryMenu.USE_ROW_SLOT_START + selected,
        )
        return true
    }
}
