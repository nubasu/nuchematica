package com.nubasu.nuchematica.e2e

import com.nubasu.nuchematica.Nuchematica
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/** Forge entry point: forwards the client tick END phase to the shared [AutomodeE2e] harness. */
@Mod.EventBusSubscriber(
    modid = Nuchematica.MODID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = [Dist.CLIENT],
)
public object AutomodeForgeE2e {
    @JvmStatic
    @SubscribeEvent
    public fun onClientTick(event: TickEvent.ClientTickEvent): Unit {
        if (event.phase != TickEvent.Phase.END) return
        AutomodeE2e.onClientTickEnd()
    }
}
