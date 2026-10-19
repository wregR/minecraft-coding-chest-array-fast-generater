package dev.codingchest.core;
import dev.codingchest.core.Model.Pos;
/** Raw selections can be saved before recognizing a layout or choosing a code. */
public record SavedRegion(String id,String name,String dimension,Pos first,Pos second) {
    public SavedRegion {
        if(id==null||!id.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("选区ID无效");
        if(name==null||name.isBlank()||name.length()>80)throw new IllegalArgumentException("选区名称须为1～80字");
        if(dimension==null||first==null||second==null)throw new IllegalArgumentException("选区两角尚未选全");
        if(first.volumeTo(second)>2_000_000)throw new IllegalArgumentException("选区超过200万格，请分段保存");
    }
    public Pos min(){return new Pos(Math.min(first.x(),second.x()),Math.min(first.y(),second.y()),Math.min(first.z(),second.z()));}
    public Pos max(){return new Pos(Math.max(first.x(),second.x()),Math.max(first.y(),second.y()),Math.max(first.z(),second.z()));}
    public long volume(){return first.volumeTo(second);}
}
