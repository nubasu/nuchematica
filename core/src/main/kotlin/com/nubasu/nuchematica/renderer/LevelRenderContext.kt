package com.nubasu.nuchematica.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Matrix4f
import net.minecraft.client.Camera
import net.minecraft.client.renderer.culling.Frustum

/** The per-frame level-rendering state that ghost drawing reads, independent of the mod loader. */
public class LevelRenderContext(
    public val poseStack: PoseStack,
    public val projectionMatrix: Matrix4f,
    public val camera: Camera,
    public val frustum: Frustum,
)
