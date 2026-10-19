package dev.codingchest.core;

import dev.codingchest.core.Model.*;
import java.util.*;

/** Read-only diagnostics shared by review filters and scan summaries. */
public final class ProjectAudit {
    private ProjectAudit() {}
    public static Set<String> conflictingItems(LayoutProject project) {
        Map<String,Address> first=new HashMap<>();Set<String> conflicts=new TreeSet<>();
        for(LayoutEntry e:project.entries())if(!e.excluded())for(ItemData item:e.items())if(!item.excluded()){
            Address old=first.putIfAbsent(item.id(),e.address());if(old!=null&&!old.equals(e.address()))conflicts.add(item.id());
        }
        return Collections.unmodifiableSet(conflicts);
    }
    public static String summary(LayoutProject project) {
        int occupied=0,issues=0,unsupported=0;Set<String> supported=new HashSet<>();
        for(LayoutEntry e:project.entries()){
            if(!e.items().isEmpty())occupied++;
            if(e.excluded())continue;if(!e.issues().isEmpty())issues++;
            for(ItemData item:e.items())if(!item.excluded()){if(ChestFilling.supported(item.maxStack()))supported.add(item.id());else unsupported++;}
        }
        return project.entries().size()+"位置 / "+occupied+"有物品 / "+supported.size()+"种可编码 / "+issues+"待读取修正 / "+unsupported+"条不支持堆叠 / "+conflictingItems(project).size()+"种目标冲突";
    }
    /** Explicit user action only; never resurrect an excluded destination or other item types. */
    public static LayoutProject restoreExcluded16(LayoutProject project){
        List<LayoutEntry> entries=project.entries().stream().map(e->e.excluded()?e:new LayoutEntry(e.address(),e.position(),e.blockState(),e.source(),e.items().stream().map(i->i.maxStack()==16&&i.excluded()?new ItemData(i.id(),i.maxStack(),i.stackNbt(),false):i).toList(),e.issues(),false)).toList();
        return new LayoutProject(project.schemaVersion(),project.name(),project.dimension(),project.profile(),project.groups(),entries);
    }
}
