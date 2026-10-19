package dev.codingchest.core;
import dev.codingchest.core.Model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CompleteProjectTest {
    @Test void cannotGenerateFromOnlyOneOfTwoRegisteredSections(){
        var p=new EncodingProfile(10,10,2,4,false,List.of(),1);var lane=List.of(new LaneAnchor(new Pos(0,0,0),"none"));
        var g1=new GroupConfig(1,new Pos(0,0,0),new Pos(0,0,0),new Pos(0,0,0),new Pos(0,0,1),1,lane,false,true,Set.of());
        var g2=new GroupConfig(2,new Pos(0,0,10),new Pos(0,0,10),new Pos(0,0,10),new Pos(0,0,-1),1,lane,false,true,Set.of());
        var e1=new LayoutEntry(new Address(1,1,1),g1.first(),"","fixture",List.of(new ItemData("minecraft:stone",64,"",false)),List.of(),false);
        var e2=new LayoutEntry(new Address(2,1,1),g2.first(),"","fixture",List.of(new ItemData("minecraft:dirt",64,"",false)),List.of(),false);
        assertThrows(IllegalArgumentException.class,()->EncodingEngine.plan(new LayoutProject(1,"two","minecraft:overworld",p,List.of(g1,g2),List.of(e1))));
        assertEquals(2,EncodingEngine.plan(new LayoutProject(1,"two","minecraft:overworld",p,List.of(g1,g2),List.of(e1,e2))).addresses().size());
    }
}
