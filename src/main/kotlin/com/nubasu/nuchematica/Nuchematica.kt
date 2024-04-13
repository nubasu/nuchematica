package com.nubasu.nuchematica

import com.mojang.blaze3d.vertex.*
import com.mojang.brigadier.Command
import com.mojang.logging.LogUtils
import com.nubasu.nuchematica.original.Vector3
import com.nubasu.nuchematica.original.command.TestCommand
import com.nubasu.nuchematica.original.gui.MainGui
import com.nubasu.nuchematica.original.keysetting.KeyManager
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.Material
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
import net.minecraftforge.registries.DeferredRegister
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

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus)
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus)

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
                testCommand.registerPos1(Vector3(p1.x, p1.y, p1.z))
                Command.SINGLE_SUCCESS
            }

        val pos2Builder = Commands.literal("pos2")
            .executes {
                LOGGER.info("p2")
                val p2 = Minecraft.getInstance().player!!.position()
                testCommand.registerPos2(Vector3(p2.x, p2.y, p2.z))
                Command.SINGLE_SUCCESS
            }

        event.dispatcher.register(testBuilder)
        event.dispatcher.register(pos1Builder)
        event.dispatcher.register(pos2Builder)
    }

    @SubscribeEvent
    public fun onWorldRenderLast(event: RenderLevelStageEvent) {
        testCommand.poseStack = event.poseStack
        testCommand.projectionMatrix = event.projectionMatrix

        testCommand.renderLine()
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

        // Directly reference a slf4j logger
        private val LOGGER = LogUtils.getLogger()

        // Create a Deferred Register to hold Blocks which will all be registered under the "examplemod" namespace
        private val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID)

        // Create a Deferred Register to hold Items which will all be registered under the "examplemod" namespace
        private  val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID)

        // Creates a new Block with the id "examplemod:example_block", combining the namespace and path
        private val EXAMPLE_BLOCK = BLOCKS.register(
            "example_block"
        ) {
            Block(
                BlockBehaviour.Properties.of(Material.STONE)
            )
        }

        // Creates a new BlockItem with the id "examplemod:example_block", combining the namespace and path
        private val EXAMPLE_BLOCK_ITEM = ITEMS.register<Item>(
            "example_block"
        ) {
            BlockItem(
                EXAMPLE_BLOCK.get(),
                Item.Properties()
            )
        }
    }
}

