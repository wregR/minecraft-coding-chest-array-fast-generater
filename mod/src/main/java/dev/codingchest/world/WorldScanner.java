package dev.codingchest.world;

import dev.codingchest.core.Model.*;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import java.util.*;

/** Called only from the owning world's tick thread. Never opens inventories or changes the world. */
public final class WorldScanner {
    public static BlockPos block(Pos p) { return new BlockPos(p.x(),p.y(),p.z()); }
    public static Pos pos(BlockPos p) { return new Pos(p.getX(),p.getY(),p.getZ()); }
    public record DisplaySample(Pos base,String face,String background) {}
    public static DisplaySample displaySample(World world,Pos display,String fallbackFace) {
        BlockPos at=block(display);
        var frames=world.getEntitiesByClass(ItemFrameEntity.class,new Box(at).expand(1.1),e->e.getBlockPos().equals(at)&&!e.getHeldItemStack().isEmpty());
        if(frames.size()>1)throw new IllegalArgumentException("此处有多个展示框，请选择明确的样本");
        if(frames.size()==1){NbtCompound nbt=new NbtCompound();frames.getFirst().writeNbt(nbt);Direction face=Direction.byId(nbt.getByte("Facing"));BlockPos base=at.offset(face.getOpposite());return new DisplaySample(pos(base),face.getName(),Registries.BLOCK.getId(world.getBlockState(base).getBlock()).toString());}
        return new DisplaySample(display,fallbackFace,"");
    }
    public static final class Job {
        public final World world; public final GroupConfig config; public final EncodingProfile profile;
        private final List<LayoutEntry> entries=new ArrayList<>(); private int cursor;
        public boolean cancelled;
        public Job(World world, GroupConfig config,EncodingProfile profile) { config.validate(profile);this.world=world;this.config=config;this.profile=profile; }
        public boolean tick() {
            if(cancelled)return true;
            long until=System.nanoTime()+3_000_000L;int count=0;
            while(cursor<total()&&count++<128&&System.nanoTime()<until) {
                int slice=cursor/config.lanes().size()+1,lane=cursor%config.lanes().size();cursor++;
                entries.add(scan(world,config,profile,slice,lane));
            }
            return cursor==total();
        }
        public int total() { return config.slices()*config.lanes().size(); }
        public int progress() { return cursor; }
        public List<LayoutEntry> entries() { return List.copyOf(entries); }
    }
    public static LayoutEntry scan(World world,GroupConfig g,EncodingProfile p,int slice,int lane) {
        Pos anchor=g.at(slice,lane);BlockPos base=block(anchor);
        Direction face=Direction.byName(g.lanes().get(lane).face());
        BlockPos target=face==null?base:base.offset(face);
        List<String> issues=new ArrayList<>();List<ItemData> items=new ArrayList<>();String source="block";
        if(!world.isChunkLoaded(base)||!world.isChunkLoaded(target))return new LayoutEntry(new Address(g.number(),slice,lane+(p.radix()==2?0:1)),anchor,"","unknown",List.of(),List.of("区块未加载"),false);
        BlockState state=world.getBlockState(base);
        try {
            List<ItemFrameEntity> frames=world.getEntitiesByClass(ItemFrameEntity.class,new Box(base).expand(1.1), e->e.getBlockPos().equals(target)||e.getBlockPos().equals(base));
            frames=frames.stream().filter(e->!e.getHeldItemStack().isEmpty()).toList();
            if(frames.size()>1)issues.add("多个展示框候选，请调整样本位置和显示物方向");
            else if(frames.size()==1) {
                ItemFrameEntity frame=frames.getFirst();NbtCompound data=new NbtCompound();frame.writeNbt(data);source="frame:"+data;
                add(world,frame.getHeldItemStack(),g.expandShulkers(),items);
            } else if(g.containers()) {
                BlockEntity entity=world.getBlockEntity(base);
                if(entity instanceof Inventory inventory) {
                    if(world.isClient)issues.add("容器内容未知：请安装服务端支持后重新扫描");
                    else {source="container";for(int i=0;i<inventory.size();i++)add(world,inventory.getStack(i),false,items);}
                } else if(!state.isAir())issues.add("容器排版模式的样本不是容器");
            } else {
                BlockState attached=world.getBlockState(target);
                if(face!=null&&!attached.isAir()&&!ignored(g,attached)) { state=attached;source="attached:"+target.toShortString(); }
                if(!state.isAir()&&!ignored(g,state)) {
                    Block b=state.getBlock();Item item=displayItem(state);
                    if(item==Items.AIR)issues.add("无法映射为物品，请手动修正: "+Registries.BLOCK.getId(b));
                    else add(world,new ItemStack(item),false,items);
                }
            }
        } catch(Exception ex) {issues.add("读取失败: "+ex.getMessage());}
        Map<String,ItemData> unique=new LinkedHashMap<>();items.forEach(i->unique.putIfAbsent(i.id(),i));
        return new LayoutEntry(new Address(g.number(),slice,lane+(p.radix()==2?0:1)),anchor,state.toString(),source,List.copyOf(unique.values()),List.copyOf(issues),false);
    }
    private static boolean ignored(GroupConfig g,BlockState s) {
        return s.isAir()||s.getBlock() instanceof FluidBlock||g.ignoredBlocks().contains(Registries.BLOCK.getId(s.getBlock()).toString());
    }
    public static Item displayItem(BlockState state) {
        Block block=state.getBlock();
        if(block instanceof FlowerPotBlock pot&&!pot.getContent().getDefaultState().isAir())return pot.getContent().asItem();
        return block.asItem();
    }
    private static void add(World world,ItemStack stack,boolean expand,List<ItemData> result) {
        if(stack.isEmpty())return;
        ContainerComponent contents=stack.get(DataComponentTypes.CONTAINER);
        if(expand&&stack.getItem() instanceof BlockItem b&&b.getBlock() instanceof ShulkerBoxBlock&&contents!=null&&contents.streamNonEmpty().findAny().isPresent()) {
            for(ItemStack inside:contents.iterateNonEmpty())add(world,inside,false,result);return;
        }
        result.add(new ItemData(Registries.ITEM.getId(stack.getItem()).toString(),stack.getMaxCount(),stack.encode(world.getRegistryManager()).toString(),false));
    }
}
