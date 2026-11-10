package com.zurrtum.create.client.flywheel.lib.model.baked;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.zurrtum.create.client.flywheel.api.material.Material;
import com.zurrtum.create.client.flywheel.api.model.Mesh;
import com.zurrtum.create.client.flywheel.lib.material.Materials;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bakes an item stack's model into meshes. 26.3 items no longer draw through a buffer source, so the stack's render
 * state is submitted to a collector that keeps its plain item quads. An item that submits anything else (a special
 * renderer's model, a glint, an outline) can't be baked: {@link #canBuffer} says so, and it is left to vanilla.
 */
public class BakedItemModelBufferer {
    static final List<RenderType> CHUNK_LAYERS = List.of(
        Sheets.cutoutBlockItemSheet(),
        Sheets.cutoutItemSheet(),
        Sheets.translucentBlockItemSheet(),
        Sheets.translucentItemSheet()
    );

    public static void bufferItemStack(
        ItemStack stack,
        BlockAndTintGetter level,
        ItemDisplayContext displayContext,
        ResultConsumer resultConsumer,
        MeshResultConsumer meshResultConsumer
    ) {
        ThreadLocalObjects objects = THREAD_LOCAL_OBJECTS.get();
        ClientLevel world = level instanceof ClientLevel clientWorld ? clientWorld : null;
        ItemStackRenderState state = objects.state;
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(state, stack, displayContext, world, null, 0);
        QuadCollector collector = objects.collector;
        collector.begin(true);
        for (ItemMeshEmitter emitter : objects.emitters) {
            emitter.prepare(
                resultConsumer,
                (renderType, mesh, translucent) -> meshResultConsumer.accept(
                    renderType,
                    Materials.TRANSLUCENT_ITEM_ENTITY_ITEM,
                    mesh,
                    translucent
                )
            );
        }
        state.submit(new PoseStack(), collector, 0, OverlayTexture.NO_OVERLAY, 0);
        for (ItemMeshEmitter emitter : objects.emitters) {
            emitter.end();
        }
        state.clear();
    }

    /** True if everything the given (already updated) render state draws is plain item quads that can be baked. */
    public static boolean canBuffer(ItemStackRenderState state) {
        QuadCollector collector = THREAD_LOCAL_OBJECTS.get().collector;
        collector.begin(false);
        state.submit(new PoseStack(), collector, 0, OverlayTexture.NO_OVERLAY, 0);
        return collector.bakeable;
    }

    public interface ResultConsumer {
        void accept(RenderType renderType, boolean shaded, MeshData data);
    }

    public interface MeshResultConsumer {
        void accept(RenderType renderType, Material material, Mesh mesh, boolean translucent);
    }

    private static final ThreadLocal<ThreadLocalObjects> THREAD_LOCAL_OBJECTS = ThreadLocal.withInitial(
        ThreadLocalObjects::new);

    public static Map<RenderType, Integer> getChunkLayers() {
        return THREAD_LOCAL_OBJECTS.get().chunkLayers;
    }

    private static class ThreadLocalObjects {
        public final ItemStackRenderState state = new ItemStackRenderState();
        public final Map<RenderType, Integer> chunkLayers = new HashMap<>();
        public final List<ItemMeshEmitter> emitters = new ArrayList<>();
        public final QuadCollector collector = new QuadCollector(this);

        {
            for (int i = 0, size = CHUNK_LAYERS.size(); i < size; i++) {
                RenderType renderType = CHUNK_LAYERS.get(i);
                chunkLayers.put(renderType, i);
                emitters.add(new ItemMeshEmitter(renderType));
            }
        }
    }

    /** Keeps the plain item quads a render state submits; anything else marks the item as not bakeable. */
    private static class QuadCollector implements SubmitNodeCollector {
        private final ThreadLocalObjects objects;
        private final QuadInstance quadInstance = new QuadInstance();
        private boolean emit;
        boolean bakeable;

        QuadCollector(ThreadLocalObjects objects) {
            this.objects = objects;
        }

        void begin(boolean emit) {
            this.emit = emit;
            bakeable = true;
        }

        @Override
        public void submitItem(
            PoseStack poseStack,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            ItemQuads quads,
            ItemStackRenderState.FoilType foilType
        ) {
            if (foilType != ItemStackRenderState.FoilType.NONE || outlineColor != 0) {
                bakeable = false;
            }
            quadInstance.setLightCoords(lightCoords);
            quadInstance.setOverlayCoords(overlayCoords);
            for (BakedQuad quad : quads.all()) {
                BakedQuad.MaterialInfo info = quad.materialInfo();
                Integer layer = objects.chunkLayers.get(info.itemRenderType());
                if (layer == null) {
                    bakeable = false;
                    continue;
                }
                if (!emit) {
                    continue;
                }
                int tint = info.tintIndex();
                quadInstance.setColor(tint >= 0 && tint < tintLayers.length ? tintLayers[tint] : -1);
                objects.emitters.get(layer).putBakedQuad(poseStack.last(), quad, quadInstance);
            }
        }

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
            bakeable = false;
        }

        @Override
        public void submitNameTag(
            PoseStack poseStack,
            Vec3 pos,
            int offset,
            Component name,
            boolean seeThrough,
            int lightCoords,
            CameraRenderState camera
        ) {
            bakeable = false;
        }

        @Override
        public void submitText(
            PoseStack poseStack,
            float x,
            float y,
            FormattedCharSequence text,
            boolean dropShadow,
            Font.DisplayMode displayMode,
            int lightCoords,
            int color,
            int backgroundColor,
            int outlineColor
        ) {
            bakeable = false;
        }

        @Override
        public void submitTextBackground(
            PoseStack poseStack,
            float x0,
            float y0,
            float x1,
            float y1,
            int color,
            Font.DisplayMode displayMode,
            int lightCoords
        ) {
            bakeable = false;
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
            bakeable = false;
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
            bakeable = false;
        }

        @Override
        public <S> void submitModel(
            Model<? super S> model,
            S renderState,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            UvMapping uvMapping,
            int outlineColor
        ) {
            bakeable = false;
        }

        @Override
        public <S> void submitCrumblingOverlay(
            Model<? super S> model,
            S renderState,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
        ) {
            bakeable = false;
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int order) {
            bakeable = false;
        }

        @Override
        public void submitBlockModel(
            PoseStack poseStack,
            RenderType renderType,
            List<BlockStateModelPart> parts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int outlineColor
        ) {
            bakeable = false;
        }

        @Override
        public void submitBreakingBlockModel(
            PoseStack poseStack,
            List<BlockStateModelPart> parts,
            int progress,
            boolean flag
        ) {
            bakeable = false;
        }

        @Override
        public void submitShapeOutline(
            PoseStack poseStack,
            VoxelShape shape,
            RenderType renderType,
            int color,
            float width,
            boolean flag
        ) {
            bakeable = false;
        }

        @Override
        public void submitCustomGeometry(
            PoseStack poseStack,
            RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer renderer
        ) {
            bakeable = false;
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState particles) {
            bakeable = false;
        }

        @Override
        public void submitGizmoPrimitives(
            DrawableGizmoPrimitives.Group group,
            CameraRenderState camera,
            boolean flag
        ) {
            bakeable = false;
        }
    }
}
