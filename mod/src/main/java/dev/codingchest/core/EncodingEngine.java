package dev.codingchest.core;

import dev.codingchest.core.Model.*;
import java.util.*;
import java.util.concurrent.CancellationException;

public final class EncodingEngine {
    private EncodingEngine() {}
    private static void cancelled() { if(Thread.currentThread().isInterrupted()) throw new CancellationException("计算已取消"); }
    public static EncoderPlan plan(LayoutProject project) {
        EncodingProfile p=project.profile(); p.validate();
        requireComplete(project);
        Map<String,Address> addresses=new TreeMap<>();
        for(LayoutEntry e:project.entries()) {
            cancelled(); if(e.excluded())continue;
            if(!e.issues().isEmpty()) throw new IllegalArgumentException(e.position()+": "+String.join("; ",e.issues()));
            Address a=e.address();
            int first=p.radix()==2?0:1;
            if(a.group()<first||a.group()>=first+p.groups()||a.slice()<1||a.slice()>p.maxSlices()||a.lane()<first||a.lane()>=first+p.lanes()) throw new IllegalArgumentException("地址超出范围: "+a);
            for(ItemData item:e.items()) {
                if(item.excluded())continue;
                if(!ChestFilling.supported(item.maxStack())) throw new IllegalArgumentException("仅支持16或64堆叠物品，请在校对页排除: "+item.id());
                if(!item.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("无效物品ID: "+item.id());
                Address previous=addresses.putIfAbsent(item.id(),a);
                if(previous!=null&&!previous.equals(a)) throw new IllegalArgumentException("物品目标冲突 "+item.id()+": "+previous+" / "+a);
            }
        }
        if(addresses.isEmpty())throw new IllegalArgumentException("没有参与编码的16或64堆叠物品");
        List<Integer> masks=p.radix()==2?optimize(p,addresses):List.of();
        List<EncoderChest> chests=makeChests(p,addresses,masks);
        List<Integer> singles=new ArrayList<>(); for(int b=p.totalBits()-1;b>=0;b--) singles.add(1<<b);
        int baseline=p.radix()==2?makeChests(p,addresses,singles).size():chests.size();
        EncoderPlan result=new EncoderPlan(p,Collections.unmodifiableMap(new LinkedHashMap<>(addresses)),List.copyOf(masks),List.copyOf(chests),baseline,chests.stream().mapToInt(c->c.items().size()).sum());
        verify(result); return result;
    }
    public static void requireComplete(LayoutProject project) {
        if(project.groups().isEmpty())return;
        Set<Integer> numbers=new HashSet<>();Set<Address> expected=new HashSet<>();
        int laneBase=project.profile().radix()==2?0:1;
        for(GroupConfig group:project.groups()){
            group.validate(project.profile());if(!numbers.add(group.number()))throw new IllegalArgumentException("单片组编号重复："+group.number());
            for(int slice=1;slice<=group.slices();slice++)for(int lane=0;lane<group.lanes().size();lane++)expected.add(new Address(group.number(),slice,lane+laneBase));
        }
        Set<Address> actual=new HashSet<>();
        for(LayoutEntry e:project.entries())if(!actual.add(e.address()))throw new IllegalArgumentException("目标位置重复记录："+e.address());
        Set<Address> missing=new HashSet<>(expected);missing.removeAll(actual);
        if(!missing.isEmpty())throw new IllegalArgumentException("全图尚未扫描完整：缺少"+missing.size()+"个位置，请扫描项目全部组");
        actual.removeAll(expected);if(!actual.isEmpty())throw new IllegalArgumentException("项目包含不属于当前分组的旧记录，请重新扫描全部组");
    }
    public static boolean allowed(EncodingProfile p,int mask) {
        if(mask<=0||mask>=(1<<p.totalBits())||Integer.bitCount(mask)>p.maxGroupWidth())return false;
        if(p.crossFields())return true;
        int lane=(1<<p.laneBits())-1, slice=((1<<p.sliceBits())-1)<<p.laneBits(), group=((1<<p.groupBits())-1)<<(p.sliceBits()+p.laneBits());
        return (mask&lane)==mask||(mask&slice)==mask||(mask&group)==mask;
    }
    private static List<Integer> optimize(EncodingProfile p,Map<String,Address> addresses) {
        int all=(1<<p.totalBits())-1;
        if(p.lockedMasks()!=null&&!p.lockedMasks().isEmpty()) {
            int union=0;
            for(int mask:p.lockedMasks()) { if(!allowed(p,mask)||(union&mask)!=0)throw new IllegalArgumentException("手动位组重叠、超宽或越过字段边界"); union|=mask; }
            if(union!=all)throw new IllegalArgumentException("手动位组必须覆盖所有码位");
            return p.lockedMasks().stream().sorted(Comparator.reverseOrder()).toList();
        }
        int size=all+1; int[] cost=new int[size],copies=new int[size],best=new int[size],bestCopies=new int[size],groups=new int[size],choice=new int[size];
        Arrays.fill(cost,-1); Arrays.fill(best,Integer.MAX_VALUE/4); best[0]=0;
        int[] numeric=addresses.values().stream().mapToInt(a->a.binary(p)).toArray();
        for(int mask=1;mask<size;mask++)if(allowed(p,mask)) {
            cancelled(); Map<Integer,Integer> pools=new HashMap<>();
            for(int code:numeric) { int value=code&mask; if(value!=0)pools.merge(value,1,Integer::sum); }
            cost[mask]=pools.values().stream().mapToInt(n->(n+49)/50).sum(); copies[mask]=pools.values().stream().mapToInt(n->n).sum();
        }
        for(int set=1;set<size;set++) {
            cancelled(); int pivot=Integer.lowestOneBit(set);
            for(int sub=set;sub>0;sub=(sub-1)&set) {
                if((sub&pivot)==0||cost[sub]<0)continue;
                int rest=set^sub, c=best[rest]+cost[sub], cp=bestCopies[rest]+copies[sub], g=groups[rest]+1;
                if(c<best[set]||(c==best[set]&&(cp<bestCopies[set]||(cp==bestCopies[set]&&(g<groups[set]||(g==groups[set]&&sub>choice[set])))))) {
                    best[set]=c;bestCopies[set]=cp;groups[set]=g;choice[set]=sub;
                }
            }
        }
        List<Integer> result=new ArrayList<>(); for(int set=all;set!=0;set^=choice[set]) { if(choice[set]==0)throw new IllegalStateException("位组划分失败"); result.add(choice[set]); }
        result.sort(Comparator.reverseOrder());return result;
    }
    public static List<EncoderChest> makeChests(EncodingProfile p,Map<String,Address> addresses,List<Integer> masks) {
        Map<Signal,List<String>> pools=new LinkedHashMap<>();
        List<Map.Entry<String,Address>> sorted=addresses.entrySet().stream().sorted(Map.Entry.<String,Address>comparingByValue().thenComparing(Map.Entry.comparingByKey())).toList();
        if(p.radix()==2) {
            for(int mask:masks) {
                Map<Integer,List<String>> values=new TreeMap<>();
                for(var e:sorted) { int value=e.getValue().binary(p)&mask; if(value!=0)values.computeIfAbsent(value,k->new ArrayList<>()).add(e.getKey()); }
                String field=field(p,mask);
                values.forEach((value,items)->pools.put(new Signal(field,mask,value),items));
            }
        } else {
            String[] fields={"分组","单片","水道"};
            for(int f=0;f<3;f++) { final int part=f; Map<Integer,List<String>> values=new TreeMap<>();
                for(var e:sorted) { Address a=e.getValue();int value=part==0?a.group():part==1?a.slice():a.lane();values.computeIfAbsent(value,k->new ArrayList<>()).add(e.getKey()); }
                values.forEach((value,items)->pools.put(new Signal(fields[part],0,value),items));
            }
        }
        List<EncoderChest> result=new ArrayList<>();
        pools.forEach((signal,items)->{for(int start=0;start<items.size();start+=50)result.add(new EncoderChest(signal,start/50+1,List.copyOf(items.subList(start,Math.min(start+50,items.size())))));});
        return result;
    }
    private static String field(EncodingProfile p,int mask) {
        if((mask&((1<<p.laneBits())-1))==mask)return "水道";
        if((mask&(((1<<p.sliceBits())-1)<<p.laneBits()))==mask)return "单片";
        if((mask&(((1<<p.groupBits())-1)<<(p.sliceBits()+p.laneBits())))==mask)return "分组";
        return "跨字段";
    }
    public static void verify(EncoderPlan plan) {
        for(var item:plan.addresses().entrySet()) {
            cancelled();int binary=0;Map<String,Integer> nary=new HashMap<>();
            for(EncoderChest chest:plan.chests()) {
                if(chest.items().size()>50||new HashSet<>(chest.items()).size()!=chest.items().size())throw new IllegalStateException("编码箱容量或重复物品错误");
                if(chest.items().contains(item.getKey())) {Signal s=chest.signal();binary|=s.value();if(s.mask()==0&&nary.putIfAbsent(s.field(),s.value())!=null)throw new IllegalStateException("同物品重复触发字段");}
            }
            Address a=item.getValue();
            boolean valid=plan.profile().radix()==2?binary==a.binary(plan.profile()):Objects.equals(nary.get("分组"),a.group())&&Objects.equals(nary.get("单片"),a.slice())&&Objects.equals(nary.get("水道"),a.lane());
            if(!valid)throw new IllegalStateException("反向解码校验失败: "+item.getKey());
        }
    }
}
