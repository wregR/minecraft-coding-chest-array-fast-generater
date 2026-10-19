package dev.codingchest.core;
import dev.codingchest.core.Model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FlexibleLaneTest {
    @Test void frameBackingsMaySitJustOutsideDisplayedSelection(){var p=new EncodingProfile(10,10,1,4,false,List.of(),5);List<LaneAnchor> lanes=new ArrayList<>();for(int i=0;i<5;i++)lanes.add(new LaneAnchor(new Pos(0,i,0),"east"));var group=new GroupConfig(1,new Pos(0,0,0),new Pos(0,4,2),new Pos(-1,0,0),new Pos(0,0,1),3,lanes,false,true,Set.of());assertDoesNotThrow(()->group.validate(p));assertEquals(new Pos(0,4,2),group.at(3,4).add(lanes.get(4).outward()));}
    @Test void fiveLanesUseDecimalSignalsOneToFive(){var profile=new EncodingProfile(10,10,1,4,false,List.of(),5);List<LayoutEntry> entries=new ArrayList<>();for(int lane=1;lane<=5;lane++)entries.add(new LayoutEntry(new Address(1,1,lane),new Pos(0,lane,0),"","fixture",List.of(new ItemData("test:item_"+lane,64,"",false)),List.of(),false));var plan=EncodingEngine.plan(new LayoutProject(1,"five","minecraft:overworld",profile,List.of(),entries));assertEquals(5,profile.lanes());assertEquals(5,plan.addresses().size());assertEquals(Set.of(1,2,3,4,5),new HashSet<>(plan.chests().stream().filter(c->c.signal().field().equals("水道")).map(c->c.signal().value()).toList()));}
    @Test void binaryWidthsAdaptWithoutChangingFieldBoundaries(){for(int lanes:new int[]{1,2,5,8,10,16}){var p=new EncodingProfile(2,10,2,4,false,List.of(),lanes);p.validate();int bits=lanes==1?0:32-Integer.numberOfLeadingZeros(lanes-1);assertEquals(bits,p.laneBits());assertEquals(9-bits,p.sliceBits());assertEquals((1<<(p.sliceBits()+bits))|(1<<bits)|(lanes-1),new Address(1,1,lanes-1).binary(p));}}
}
