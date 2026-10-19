package dev.codingchest.world;

import dev.codingchest.core.Model.*;
import dev.codingchest.core.EncoderGeometry;
import dev.codingchest.core.ChestFilling;
import net.minecraft.block.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.*;
import java.util.*;

public final class ChestFactory {
    public record Half(BlockPos pos,BlockState state,List<ItemStack> items,List<String> signLines) {
        public Half(BlockPos pos,BlockState state,List<ItemStack> items){this(pos,state,items,List.of());}
        public boolean sign(){return !signLines.isEmpty();}
    }
    public static List<ItemStack> inventory(EncoderChest chest) {
        if(chest.items().size()>50)throw new IllegalArgumentException("每箱最多50种编码物品");
        List<ItemStack> stacks=new ArrayList<>();int count16=0;
        for(int slot=0;slot<50;slot++) {
            if(slot<chest.items().size()) {
                Identifier id=Identifier.of(chest.items().get(slot));
                if(!Registries.ITEM.containsId(id))throw new IllegalArgumentException("物品未注册: "+id);
                ItemStack stack=new ItemStack(Registries.ITEM.get(id));
                if(stack.isEmpty()||!ChestFilling.supported(stack.getMaxCount()))throw new IllegalArgumentException("物品不是16或64堆叠: "+id);
                if(stack.getMaxCount()==16)count16++;
                stacks.add(stack);
            } else stacks.add(paper(1));
        }
        for(var filler:ChestFilling.tail(count16))stacks.add(filler.shears()?new ItemStack(Items.SHEARS):paper(filler.count()));
        SimpleInventory inv=new SimpleInventory(stacks.toArray(ItemStack[]::new));
        if(ScreenHandler.calculateComparatorOutput(inv)!=2)throw new IllegalStateException("初始容量不是SS2");
        for(int i=0;i<chest.items().size();i++) {
            ItemStack old=inv.getStack(i);inv.setStack(i,ItemStack.EMPTY);
            if(ScreenHandler.calculateComparatorOutput(inv)!=1)throw new IllegalStateException("吸走物品后不是SS1");
            inv.setStack(i,old);
        }
        return stacks;
    }
    private static ItemStack paper(int count) {ItemStack s=new ItemStack(Items.PAPER,count);s.set(DataComponentTypes.CUSTOM_NAME,Text.literal("ENCODER · 占位"));return s;}
    public static List<Half> halves(EncoderPlan plan,BlockPos origin,int rotation) {
        BlockRotation rot=BlockRotation.values()[Math.floorMod(rotation,4)];List<Half> out=new ArrayList<>();
        for(EncoderGeometry.Slot slot:EncoderGeometry.slots(plan)) {
            List<ItemStack> stacks=inventory(plan.chests().get(slot.chest()));
            for(int z=0;z<2;z++) {
                BlockState state=Blocks.CHEST.getDefaultState().with(ChestBlock.FACING,Direction.EAST).with(ChestBlock.CHEST_TYPE,z==0?ChestType.LEFT:ChestType.RIGHT).rotate(rot);
                Pos local=slot.local(z);
                // Vanilla combines FIRST (right) before SECOND (left), regardless of world rotation.
                int start=ChestBlock.getDoubleBlockType(state)==DoubleBlockProperties.Type.FIRST?0:27;
                out.add(new Half(origin.add(new BlockPos(local.x(),local.y(),local.z()).rotate(rot)),state,List.copyOf(stacks.subList(start,start+27))));
            }
        }
        return out;
    }
    public static NbtCompound entityNbt(Half h,RegistryWrapper.WrapperLookup registry) {
        if(h.sign()){
            SignText text=new SignText().withGlowing(true);
            for(int i=0;i<4;i++)text=text.withMessage(i,Text.literal(h.signLines().get(i)));
            NbtCompound sign=new NbtCompound();sign.putString("id","minecraft:sign");sign.putInt("x",h.pos().getX());sign.putInt("y",h.pos().getY());sign.putInt("z",h.pos().getZ());
            // Setters notify a live world; exporting must be world-independent. Use the vanilla codec instead.
            var encoded=SignText.CODEC.encodeStart(registry.getOps(NbtOps.INSTANCE),text).getOrThrow();sign.put("front_text",encoded);sign.put("back_text",encoded.copy());sign.putBoolean("is_waxed",true);return sign;
        }
        NbtCompound nbt=new NbtCompound();nbt.putString("id","minecraft:chest");
        nbt.putInt("x",h.pos().getX());nbt.putInt("y",h.pos().getY());nbt.putInt("z",h.pos().getZ());NbtList items=new NbtList();
        for(int slot=0;slot<h.items().size();slot++) {NbtCompound item=(NbtCompound)h.items().get(slot).encode(registry);item.putByte("Slot",(byte)slot);items.add(item);}
        nbt.put("Items",items);return nbt;
    }
    /** Full annotated output; halves() remains the chest-only inventory API. */
    public static List<Half> blocks(EncoderPlan plan,BlockPos origin,int rotation){
        List<Half> result=new ArrayList<>(halves(plan,origin,rotation));BlockRotation rot=BlockRotation.values()[Math.floorMod(rotation,4)];
        for(var label:EncoderGeometry.labels(plan,rotation)){
            Pos p=label.local();BlockPos position=origin.add(new BlockPos(p.x(),p.y(),p.z()).rotate(rot));
            BlockState state=Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING,Direction.byName(label.face())).rotate(rot);
            result.add(new Half(position,state,List.of(),label.lines()));
        }
        return List.copyOf(result);
    }
}
