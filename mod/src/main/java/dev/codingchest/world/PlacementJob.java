package dev.codingchest.world;

import net.minecraft.block.*;
import net.minecraft.block.entity.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Server-tick transaction. Preflight completes before writing; rollback never destroys intervening edits. */
public final class PlacementJob {
    public record Snapshot(BlockPos position,BlockState oldState,BlockState expectedState,NbtCompound expectedNbt) {}
    public final ServerWorld world;private final List<ChestFactory.Half> halves;
    private final List<Snapshot> placed=new ArrayList<>();private int preflight,cursor;public boolean cancelled;
    private boolean finished;private String result="";
    public PlacementJob(ServerWorld world,List<ChestFactory.Half> halves) {
        if(halves.isEmpty()||halves.size()>18001)throw new IllegalArgumentException("生成方块数量超出限制");this.world=world;this.halves=List.copyOf(halves);
    }
    public boolean tick() {
        if(finished)return true;
        if(cancelled){rollback();result="生成取消，已回滚";return finished=true;}
        try {
            int budget=64;
            while(preflight<halves.size()&&budget-->0) {check(halves.get(preflight++).pos());}
            if(preflight<halves.size())return false;
            budget=32;
            while(cursor<halves.size()&&budget-->0) {
                ChestFactory.Half h=halves.get(cursor++);check(h.pos());BlockState old=world.getBlockState(h.pos());
                world.setBlockState(h.pos(),h.state(),Block.NOTIFY_LISTENERS|Block.FORCE_STATE);
                BlockEntity be=world.getBlockEntity(h.pos());
                if(be==null) {world.setBlockState(h.pos(),old,Block.NOTIFY_LISTENERS|Block.FORCE_STATE);throw new IllegalStateException("无法建立生成方块实体");}
                // Snapshot immediately so any subsequent initialization failure is recoverable.
                placed.add(new Snapshot(h.pos(),old,world.getBlockState(h.pos()),be.createNbtWithIdentifyingData(world.getRegistryManager())));
                try{if(h.sign())be.read(ChestFactory.entityNbt(h,world.getRegistryManager()),world.getRegistryManager());
                    else if(be instanceof ChestBlockEntity chest){for(int i=0;i<27;i++)chest.setStack(i,h.items().get(i).copy());}
                    else throw new IllegalStateException("生成方块类型不符");be.markDirty();
                }finally{placed.set(placed.size()-1,new Snapshot(h.pos(),old,world.getBlockState(h.pos()),be.createNbtWithIdentifyingData(world.getRegistryManager())));}
                world.updateListeners(h.pos(),old,h.state(),Block.NOTIFY_LISTENERS);
            }
            if(cursor==halves.size()){for(Snapshot s:placed)world.updateNeighbors(s.position(),s.expectedState().getBlock());result="生成完成："+halves.stream().filter(h->!h.sign()).count()/2+" 个双箱及 "+halves.stream().filter(ChestFactory.Half::sign).count()+" 块标牌，可撤销";return finished=true;}
        } catch(Exception ex) {int conflicts=rollback();result="生成失败，已回滚："+ex.getMessage()+(conflicts>0?"；回滚冲突 "+conflicts:"");finished=true;}
        return finished;
    }
    private void check(BlockPos p) {
        if(!world.isChunkLoaded(p))throw new IllegalArgumentException("目标区块未加载: "+p.toShortString());
        if(world.isOutOfHeightLimit(p)||!world.getWorldBorder().contains(p))throw new IllegalArgumentException("目标超出世界边界");
        if(!world.getBlockState(p).isAir())throw new IllegalArgumentException("目标不是空气: "+p.toShortString());
    }
    public int rollback() {int conflicts=undo(world,placed);placed.clear();return conflicts;}
    public List<Snapshot> snapshots(){return List.copyOf(placed);}
    public String result(){return result;}
    public String progress(){return preflight<halves.size()?"检查目标 "+preflight+"/"+halves.size():"放置 "+cursor+"/"+halves.size();}
    public record UndoResult(int restored,List<Snapshot> conflicts) {
        public String summary(){return "已撤销 "+restored+" 个生成方块；跳过冲突 "+conflicts.size()+"处"+(conflicts.isEmpty()?"":"："+conflicts.stream().limit(8).map(s->s.position().toShortString()).toList()+"（可解决冲突后重试）");}
    }
    public static int undo(ServerWorld world,List<Snapshot> snapshots) {return undoDetailed(world,snapshots).conflicts().size();}
    public static UndoResult undoDetailed(ServerWorld world,List<Snapshot> snapshots) {
        // Verify all halves first: removing one half must not change the comparison for its partner.
        Map<BlockPos,Snapshot> all=new HashMap<>();snapshots.forEach(s->all.put(s.position(),s));
        Set<BlockPos> safe=new HashSet<>();
        for(Snapshot s:snapshots) {
            if(!world.isChunkLoaded(s.position()))continue;
            BlockEntity be=world.getBlockEntity(s.position());
            if(!world.getBlockState(s.position()).equals(s.expectedState())||be==null||!be.createNbtWithIdentifyingData(world.getRegistryManager()).equals(s.expectedNbt()))continue;
            safe.add(s.position());
        }
        // Chests and attached signs form an atomic connected component. A changed sign
        // must not lose its supporting chest; a changed chest must retain its labels.
        boolean changed;do{changed=false;for(Snapshot s:snapshots){BlockPos partner=null;
            if(s.expectedState().getBlock() instanceof ChestBlock)partner=s.position().offset(ChestBlock.getFacing(s.expectedState()));
            else if(s.expectedState().getBlock() instanceof WallSignBlock)partner=s.position().offset(s.expectedState().get(WallSignBlock.FACING).getOpposite());
            if(partner!=null&&all.containsKey(partner)&&(!safe.contains(partner)||!safe.contains(s.position()))){changed|=safe.remove(partner);changed|=safe.remove(s.position());}
        }}while(changed);
        List<Snapshot> conflicts=new ArrayList<>();
        // Remove signs first to avoid item drops when their supporting chest goes away.
        List<Snapshot> ordered=new ArrayList<>(snapshots);ordered.sort(Comparator.comparing(s->!(s.expectedState().getBlock() instanceof WallSignBlock)));
        for(Snapshot s:ordered) {if(!safe.contains(s.position())){conflicts.add(s);continue;}world.removeBlockEntity(s.position());world.setBlockState(s.position(),s.oldState(),Block.NOTIFY_LISTENERS|Block.FORCE_STATE);}
        return new UndoResult(safe.size(),List.copyOf(conflicts));
    }
}
