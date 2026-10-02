package com.nubasu.nuchematica.e2e

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/** Fabric entry point: forwards the client tick END phase to the shared [AutomodeE2e] harness. */
public class AutomodeFabricE2e : ClientModInitializer {
    override fun onInitializeClient(): Unit {
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick {
                AutomodeE2e.onClientTickEnd()
            },
        )
    }
}
