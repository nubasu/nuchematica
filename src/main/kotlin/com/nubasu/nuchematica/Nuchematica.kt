package com.nubasu.nuchematica

import com.mojang.blaze3d.vertex.*
import com.mojang.brigadier.Command
import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.command.TestCommand
import com.nubasu.nuchematica.gui.MainGui
import com.nubasu.nuchematica.keysetting.KeyManager
import com.nubasu.nuchematica.renderer.SelectedRegionManager
import com.nubasu.nuchematica.utils.ChatSender
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.CreativeModeTabEvent.BuildContents
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.registries.ForgeRegistries


// The value here should match an entry in the META-INF/mods.toml file
@Mod(Nuchematica.MODID)
public class Nuchematica {
    private val testCommand = TestCommand()

    init {
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus

        // Register the commonSetup method for modloading
        modEventBus.addListener { event: FMLCommonSetupEvent ->
            commonSetup(
                event
            )
        }

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this)
        MinecraftForge.EVENT_BUS.register(KeyManager())
        MinecraftForge.EVENT_BUS.register(MainGui())

        // Register the item to a creative tab
        modEventBus.addListener { event: BuildContents ->

        }
    }

    private fun commonSetup(event: FMLCommonSetupEvent) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP")
        LOGGER.info("DIRT BLOCK >> {}", ForgeRegistries.BLOCKS.getKey(Blocks.DIRT))
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public fun onServerStarting(event: ServerStartingEvent?) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting")
    }

    @SubscribeEvent
    public fun onRegisterCommand(event: RegisterCommandsEvent) {
        val testBuilder = Commands.literal("test")
            .executes {
                LOGGER.info("test command")
                try {
                    testCommand.isRendering = !testCommand.isRendering
                } catch (e: Exception) {
                    LOGGER.info(e.stackTraceToString())
                }
                Command.SINGLE_SUCCESS
            }

        val pos1Builder = Commands.literal("pos1")
            .executes {
                LOGGER.info("p1")
                val p1 = Minecraft.getInstance().player!!.position()
                SelectedRegionManager.setFirstPosition(p1)
                Command.SINGLE_SUCCESS
            }

        val pos2Builder = Commands.literal("pos2")
            .executes {
                LOGGER.info("p2")
                val p2 = Minecraft.getInstance().player!!.position()
                SelectedRegionManager.setSecondPosition(p2)
                Command.SINGLE_SUCCESS
            }

        val blocksBuilder = Commands.literal("blocks")
            .executes {
                LOGGER.info("blocks")
                val blocks = SelectedRegionManager.getSelectedRegionBlocks()
                blocks.forEach {
                    ChatSender.send(it.block.name.toString())

                    it.blockHolder.tagKeys.forEach {
                        ChatSender.send(it.toString())
                    }
                }
                Command.SINGLE_SUCCESS
            }

        event.dispatcher.register(testBuilder)
        event.dispatcher.register(pos1Builder)
        event.dispatcher.register(pos2Builder)
        event.dispatcher.register(blocksBuilder)
    }

    @SubscribeEvent
    public fun onWorldRenderLast(event: RenderLevelStageEvent) {
        testCommand.renderLine(SelectedRegionManager.selectedRegion, event.poseStack, event.projectionMatrix)
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
    public object ClientModEvents {
        @SubscribeEvent
        public fun onClientSetup(event: FMLClientSetupEvent?) {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP")
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().user.name)
        }
    }

    public companion object {
        // Define mod id in a common place for everything to reference
        public const val MODID: String = "nuchematica"

        private val LOGGER = LogUtils.getLogger()
    }
}

