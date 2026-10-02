package com.nubasu.nuchematica.printer

import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.VanillaPackResources
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.server.packs.resources.PreparableReloadListener
import net.minecraft.tags.TagKey
import net.minecraft.tags.TagManager
import net.minecraft.util.profiling.InactiveProfiler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Binds the vanilla data-pack tags to the static registries until [close].
 *
 * The headless [net.minecraft.server.Bootstrap] leaves every tag unbound, so `BlockState.is(TagKey)` is
 * false for all tags, while a running game binds them from the server data or the update-tags packet.
 * Tests whose outcome depends on a tag (plants survive on `BlockTags.DIRT`) bind them here to observe the
 * in-game result, and close the instance to restore the unbound state other tests rely on.
 */
internal class BoundVanillaTags private constructor(private val bound: List<Registry<*>>) : AutoCloseable {
    override fun close() {
        bound.forEach { registry -> registry.resetTags() }
    }

    internal companion object {
        internal fun bind(): BoundVanillaTags {
            val pack = VanillaPackResources(ServerPacksSource.BUILT_IN_METADATA, "minecraft")
            MultiPackResourceManager(PackType.SERVER_DATA, listOf(pack)).use { resources ->
                val access = RegistryAccess.BUILTIN.get()
                val manager = TagManager(access)
                val direct = Executor { task -> task.run() }
                val barrier = object : PreparableReloadListener.PreparationBarrier {
                    override fun <T> wait(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)
                }
                manager.reload(barrier, resources, InactiveProfiler.INSTANCE, InactiveProfiler.INSTANCE, direct, direct).join()
                return BoundVanillaTags(manager.result.map { result -> bindResult(access, result) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> bindResult(access: RegistryAccess, result: TagManager.LoadResult<T>): Registry<T> {
            val registry = access.registryOrThrow(result.key()) as Registry<T>
            val tags = HashMap<TagKey<T>, List<Holder<T>>>()
            for ((id, tag) in result.tags()) {
                tags[TagKey.create(result.key(), id)] = tag.values
            }
            registry.bindTags(tags)
            return registry
        }
    }
}
