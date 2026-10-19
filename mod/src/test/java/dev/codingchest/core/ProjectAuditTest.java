package dev.codingchest.core;

import dev.codingchest.core.Model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectAuditTest {
    private LayoutEntry entry(int slice,boolean excluded,ItemData... items){return new LayoutEntry(new Address(0,slice,0),new Pos(slice,0,0),"","fixture",List.of(items),List.of(),excluded);}
    private ItemData item(String id,int stack,boolean excluded){return new ItemData(id,stack,"",excluded);}
    private LayoutProject project(LayoutEntry... entries){return new LayoutProject(1,"audit","minecraft:overworld",EncodingProfile.defaults(),List.of(),List.of(entries));}
    @Test void conflictsAreDifferentActiveDestinationsNotRepeatedStacks(){
        var stone=item("minecraft:stone",64,false);
        assertTrue(ProjectAudit.conflictingItems(project(entry(1,false,stone,stone))).isEmpty());
        assertEquals(Set.of("minecraft:stone"),ProjectAudit.conflictingItems(project(entry(1,false,stone),entry(2,false,stone))));
    }
    @Test void excludedItemsAndPositionsDoNotProduceConflicts(){
        var stone=item("minecraft:stone",64,false);
        assertTrue(ProjectAudit.conflictingItems(project(entry(1,false,stone),entry(2,true,stone),entry(3,false,item("minecraft:stone",64,true)))).isEmpty());
    }
    @Test void summaryDoesNotHideEmptyOrUnsupportedPositions(){
        var p=project(entry(1,false,item("minecraft:stone",64,false)),entry(2,false),entry(3,false,item("minecraft:ender_pearl",16,false)));
        assertEquals("3位置 / 2有物品 / 2种可编码 / 0待读取修正 / 0条不支持堆叠 / 0种目标冲突",ProjectAudit.summary(p));
    }
    @Test void restore16IsExplicitAndPreservesOtherExclusionsAndEmptyAddresses(){
        var p=project(entry(1,false,item("minecraft:ender_pearl",16,true),item("minecraft:stone",64,true),item("minecraft:shears",1,true)),entry(2,true,item("minecraft:egg",16,true)),entry(3,false));
        var restored=ProjectAudit.restoreExcluded16(p);
        assertTrue(p.entries().getFirst().items().getFirst().excluded());assertFalse(restored.entries().getFirst().items().getFirst().excluded());
        assertTrue(restored.entries().getFirst().items().get(1).excluded());assertTrue(restored.entries().getFirst().items().get(2).excluded());
        assertTrue(restored.entries().get(1).excluded());assertTrue(restored.entries().get(1).items().getFirst().excluded());
        assertEquals(p.entries().get(2),restored.entries().get(2));
    }
}
