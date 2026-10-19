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
            for(ItemData item:e.items())if(!item.excluded()){if(item.maxStack()==64)supported.add(item.id());else unsupported++;}
        }
        return project.entries().size()+"位置 / "+occupied+"有物品 / "+supported.size()+"种可编码 / "+issues+"待读取修正 / "+unsupported+"条非64堆叠 / "+conflictingItems(project).size()+"种目标冲突";
    }
}
