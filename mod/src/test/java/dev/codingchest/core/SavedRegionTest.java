package dev.codingchest.core;
import dev.codingchest.core.Model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SavedRegionTest {
    @Test void reversedCornersAndSingleBlockRegions(){var r=new SavedRegion("one","第一段","minecraft:overworld",new Pos(3,8,10),new Pos(-1,2,-2));assertEquals(new Pos(-1,2,-2),r.min());assertEquals(new Pos(3,8,10),r.max());assertEquals(455,r.volume());assertEquals(1,new SavedRegion("two","第二段","minecraft:overworld",r.min(),r.min()).volume());}
    @Test void rawRegionDoesNotRequireProfileOrSampleAndRejectsIncompleteOrHugeBoxes(){assertThrows(IllegalArgumentException.class,()->new SavedRegion("one","区域","minecraft:overworld",null,new Pos(0,0,0)));assertThrows(IllegalArgumentException.class,()->new SavedRegion("one","区域","minecraft:overworld",new Pos(0,0,0),new Pos(999,999,999)));}
    @Test void signsDoNotCollideWithChestGeometryAndUseActualCodeWidth(){
        var p=new EncodingProfile(2,12,2,4,false,List.of());var chest=new EncoderChest(new Signal("单片",1024,1024),2,List.of("minecraft:stone"));var plan=new EncoderPlan(p,Map.of("minecraft:stone",new Address(0,1,0)),List.of(),List.of(chest),1,1);
        var labels=EncoderGeometry.labels(plan,0);assertEquals(2,labels.size());assertEquals("M:010000000000",labels.getFirst().lines().get(2));
        Set<Pos> positions=new HashSet<>();for(var slot:EncoderGeometry.slots(plan))for(int h=0;h<2;h++)positions.add(slot.local(h));for(var label:labels)assertTrue(positions.add(label.local()));
    }
}
