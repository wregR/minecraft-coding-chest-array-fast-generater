package dev.codingchest.core;

import dev.codingchest.core.Model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EncodingEngineTest {
    private LayoutEntry entry(Address a,String... ids){return new LayoutEntry(a,new Pos(a.slice(),a.group(),a.lane()),"state","fixture",Arrays.stream(ids).map(id->new ItemData(id,64,"",false)).toList(),List.of(),false);}
    private LayoutProject project(EncodingProfile p,List<LayoutEntry> entries){return new LayoutProject(1,"test","minecraft:overworld",p,List.of(),entries);}
    @Test void binaryFieldsAndCapacity() {
        for(int groups:new int[]{1,2,4,8})for(int bits:new int[]{10,11,12}) {
            EncodingProfile p=new EncodingProfile(2,bits,groups,4,false,List.of());List<LayoutEntry> entries=new ArrayList<>();
            for(int g=0;g<groups;g++)for(int lane=0;lane<8;lane++)entries.add(entry(new Address(g,1,lane),"test:item_"+g+"_"+lane));
            EncoderPlan plan=EncodingEngine.plan(project(p,entries));
            assertEquals((1<<(bits-p.groupBits()-3))-1,p.maxSlices());
            assertEquals(8,new Address(0,1,0).binary(p));assertEquals(((groups-1)<<(p.sliceBits()+3))|8|7,new Address(groups-1,1,7).binary(p));
            EncodingEngine.verify(plan);assertTrue(plan.chests().size()<=plan.baselineChests());
        }
    }
    @Test void naryUsesActualSignalsNotPositionalDigits() {
        for(int radix=10;radix<=14;radix++) {
            final int n=radix;
            EncodingProfile p=new EncodingProfile(radix,10,radix,4,false,List.of());Address a=new Address(1,1,radix);
            EncoderPlan plan=EncodingEngine.plan(project(p,List.of(entry(a,"minecraft:stone"))));
            assertEquals(3,plan.chests().size());assertTrue(plan.chests().stream().anyMatch(c->c.signal().field().equals("水道")&&c.signal().value()==n));
            assertEquals("[1 | 1 | "+radix+"]",a.display(p));
        }
    }
    @Test void exactCapacityBoundariesNeverTruncate() {
        EncodingProfile p=new EncodingProfile(10,10,1,4,false,List.of());
        for(int amount:new int[]{49,50,51,100,101}) {
            String[] ids=new String[amount];for(int i=0;i<amount;i++)ids[i]="test:item_"+i;
            EncoderPlan plan=EncodingEngine.plan(project(p,List.of(entry(new Address(1,1,1),ids))));
            assertEquals(3*((amount+49)/50),plan.chests().size());
            assertEquals(amount*3,plan.copies());assertTrue(plan.chests().stream().allMatch(c->c.items().size()<=50));
        }
    }
    @Test void fullScaleNarySplitsEverySignalPoolWithoutABoxCountLimit() {
        EncodingProfile p=new EncodingProfile(10,10,10,4,false,List.of());
        for(int amount:new int[]{800,1000}) {
            List<LayoutEntry> entries=new ArrayList<>();
            for(int i=0;i<amount;i++)entries.add(entry(new Address(i/100+1,(i%100)/10+1,i%10+1),"test:full_"+i));
            EncoderPlan plan=EncodingEngine.plan(project(p,entries));
            // 800: group pools 8*ceil(100/50), slice and lane pools each 10*ceil(80/50).
            // 1000: all thirty signal pools have 100 items, hence two boxes per pool.
            assertEquals(amount==800?56:60,plan.chests().size());
            assertEquals(amount,plan.addresses().size());assertEquals(amount*3,plan.copies());
            for(String id:plan.addresses().keySet()) {
                var triggered=plan.chests().stream().filter(c->c.items().contains(id)).toList();
                assertEquals(Set.of("分组","单片","水道"),new HashSet<>(triggered.stream().map(c->c.signal().field()).toList()));
                assertEquals(3,triggered.size());
            }
            assertTrue(plan.chests().stream().allMatch(c->c.items().size()<=50));
            EncodingEngine.verify(plan);
        }
    }
    @Test void sameAddressMultipleItemsAndDuplicates() {
        EncodingProfile p=EncodingProfile.defaults();Address a=new Address(0,1,0);
        EncoderPlan plan=EncodingEngine.plan(project(p,List.of(entry(a,"minecraft:stone","minecraft:dirt","minecraft:stone"))));
        assertEquals(2,plan.addresses().size());
        assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(project(p,List.of(entry(a,"minecraft:stone"),entry(new Address(0,2,0),"minecraft:stone")))));
    }
    @Test void emptyPositionsDontShiftOtherAddressesAndUnsupportedRequireExclusion() {
        EncodingProfile p=EncodingProfile.defaults();Address a=new Address(0,3,0);
        EncoderPlan plan=EncodingEngine.plan(project(p,List.of(entry(new Address(0,1,0)),entry(a,"minecraft:stone"))));assertEquals(a,plan.addresses().get("minecraft:stone"));
        LayoutEntry e=new LayoutEntry(a,new Pos(0,0,0),"","",List.of(new ItemData("minecraft:shears",1,"",false),new ItemData("minecraft:stone",64,"",false)),List.of(),false);
        assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(project(p,List.of(e))));
        LayoutEntry excluded=new LayoutEntry(a,e.position(),"","",List.of(new ItemData("minecraft:shears",1,"",true),new ItemData("minecraft:stone",64,"",false)),List.of(),false);
        assertEquals(a,EncodingEngine.plan(project(p,List.of(excluded))).addresses().get("minecraft:stone"));
    }
    @Test void supportsMixedStacksWithIdenticalAddressesAndSplitsAcrossEncodingBases(){
        for(int radix:new int[]{2,10,11,12,13,14})for(int amount:new int[]{49,50,51,100,101}){
            var p=new EncodingProfile(radix,10,1,4,false,List.of());var address=new Address(radix==2?0:1,1,radix==2?0:1);
            List<ItemData> items=new ArrayList<>();for(int i=0;i<amount;i++)items.add(new ItemData("test:item_"+i,i%2==0?16:64,"",false));
            var e=new LayoutEntry(address,new Pos(0,0,0),"","fixture",items,List.of(),false);
            var plan=EncodingEngine.plan(project(p,List.of(e)));assertEquals(amount,plan.addresses().size());
            assertEquals((radix==2?1:3)*((amount+49)/50),plan.chests().size());EncodingEngine.verify(plan);
        }
    }
    @Test void manualMasksValidateNonOverlapCoverageAndFieldBoundaries() {
        var entries=List.of(entry(new Address(0,1,0),"minecraft:stone"));
        assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(project(new EncodingProfile(2,10,2,10,false,List.of(1023)),entries)));
        assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(project(new EncodingProfile(2,10,2,10,true,List.of(511,513)),entries)));
        assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(project(new EncodingProfile(2,10,2,10,true,List.of(511)),entries)));
        var plan=EncodingEngine.plan(project(new EncodingProfile(2,10,2,10,true,List.of(341,682)),entries));assertEquals(List.of(682,341),plan.masks());
    }
    @Test void dpMatchesIndependentEnumeratedPartitions() {
        Random random=new Random(20261019);EncodingProfile p=new EncodingProfile(2,10,2,3,false,List.of());
        for(int trial=0;trial<8;trial++) {
            List<LayoutEntry> entries=new ArrayList<>();for(int i=0;i<90;i++)entries.add(entry(new Address(random.nextInt(2),random.nextInt(63)+1,random.nextInt(8)),"test:i"+i));
            EncoderPlan plan=EncodingEngine.plan(project(p,entries));
            assertEquals(enumerate(1023,new ArrayList<>(),p,plan.addresses()),plan.chests().size());
        }
    }
    private int enumerate(int remaining,List<Integer> masks,EncodingProfile p,Map<String,Address> items) {
        if(remaining==0)return EncodingEngine.makeChests(p,items,masks).size();
        int pivot=Integer.lowestOneBit(remaining),best=Integer.MAX_VALUE;
        for(int subset=remaining;subset>0;subset=(subset-1)&remaining)if((subset&pivot)!=0&&EncodingEngine.allowed(p,subset)){
            masks.add(subset);best=Math.min(best,enumerate(remaining^subset,masks,p,items));masks.removeLast();
        }return best;
    }
    @Test void mirroredGeometryAndOverlapValidation() {
        List<LaneAnchor> lanes=new ArrayList<>();for(int i=0;i<8;i++)lanes.add(new LaneAnchor(new Pos(0,i%4,i/4),"none"));
        GroupConfig g=new GroupConfig(0,new Pos(-6,0,0),new Pos(0,3,1),new Pos(0,0,0),new Pos(-2,0,0),4,lanes,false,true,Set.of());g.validate(EncodingProfile.defaults());
        assertEquals(new Pos(-6,3,1),g.at(4,7));
        GroupConfig bad=new GroupConfig(0,g.min(),g.max(),g.first(),new Pos(0,1,0),4,lanes,false,true,Set.of());assertThrows(IllegalArgumentException.class,()->bad.validate(EncodingProfile.defaults()));
    }
}
