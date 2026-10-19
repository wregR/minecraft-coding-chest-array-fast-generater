package dev.codingchest.core;

import java.util.*;

/** World-independent persisted model. All address numbers are explicit, never inferred from strings. */
public final class Model {
    private Model() {}
    public record Pos(int x, int y, int z) {
        public Pos add(Pos b) { return new Pos(x+b.x,y+b.y,z+b.z); }
        public Pos sub(Pos b) { return new Pos(x-b.x,y-b.y,z-b.z); }
        public Pos scale(int n) { return new Pos(x*n,y*n,z*n); }
        public long volumeTo(Pos b) {
            try {return Math.multiplyExact(Math.multiplyExact(Math.abs((long)x-b.x)+1,Math.abs((long)y-b.y)+1),Math.abs((long)z-b.z)+1);}
            catch(ArithmeticException overflow){return Long.MAX_VALUE;}
        }
    }
    public record EncodingProfile(int radix, int totalBits, int groups, int maxGroupWidth,
                                  boolean crossFields, List<Integer> lockedMasks, int waterwayCount) {
        public EncodingProfile(int radix,int totalBits,int groups,int maxGroupWidth,boolean crossFields,List<Integer> lockedMasks) {this(radix,totalBits,groups,maxGroupWidth,crossFields,lockedMasks,0);}
        public static EncodingProfile defaults() { return new EncodingProfile(2,10,2,4,false,List.of()); }
        public int groupBits() { return radix==2 ? Integer.numberOfTrailingZeros(groups) : 0; }
        public int laneBits() { return lanes()<=1?0:32-Integer.numberOfLeadingZeros(lanes()-1); }
        public int sliceBits() { return totalBits-groupBits()-laneBits(); }
        public int lanes() { return waterwayCount==0?(radix==2 ? 8 : radix):waterwayCount; }
        public int maxSlices() { return radix==2 ? (1<<sliceBits())-1 : radix; }
        public void validate() {
            if(lanes()<1||lanes()>(radix==2?16:radix))throw new IllegalArgumentException("实际水道数超出信号容量");
            if(radix==2) {
                if(!Set.of(1,2,4,8).contains(groups)) throw new IllegalArgumentException("二进制单片组数必须是1/2/4/8");
                if(totalBits<10||totalBits>12) throw new IllegalArgumentException("总码长必须为10/11/12位");
                if(maxGroupWidth<1||maxGroupWidth>totalBits) throw new IllegalArgumentException("组合位宽超出总码长");
            } else if(radix<10||radix>14||groups<1||groups>radix) throw new IllegalArgumentException("N进制应为10～14，组数不超过N");
        }
    }
    public record LaneAnchor(Pos offset, String face) {
        public Pos outward(){return switch(face){case "east"->new Pos(1,0,0);case "west"->new Pos(-1,0,0);case "up"->new Pos(0,1,0);case "down"->new Pos(0,-1,0);case "south"->new Pos(0,0,1);case "north"->new Pos(0,0,-1);default->new Pos(0,0,0);};}
    }
    public record GroupConfig(int number, Pos min, Pos max, Pos first, Pos step, int slices,
                              List<LaneAnchor> lanes, boolean containers, boolean expandShulkers,
                              Set<String> ignoredBlocks) {
        public Pos at(int slice, int lane) { return first.add(step.scale(slice-1)).add(lanes.get(lane).offset()); }
        public void validate(EncodingProfile p) {
            p.validate();
            int firstNumber=p.radix()==2?0:1;
            if(number<firstNumber||number>=firstNumber+p.groups()) throw new IllegalArgumentException("单片组编号超出配置");
            if(slices<1||slices>p.maxSlices()) throw new IllegalArgumentException("单片数超过当前编码容量 " + p.maxSlices());
            if(lanes==null||lanes.size()!=p.lanes()) throw new IllegalArgumentException("首片水道样本数量应为"+p.lanes());
            if(step==null||(Math.abs(step.x())+Math.abs(step.y())+Math.abs(step.z())==0)) throw new IllegalArgumentException("单片间距不能为0");
            if(new HashSet<>(lanes.stream().map(LaneAnchor::offset).toList()).size()!=lanes.size()) throw new IllegalArgumentException("水道样本坐标重复");
            for(LaneAnchor lane:lanes)if(!Set.of("none","up","down","east","west","north","south").contains(lane.face()))throw new IllegalArgumentException("显示方向须为none/up/down/east/west/north/south");
            if(min.volumeTo(max)>2_000_000) throw new IllegalArgumentException("选区过大，请缩小到单个组");
            Set<Pos> used=new HashSet<>();
            for(int s=1;s<=slices;s++) for(int l=0;l<lanes.size();l++) {
                Pos at=at(s,l),display=at.add(lanes.get(l).outward());
                if(!inside(at,min,max)&&!inside(display,min,max)) throw new IllegalArgumentException("样本网格超出框选范围: "+display);
                if(!used.add(display)) throw new IllegalArgumentException("单片/水道位置互相重叠: "+display);
            }
        }
        private static boolean inside(Pos p,Pos min,Pos max){return p.x()>=min.x()&&p.y()>=min.y()&&p.z()>=min.z()&&p.x()<=max.x()&&p.y()<=max.y()&&p.z()<=max.z();}
    }
    public record ItemData(String id, int maxStack, String stackNbt, boolean excluded) {}
    public record Address(int group, int slice, int lane) implements Comparable<Address> {
        public int binary(EncodingProfile p) { return (group<<(p.sliceBits()+p.laneBits()))|(slice<<p.laneBits())|lane; }
        public String display(EncodingProfile p) {
            if(p.radix()!=2) return "["+group+" | "+slice+" | "+lane+"]";
            return (p.groupBits()==0?"":bits(group,p.groupBits())+" | ")+bits(slice,p.sliceBits())+" | "+(p.laneBits()==0?"-":bits(lane,p.laneBits()));
        }
        public int compareTo(Address b) { int c=Integer.compare(group,b.group); if(c==0)c=Integer.compare(slice,b.slice); return c==0?Integer.compare(lane,b.lane):c; }
    }
    public static String bits(int n,int width) { return "0".repeat(Math.max(0,width-Integer.toBinaryString(n).length()))+Integer.toBinaryString(n); }
    public record LayoutEntry(Address address, Pos position, String blockState, String source,
                              List<ItemData> items, List<String> issues, boolean excluded) {}
    public record LayoutProject(int schemaVersion, String name, String dimension, EncodingProfile profile,
                                List<GroupConfig> groups, List<LayoutEntry> entries) {
        public static LayoutProject empty(String dimension) { return new LayoutProject(1,"编码全物品",dimension,EncodingProfile.defaults(),List.of(),List.of()); }
    }
    public record Signal(String field, int mask, int value) {
        public String label() { return mask==0?field+"="+value:field+" mask="+Integer.toBinaryString(mask)+" value="+Integer.toBinaryString(value); }
    }
    public record EncoderChest(Signal signal, int part, List<String> items) {}
    public record EncoderPlan(EncodingProfile profile, Map<String,Address> addresses, List<Integer> masks,
                              List<EncoderChest> chests, int baselineChests, int copies) {}
}
