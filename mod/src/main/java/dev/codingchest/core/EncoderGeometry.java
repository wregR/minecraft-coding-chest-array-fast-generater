package dev.codingchest.core;

import dev.codingchest.core.Model.*;
import java.util.*;

/** Geometry is not the address format. A bit group stays together on its physical level. */
public final class EncoderGeometry {
    public static final int LAYER_HEIGHT=2;
    public record Slot(int chest,int level,int column) {public Pos local(int half){return new Pos(column,level*LAYER_HEIGHT,half);}}
    public record Label(Pos local,String face,List<String> lines,int chest) {}
    public static List<Label> labels(EncoderPlan plan,int rotation) {
        List<Label> result=new ArrayList<>();List<Slot> slots=slots(plan);
        for(Slot slot:slots){EncoderChest chest=plan.chests().get(slot.chest());Signal s=chest.signal();
            List<String> lines=plan.profile().radix()==2
                ?List.of("#"+(slot.chest()+1)+" / "+chest.items().size(),s.field()+" p"+chest.part(),"M:"+Model.bits(s.mask(),plan.profile().totalBits()),"V:"+Model.bits(s.value(),plan.profile().totalBits()))
                :List.of("#"+(slot.chest()+1),s.field()+" = "+s.value(),"拆分 "+chest.part(),chest.items().size()+" 种物品");
            result.add(new Label(slot.local(0).add(new Pos(0,0,-1)),"north",lines,slot.chest()));
        }
        if(!slots.isEmpty()){
            int r=Math.floorMod(rotation,4);String axis=new String[]{"+X","+Z","-X","-Z"}[r],direction=new String[]{"东","南","西","北"}[r];
            result.add(new Label(slots.getFirst().local(0).add(new Pos(-1,0,0)),"west",List.of("起点 #1","正方向 "+axis,"向"+direction+" / 箱号递增","箱面朝"+direction),-1));
        }
        return List.copyOf(result);
    }
    public static List<Slot> slots(EncoderPlan plan) {
        List<Slot> slots=new ArrayList<>();int[] columns={0,0};Map<Integer,Integer> maskLevels=new HashMap<>();
        for(int i=0;i<plan.chests().size();i++) {
            EncoderChest c=plan.chests().get(i);int level=0;
            if(plan.profile().radix()==2){Integer known=maskLevels.get(c.signal().mask());if(known==null){known=columns[0]<=columns[1]?0:1;maskLevels.put(c.signal().mask(),known);}level=known;}
            slots.add(new Slot(i,level,columns[level]++));
        }return List.copyOf(slots);
    }
}
