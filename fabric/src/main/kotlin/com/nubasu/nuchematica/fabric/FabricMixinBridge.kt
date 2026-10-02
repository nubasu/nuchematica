package com.nubasu.nuchematica.fabric

import com.nubasu.nuchematica.Nuchematica
import com.nubasu.nuchematica.mover.SchematicMover
import com.nubasu.nuchematica.renderer.ClientBlockInteractHandler
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.Input
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/** Static entry points for the Java mixins; each forwards a vanilla call site to the loader-independent core. */
public object FabricMixinBridge {
    // The handler keeps its pending positions in its companion, so every instance shares one state.
    private val clientBlockInteractHandler: ClientBlockInteractHandler = ClientBlockInteractHandler()

    /** Called once a client level is fully constructed. */
    @JvmStatic
    public fun onWorldLoad(level: ClientLevel): Unit {
        Nuchematica.onWorldLoad(level)
    }

    /** Called just before the client level is replaced or cleared; [level] is null when none was loaded. */
    @JvmStatic
    public fun onWorldUnload(level: ClientLevel?): Unit {
        if (level != null) Nuchematica.onWorldUnload(level)
    }

    /** Called as the client leaves a game session, while [player] is still set; it is null when none was joined. */
    @JvmStatic
    public fun onLoggedOut(player: LocalPlayer?): Unit {
        SchematicMover.onLoggedOut(player)
    }

    /** Called right after the local player's input has been polled for the tick. */
    @JvmStatic
    public fun onMovementInput(player: Player, input: Input): Unit {
        SchematicMover.onMovementInput(player, input)
    }

    /** Called for each tick of a survival-mode block break that the attack-block event does not report. */
    @JvmStatic
    public fun onLeftClickBlock(level: Level, pos: BlockPos): Unit {
        clientBlockInteractHandler.onLeftClickBlock(level, pos)
    }
}
