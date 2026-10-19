package dev.codingchest.client;

import dev.codingchest.core.Model.*;
import dev.codingchest.core.EncoderGeometry;
import dev.codingchest.world.WorldScanner;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.*;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.*;

final class PreviewRenderer {
    private static final BufferAllocator allocator=new BufferAllocator(131072);
    private static final VertexConsumerProvider.Immediate buffers=VertexConsumerProvider.immediate(allocator);
    static void close(){allocator.close();}
    static void render(WorldRenderContext context) {
        CodingChestClient c=CodingChestClient.INSTANCE;if(c==null||context.matrixStack()==null||context.consumers()==null)return;
        MatrixStack matrices=context.matrixStack();Vec3d camera=context.camera().getPos();matrices.push();matrices.translate(-camera.x,-camera.y,-camera.z);
        VertexConsumer lines=buffers.getBuffer(RenderLayer.getLines());
        for(var region:c.regions){if(region.id().equals(c.activeRegionId))continue;Pos min=region.min(),max=region.max();if(new BlockPos(min.x(),min.y(),min.z()).getSquaredDistance(camera)>192*192)continue;WorldRenderer.drawBox(matrices,buffers.getBuffer(RenderLayer.getLines()),new Box(min.x(),min.y(),min.z(),max.x()+1,max.y()+1,max.z()+1),.35f,.5f,.85f,.5f);}
        if(c.corner1!=null)WorldRenderer.drawBox(matrices,buffers.getBuffer(RenderLayer.getLines()),new Box(WorldScanner.block(c.corner1)).expand(.02),1f,.8f,.1f,1f);
        if(c.corner2!=null)WorldRenderer.drawBox(matrices,buffers.getBuffer(RenderLayer.getLines()),new Box(WorldScanner.block(c.corner2)).expand(.02),1f,.25f,.7f,1f);
        if(c.corner1!=null&&c.corner2!=null) {Pos min=c.min(),max=c.max();WorldRenderer.drawBox(matrices,lines,new Box(min.x(),min.y(),min.z(),max.x()+1,max.y()+1,max.z()+1),0.2f,0.85f,1f,1f);}
        if(c.showLayout&&c.project!=null) {int count=0;for(LayoutEntry entry:c.project.entries()){
            BlockPos at=WorldScanner.block(entry.position());if(at.getSquaredDistance(camera)>128*128||count++>500)continue;
            float r=entry.issues().isEmpty()?0.2f:1f,g=entry.issues().isEmpty()?0.9f:0.15f;
            WorldRenderer.drawBox(matrices,buffers.getBuffer(RenderLayer.getLines()),new Box(at).expand(.005),r,g,.3f,1f);
            if(at.getSquaredDistance(camera)<32*32)text(context,matrices,at.toCenterPos().add(0,.7,0),entry.address().display(c.project.profile()),entry.excluded()?0x888888:0xFFFFFF);
        }}
        if(c.preview&&c.plan!=null) {
            BlockRotation rot=BlockRotation.values()[c.rotation];
            for(var label:EncoderGeometry.labels(c.plan,c.rotation)){Pos p=label.local();BlockPos at=WorldScanner.block(c.previewOrigin).add(new BlockPos(p.x(),p.y(),p.z()).rotate(rot));if(at.getSquaredDistance(camera)<160*160){WorldRenderer.drawBox(matrices,buffers.getBuffer(RenderLayer.getLines()),new Box(at).contract(.12),1f,.8f,.2f,1f);if(label.chest()<0&&at.getSquaredDistance(camera)<48*48)text(context,matrices,at.toCenterPos().add(0,1.2,0),String.join(" · ",label.lines()),0xFFFF66);}}
            for(EncoderGeometry.Slot slot:EncoderGeometry.slots(c.plan)) {
                int i=slot.chest();Pos local=slot.local(0);
                BlockPos at=WorldScanner.block(c.previewOrigin).add(new BlockPos(local.x(),local.y(),local.z()).rotate(rot));if(at.getSquaredDistance(camera)>160*160)continue;
                boolean occupied=false;
                for(int z=0;z<2;z++) {Pos pos=slot.local(z);BlockPos p=WorldScanner.block(c.previewOrigin).add(new BlockPos(pos.x(),pos.y(),pos.z()).rotate(rot));occupied|=!c.mc().world.getBlockState(p).isAir();WorldRenderer.drawBox(matrices,buffers.getBuffer(RenderLayer.getLines()),new Box(p).contract(.0625),occupied?1f:.3f,occupied?.15f:.8f,.85f,1f);}
                EncoderChest chest=c.plan.chests().get(i);if(at.getSquaredDistance(camera)<48*48)text(context,matrices,at.toCenterPos().add(0,1,0),"#"+(i+1)+" "+chest.signal().label()+" ("+chest.items().size()+")",occupied?0xFF7777:0xAADDFF);
            }
        }
        buffers.draw();matrices.pop();
    }
    private static void text(WorldRenderContext ctx,MatrixStack matrices,Vec3d at,String text,int color) {
        // Match 1.21's EntityRenderer label orientation: reversing X culls the glyph front face.
        matrices.push();matrices.translate(at.x,at.y,at.z);matrices.multiply(ctx.camera().getRotation());matrices.scale(.02f,-.02f,.02f);
        var renderer=CodingChestClient.INSTANCE.mc().textRenderer;float x=-renderer.getWidth(text)/2f;renderer.draw(text,x,0,(color&0xFFFFFF)|0x44000000,false,matrices.peek().getPositionMatrix(),buffers,TextRenderer.TextLayerType.SEE_THROUGH,0x55000000,15728880);renderer.draw(text,x,0,color|0xFF000000,false,matrices.peek().getPositionMatrix(),buffers,TextRenderer.TextLayerType.NORMAL,0,15728880);matrices.pop();
    }
}
