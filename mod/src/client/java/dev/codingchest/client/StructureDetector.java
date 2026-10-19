package dev.codingchest.client;

import dev.codingchest.core.Model.*;
import dev.codingchest.world.WorldScanner;
import net.minecraft.block.*;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import java.util.*;

/** Suggestions are occupancy-based, never an authoritative interpretation of an arbitrary building. */
final class StructureDetector {
    final World world;final Pos min,max;final Set<String> ignored;private final Set<Pos> occupied=new HashSet<>();
    private int x,y,z,visited;private boolean done;
    private final Pos referenceOrigin;private final List<LaneAnchor> referenceLanes;private final Map<Pos,String> frameFaces=new HashMap<>();
    StructureDetector(World world,Pos min,Pos max,Set<String> ignored,Pos referenceOrigin,List<LaneAnchor> referenceLanes) {
        if(min.volumeTo(max)>2_000_000)throw new IllegalArgumentException("选区超过200万方块");
        this.world=world;this.min=min;this.max=max;this.ignored=ignored;this.referenceOrigin=referenceOrigin;this.referenceLanes=referenceLanes;x=min.x();y=min.y();z=min.z();
        for(ItemFrameEntity e:world.getEntitiesByClass(ItemFrameEntity.class,new Box(min.x(),min.y(),min.z(),max.x()+1,max.y()+1,max.z()+1),f->!f.getHeldItemStack().isEmpty())){var s=WorldScanner.displaySample(world,WorldScanner.pos(e.getBlockPos()),"none");occupied.add(s.base());frameFaces.put(s.base(),s.face());}
    }
    boolean tick() {
        long until=System.nanoTime()+3_000_000;int budget=4096;
        while(!done&&budget-->0&&System.nanoTime()<until) {
            BlockPos p=new BlockPos(x,y,z);if(!world.isChunkLoaded(p))throw new IllegalArgumentException("选区包含未加载区块，请靠近后重试");
            BlockState state=world.getBlockState(p);
            if(!state.isAir()&&!(state.getBlock() instanceof FluidBlock)&&state.getBlock().asItem()!=Items.AIR&&!ignored.contains(Registries.BLOCK.getId(state.getBlock()).toString()))occupied.add(WorldScanner.pos(p));
            visited++;if(++x>max.x()){x=min.x();if(++y>max.y()){y=min.y();if(++z>max.z())done=true;}}
        }return done;
    }
    String progress(){return "推测结构 "+visited+"/"+min.volumeTo(max);}
    record Candidate(Pos first,Pos step,int slices,List<LaneAnchor> lanes,int score,String label) {}
    List<Candidate> candidates() {
        List<Candidate> result=new ArrayList<>();
        if(referenceOrigin!=null&&!referenceLanes.isEmpty()){
            for(boolean alongX:new boolean[]{true,false})for(int sign:new int[]{1,-1})for(int spacing=1;spacing<=16;spacing++){
                int coord=alongX?referenceOrigin.x():referenceOrigin.z();int extent=alongX?(sign>0?max.x()-coord:coord-min.x()):(sign>0?max.z()-coord:coord-min.z());int slices=extent/spacing+1;if(slices<2)continue;
                Pos step=alongX?new Pos(sign*spacing,0,0):new Pos(0,0,sign*spacing);int matched=0,checked=0;
                for(int s=1;s<Math.min(slices,16);s++)for(LaneAnchor a:referenceLanes){checked++;if(occupied.contains(referenceOrigin.add(a.offset()).add(step.scale(s))))matched++;}
                int score=matched*1000/checked;if(score>=500)result.add(new Candidate(referenceOrigin,step,slices,referenceLanes,score,"按已采样首片 "+(alongX?"X":"Z")+(sign>0?"+":"-")+" 间距"+spacing+"，重复度"+score/10+"%"));
            }
            result.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(c->Math.abs(c.step.x())+Math.abs(c.step.z())));return result.stream().limit(12).toList();
        }
        for(boolean alongX:new boolean[]{true,false})for(int sign:new int[]{1,-1}) {
            TreeMap<Integer,List<Pos>> planes=new TreeMap<>();for(Pos p:occupied)planes.computeIfAbsent(alongX?p.x():p.z(),k->new ArrayList<>()).add(p);
            if(planes.isEmpty())continue;
            List<Integer> coords=new ArrayList<>(planes.keySet());if(sign<0)Collections.reverse(coords);
            for(int coord:coords) {
                List<Pos> points=planes.get(coord);
                if(points.size()<2||points.size()>14)continue;
                points.sort(Comparator.comparingInt((Pos p)->alongX?p.z():p.x()).thenComparing(Comparator.comparingInt(Pos::y).reversed()));
                Pos first=points.getFirst();
                for(int spacing=1;spacing<=16;spacing++) {
                    Pos step=alongX?new Pos(sign*spacing,0,0):new Pos(0,0,sign*spacing);
                    int extent=alongX?(sign>0?max.x()-coord:coord-min.x()):(sign>0?max.z()-coord:coord-min.z());int slices=extent/spacing+1;
                    if(slices<2)continue;
                    int matched=0,checked=0;
                    for(int s=1;s<Math.min(slices,16);s++)for(Pos p:points){checked++;if(occupied.contains(p.add(step.scale(s))))matched++;}
                    int score=checked==0?0:matched*1000/checked;
                    if(score<500)continue;
                    result.add(new Candidate(first,step,slices,points.stream().map(p->new LaneAnchor(p.sub(first),frameFaces.getOrDefault(p,"none"))).toList(),score,(alongX?"X":"Z")+(sign>0?"+":"-")+" 间距"+spacing+"，"+points.size()+"水道，重复度"+score/10+"%"));
                }
                // Only the first plausible cross-section from each inner face is used.
                break;
            }
        }
        result.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(c->Math.abs(c.step.x())+Math.abs(c.step.z())));
        return result.stream().limit(12).toList();
    }
}
