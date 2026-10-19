package dev.codingchest.world;

import dev.codingchest.core.*;
import dev.codingchest.core.Model.*;
import net.minecraft.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftArtifactTest {
    @BeforeAll static void bootstrap(){SharedConstants.createGameVersion();Bootstrap.initialize();}
    private List<String> ids(int n){return Registries.ITEM.stream().filter(i->i!=Items.AIR&&new ItemStack(i).getMaxCount()==64).limit(n).map(i->Registries.ITEM.getId(i).toString()).toList();}
    @Test void emptyFlowerPotKeepsItsItemWhilePottedPlantsRepresentThePlant(){
        assertEquals(Items.FLOWER_POT,WorldScanner.displayItem(Blocks.FLOWER_POT.getDefaultState()));
        assertEquals(Items.POPPY,WorldScanner.displayItem(Blocks.POTTED_POPPY.getDefaultState()));
        assertEquals(Items.OAK_SAPLING,WorldScanner.displayItem(Blocks.POTTED_OAK_SAPLING.getDefaultState()));
        assertEquals(Items.OAK_STAIRS,WorldScanner.displayItem(Blocks.OAK_STAIRS.getDefaultState().with(net.minecraft.state.property.Properties.WATERLOGGED,true)));
    }
    @Test void actualVanillaComparatorForEveryEncodableSlot(){
        for(int amount:new int[]{0,1,17,49,50}) {
            List<ItemStack> stacks=ChestFactory.inventory(new EncoderChest(new Signal("单片",8,8),1,ids(amount)));
            assertEquals(54,stacks.size());assertTrue(stacks.stream().noneMatch(ItemStack::isEmpty));
            assertEquals(5,stacks.get(50).getCount());for(int i=51;i<54;i++)assertEquals(Items.SHEARS,stacks.get(i).getItem());
            SimpleInventory inv=new SimpleInventory(stacks.toArray(ItemStack[]::new));assertEquals(2,ScreenHandler.calculateComparatorOutput(inv));
            for(int i=0;i<amount;i++){ItemStack old=inv.getStack(i);inv.setStack(i,ItemStack.EMPTY);assertEquals(1,ScreenHandler.calculateComparatorOutput(inv));inv.setStack(i,old);}
        }
    }
    @Test void fullScaleVanillaItemsExportAllBoxesAndEverySignalCopy()throws Exception {
        List<String> items=ids(800);assertEquals(800,items.size());
        var profile=new EncodingProfile(10,10,10,4,false,List.of());
        List<LayoutEntry> entries=new ArrayList<>();
        for(int i=0;i<items.size();i++)entries.add(new LayoutEntry(new Address(i/100+1,(i%100)/10+1,i%10+1),new Pos(i,0,0),"","fixture",List.of(new ItemData(items.get(i),64,"",false)),List.of(),false));
        var plan=EncodingEngine.plan(new LayoutProject(1,"full-scale-fixture","minecraft:overworld",profile,List.of(),entries));
        assertEquals(56,plan.chests().size());
        Map<String,Integer> copies=new HashMap<>();
        for(var chest:plan.chests()) {
            var inventory=ChestFactory.inventory(chest);assertEquals(54,inventory.size());assertTrue(inventory.stream().noneMatch(ItemStack::isEmpty));
            var inv=new SimpleInventory(inventory.toArray(ItemStack[]::new));assertEquals(2,ScreenHandler.calculateComparatorOutput(inv));
            for(int slot=0;slot<chest.items().size();slot++){
                ItemStack old=inv.getStack(slot);assertEquals(chest.items().get(slot),Registries.ITEM.getId(old.getItem()).toString());
                copies.merge(chest.items().get(slot),1,Integer::sum);inv.setStack(slot,ItemStack.EMPTY);
                assertEquals(1,ScreenHandler.calculateComparatorOutput(inv));inv.setStack(slot,old);
            }
        }
        assertEquals(800,copies.size());assertTrue(copies.values().stream().allMatch(n->n==3));
        var lookup=RegistryWrapper.WrapperLookup.of(Registries.REGISTRIES.stream().map(r->r.getReadOnlyWrapper()));
        var root=NbtIo.readCompressed(new ByteArrayInputStream(PlanExporter.litematic(plan,lookup,0)),NbtSizeTracker.ofUnlimitedBytes());
        var region=root.getCompound("Regions").getCompound("encoder");
        var entities=region.getList("TileEntities",NbtElement.COMPOUND_TYPE);
        assertEquals(169,entities.size());assertEquals(57,region.getCompound("Size").getInt("x"));
        assertEquals(56*54,entities.stream().mapToInt(e->((NbtCompound)e).getList("Items",10).size()).sum());
    }
    private EncoderPlan sample(int radix) {
        EncodingProfile p=new EncodingProfile(radix,10,radix==2?2:1,4,false,List.of());
        Map<String,Address> addresses=new TreeMap<>();List<String> ids=ids(21);
        for(int i=0;i<ids.size();i++)addresses.put(ids.get(i),new Address(radix==2?i%2:1,i/8+1,radix==2?i%8:i%radix+1));
        List<Integer> masks=radix==2?List.of(512,504,7):List.of();List<EncoderChest> chests=EncodingEngine.makeChests(p,addresses,masks);
        return new EncoderPlan(p,addresses,masks,chests,chests.size(),chests.stream().mapToInt(c->c.items().size()).sum());
    }
    @Test void binaryTwoLevelsNarySingleLevelAndAllRotations() {
        for(int radix:new int[]{2,10,14})for(int rotation=0;rotation<4;rotation++){
            EncoderPlan plan=sample(radix);var halves=ChestFactory.halves(plan,new BlockPos(10,20,30),rotation);
            assertEquals(plan.chests().size()*2,halves.size());assertEquals(halves.size(),halves.stream().map(ChestFactory.Half::pos).distinct().count());
            if(radix==2)assertEquals(Set.of(20,22),new HashSet<>(halves.stream().map(h->h.pos().getY()).toList()));else assertTrue(halves.stream().allMatch(h->h.pos().getY()==20));
            for(int i=0;i<halves.size();i+=2){var left=halves.get(i);var right=halves.get(i+1);assertEquals(ChestType.LEFT,left.state().get(ChestBlock.CHEST_TYPE));assertEquals(ChestType.RIGHT,right.state().get(ChestBlock.CHEST_TYPE));assertEquals(left.state().get(ChestBlock.FACING),right.state().get(ChestBlock.FACING));assertEquals(1,left.pos().getManhattanDistance(right.pos()));assertEquals(right.pos(),left.pos().offset(ChestBlock.getFacing(left.state())));assertEquals(left.pos(),right.pos().offset(ChestBlock.getFacing(right.state())));}
        }
    }
    @Test void vanillaMergedDoubleInventoryKeepsFillerInTheLastFourSlotsAfterEveryRotation(){
        for(int radix:new int[]{2,10,14})for(int rotation=0;rotation<4;rotation++){
            EncoderPlan plan=sample(radix);var halves=ChestFactory.halves(plan,BlockPos.ORIGIN,rotation);
            for(int i=0;i<halves.size();i+=2){
                var a=halves.get(i);var b=halves.get(i+1);
                var first=ChestBlock.getDoubleBlockType(a.state())==DoubleBlockProperties.Type.FIRST?a:b;
                var second=first==a?b:a;
                assertEquals(ChestType.RIGHT,first.state().get(ChestBlock.CHEST_TYPE));
                var merged=new DoubleInventory(new SimpleInventory(first.items().toArray(ItemStack[]::new)),new SimpleInventory(second.items().toArray(ItemStack[]::new)));
                var expected=ChestFactory.inventory(plan.chests().get(i/2));assertEquals(54,merged.size());
                for(int slot=0;slot<54;slot++)assertTrue(ItemStack.areEqual(expected.get(slot),merged.getStack(slot)),"Merged slot "+slot);
                assertEquals(Items.PAPER,merged.getStack(50).getItem());assertEquals(5,merged.getStack(50).getCount());
                for(int slot=51;slot<54;slot++)assertEquals(Items.SHEARS,merged.getStack(slot).getItem());
                assertEquals(2,ScreenHandler.calculateComparatorOutput(merged));
                for(int slot=0;slot<plan.chests().get(i/2).items().size();slot++){
                    ItemStack old=merged.getStack(slot);merged.setStack(slot,ItemStack.EMPTY);assertEquals(1,ScreenHandler.calculateComparatorOutput(merged));merged.setStack(slot,old);
                }
            }
        }
    }
    @Test void litematicRoundtripPreservesAllSlotsPaletteAndPackedCoordinates()throws Exception {
        RegistryWrapper.WrapperLookup lookup=RegistryWrapper.WrapperLookup.of(Registries.REGISTRIES.stream().map(r->r.getReadOnlyWrapper()));
        for(int radix:new int[]{2,10})for(int rotation=0;rotation<4;rotation++){
            EncoderPlan plan=sample(radix);byte[] bytes=PlanExporter.litematic(plan,lookup,rotation);
            NbtCompound root=NbtIo.readCompressed(new ByteArrayInputStream(bytes),NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound region=root.getCompound("Regions").getCompound("encoder");NbtList entities=region.getList("TileEntities",NbtElement.COMPOUND_TYPE),palette=region.getList("BlockStatePalette",NbtElement.COMPOUND_TYPE);
            assertEquals(plan.chests().size()*3+1,entities.size());assertEquals(54*plan.chests().size(),entities.stream().mapToInt(e->((NbtCompound)e).getList("Items",10).size()).sum());
            int sx=region.getCompound("Size").getInt("x"),sz=region.getCompound("Size").getInt("z"),bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));long[] words=region.getLongArray("BlockStates");
            for(int i=0;i<entities.size();i++){
                NbtCompound e=entities.getCompound(i);int index=(e.getInt("y")*sz+e.getInt("z"))*sx+e.getInt("x"),off=index*bits,shift=off%64;long value=words[off/64]>>>shift;if(shift+bits>64)value|=words[off/64+1]<<(64-shift);int state=(int)(value&((1L<<bits)-1));assertEquals(e.getString("id").equals("minecraft:chest")?"minecraft:chest":"minecraft:oak_wall_sign",palette.getCompound(state).getString("Name"));
                if(!e.getString("id").equals("minecraft:chest"))continue;
                NbtList items=e.getList("Items",10);for(int slot=0;slot<27;slot++){NbtCompound item=items.getCompound(slot);assertEquals(slot,item.getByte("Slot"));assertFalse(item.getString("id").isEmpty());assertTrue(item.getInt("count")>0);}
            }
        }
    }
    @Test void everyBoxHasAnAttachedSignAndRotatedStartDirectionWithNativeTextRoundtrip(){
        var lookup=RegistryWrapper.WrapperLookup.of(Registries.REGISTRIES.stream().map(r->r.getReadOnlyWrapper()));
        assertTrue(Blocks.CHEST.getDefaultState().isSolid(),"Wall signs need a solid backing");
        for(int radix:new int[]{2,10,14})for(int rotation=0;rotation<4;rotation++){
            var plan=sample(radix);var blocks=ChestFactory.blocks(plan,BlockPos.ORIGIN,rotation);
            assertEquals(plan.chests().size()*3+1,blocks.size());assertEquals(blocks.size(),blocks.stream().map(ChestFactory.Half::pos).distinct().count());
            Map<BlockPos,ChestFactory.Half> byPos=new HashMap<>();blocks.forEach(b->byPos.put(b.pos(),b));int signs=0;
            for(var block:blocks)if(block.sign()){
                signs++;BlockPos backing=block.pos().offset(block.state().get(WallSignBlock.FACING).getOpposite());
                assertTrue(byPos.get(backing).state().isOf(Blocks.CHEST));
                var sign=new net.minecraft.block.entity.SignBlockEntity(block.pos(),block.state());sign.read(ChestFactory.entityNbt(block,lookup),lookup);
                assertTrue(sign.isWaxed());for(int line=0;line<4;line++)assertEquals(block.signLines().get(line),sign.getFrontText().getMessage(line,false).getString());
            }
            assertEquals(plan.chests().size()+1,signs);assertTrue(blocks.getLast().signLines().get(1).contains(new String[]{"+X","+Z","-X","-Z"}[rotation]));
        }
    }
    @Test void exportAutomaticallyProvidesNativeSchematicAndSidecarsWithoutOverwriting(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary)throws Exception{
        var plan=sample(10);var lookup=RegistryWrapper.WrapperLookup.of(Registries.REGISTRIES.stream().map(r->r.getReadOnlyWrapper()));
        var project=new LayoutProject(1,"测试项目","minecraft:overworld",plan.profile(),List.of(),List.of());
        var a=PlanExporter.exportFiles(temporary.resolve("schematics/codingchest"),project,plan,lookup,0);
        var b=PlanExporter.exportFiles(temporary.resolve("schematics/codingchest"),project,plan,lookup,0);
        assertNotEquals(a.directory(),b.directory());assertTrue(java.nio.file.Files.exists(a.schematic()));assertTrue(java.nio.file.Files.exists(a.bundle()));
        for(String file:List.of("mapping.csv","signals.csv","materials.csv","project.json","plan.json","orientation.json"))assertTrue(java.nio.file.Files.exists(a.directory().resolve(file)));
        var nbt=NbtIo.readCompressed(a.schematic(),NbtSizeTracker.ofUnlimitedBytes());assertEquals(-1,nbt.getCompound("Regions").getCompound("encoder").getCompound("Position").getInt("x"));
        try(var files=java.nio.file.Files.list(a.directory())){assertTrue(files.noneMatch(p->p.getFileName().toString().contains(".tmp")));}
    }
}
