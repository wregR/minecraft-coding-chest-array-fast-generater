package dev.codingchest.core;

import dev.codingchest.core.Model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeometryAndValidationTest {
    private LayoutEntry entry(int g,int s,int l,String id){return new LayoutEntry(new Address(g,s,l),new Pos(s,g,l),"fixture[waterlogged=true,facing=east]","frame",List.of(new ItemData(id,64,"",false)),List.of(),false);}
    private LayoutProject project(EncodingProfile p,List<LayoutEntry> e){return new LayoutProject(1,"fixture","minecraft:overworld",p,List.of(),e);}
    @Test void pureTestRuntimeReallyHasNoGameOrFabric(){
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("codingchest.pureTests"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("net.minecraft.item.ItemStack"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("net.fabricmc.loader.api.FabricLoader"));
    }
    @Test void binaryGeometryUsesTwoLevelsAndKeepsEachBitGroupTogether(){
        List<LayoutEntry> entries=new ArrayList<>();for(int i=0;i<150;i++)entries.add(entry(i%2,i/8+1,i%8,"test:item"+i));
        EncoderPlan p=EncodingEngine.plan(project(EncodingProfile.defaults(),entries));var slots=EncoderGeometry.slots(p);
        assertEquals(Set.of(0,1),new HashSet<>(slots.stream().map(EncoderGeometry.Slot::level).toList()));
        Map<Integer,Integer> levels=new HashMap<>();Set<Pos> occupied=new HashSet<>();
        for(var s:slots){int mask=p.chests().get(s.chest()).signal().mask();Integer previous=levels.putIfAbsent(mask,s.level());if(previous!=null)assertEquals(previous.intValue(),s.level());for(int half=0;half<2;half++)assertTrue(occupied.add(s.local(half)));assertEquals(s.level()*2,s.local(0).y());assertEquals(new Pos(0,0,1),s.local(1).sub(s.local(0)));}
        assertEquals(p.chests().size()*2,occupied.size());assertEquals(slots,EncoderGeometry.slots(p));
    }
    @Test void allNaryLayoutsAreSingleLevel(){
        for(int radix=10;radix<=14;radix++){
            EncoderPlan plan=EncodingEngine.plan(project(new EncodingProfile(radix,10,1,4,false,List.of()),List.of(entry(1,1,radix,"minecraft:stone"))));
            var slots=EncoderGeometry.slots(plan);assertEquals(3,slots.size());for(int i=0;i<3;i++){assertEquals(0,slots.get(i).level());assertEquals(i,slots.get(i).column());}
        }
    }
    @Test void lowestAndHighestBinaryAddressesAndReservedSliceZero(){
        for(int bits=10;bits<=12;bits++)for(int groups:new int[]{1,2,4,8}){
            var p=new EncodingProfile(2,bits,groups,4,false,List.of());var low=entry(0,1,0,"test:low");var high=entry(groups-1,p.maxSlices(),7,"test:high");
            var plan=EncodingEngine.plan(project(p,List.of(low,high)));assertEquals((1<<bits)-1,plan.addresses().get("test:high").binary(p));
            assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(project(p,List.of(entry(0,0,0,"test:zero")))));
            assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(project(p,List.of(entry(0,p.maxSlices()+1,0,"test:overflow")))));
        }
    }
    @Test void retainedStateDoesNotProduceSeparateCodesForTheSameItem(){
        var a=entry(0,1,0,"minecraft:oak_stairs");var b=new LayoutEntry(a.address(),a.position(),"oak_stairs[waterlogged=false,facing=west]","block",a.items(),List.of(),false);
        assertEquals(1,EncodingEngine.plan(project(EncodingProfile.defaults(),List.of(a,b))).addresses().size());
        assertNotEquals(a.blockState(),b.blockState());
    }
    @Test void unresolvedScanIssuesBlockExportUnlessExplicitlyExcluded(){
        var e=entry(0,1,0,"minecraft:stone");var unknown=new LayoutEntry(new Address(0,2,0),new Pos(2,0,0),"","unknown",List.of(),List.of("区块未加载"),false);
        assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(project(EncodingProfile.defaults(),List.of(e,unknown))));
        var excluded=new LayoutEntry(unknown.address(),unknown.position(),"","unknown",List.of(),unknown.issues(),true);
        assertEquals(1,EncodingEngine.plan(project(EncodingProfile.defaults(),List.of(e,excluded))).addresses().size());
    }
    @Test void invalidProfilesAndOverflowingSelectionVolumeAreRejected(){
        for(var p:List.of(new EncodingProfile(2,9,2,4,false,List.of()),new EncodingProfile(2,10,3,4,false,List.of()),new EncodingProfile(9,10,1,4,false,List.of()),new EncodingProfile(14,10,15,4,false,List.of())))assertThrows(IllegalArgumentException.class,p::validate);
        assertEquals(8,new Pos(-1,-1,-1).volumeTo(new Pos(0,0,0)));
        assertEquals(Long.MAX_VALUE,new Pos(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE).volumeTo(new Pos(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE)));
    }
    @Test void deterministicOptimizationAndNonContiguousMasks(){
        EncodingProfile p=new EncodingProfile(2,10,2,10,true,List.of(341,682));var entries=List.of(entry(0,1,0,"test:a"),entry(1,63,7,"test:b"));
        EncoderPlan first=EncodingEngine.plan(project(p,entries));EncoderPlan second=EncodingEngine.plan(project(p,entries.reversed()));
        assertEquals(first.chests(),second.chests());assertEquals(first.addresses(),second.addresses());EncodingEngine.verify(first);
    }
}
