package dev.codingchest.client;

import dev.codingchest.core.SavedRegion;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;
import java.util.*;

/** A browsable library rather than requiring players to remember file names. */
final class LibraryScreen extends Screen {
    private final CodingChestClient c;private final Screen parent;private final boolean projects;
    private int page,selected=-1;private float scale=1;private TextFieldWidget name;
    private List<String> names=List.of();private String draft="";
    private interface Action{void run()throws Exception;}
    LibraryScreen(CodingChestClient c,Screen parent,boolean projects){super(Text.literal(projects?"项目列表":"当前世界的选区库"));this.c=c;this.parent=parent;this.projects=projects;}
    private void button(int x,int y,int w,String text,Action action){addDrawableChild(ButtonWidget.builder(Text.literal(text),b->{try{action.run();}catch(Exception e){c.error(e);}}).dimensions(x,y,w,20).build());}
    private void rebuild(){clearChildren();init();}
    @Override protected void init(){
        int aw=client.getWindow().getScaledWidth(),ah=client.getWindow().getScaledHeight();scale=Math.min(1f,Math.min(aw/720f,ah/440f));width=Math.round(aw/scale);height=Math.round(ah/scale);
        if(projects)try{names=c.savedProjects();}catch(Exception e){c.error(e);names=List.of();}
        name=new TextFieldWidget(textRenderer,16,47,width-32,20,Text.empty());name.setMaxLength(80);name.setText(draft.isEmpty()?(projects?c.project.name():"选区"+(c.regions.size()+1)):draft);name.setChangedListener(v->draft=v);addDrawableChild(name);
        if(projects){button(16,74,160,"打开选中/输入的项目",()->client.setScreen(new ConfirmScreen(ok->{if(ok)try{c.loadProject(name.getText());client.setScreen(new ProjectScreen(c));}catch(Exception e){c.error(e);client.setScreen(this);}else client.setScreen(this);},Text.literal("打开另一个项目"),Text.literal("当前项目未保存的修改不会自动覆盖原文件。请先返回保存需要保留的内容。"))));}
        else {
            button(16,74,165,"将当前虚框另存为新选区",()->{c.saveRegion(name.getText());draft="";selected=-1;rebuild();});
            button(187,74,140,"返回游戏继续框选",()->{if(!c.selectionMode)c.toggleSelection();client.setScreen(null);});
        }
        int size=Math.max(1,(height-210)/28),total=projects?names.size():c.regions.size();page=Math.max(0,Math.min(page,Math.max(0,(total-1)/size)));
        button(16,102,70,"上一页",()->{page--;rebuild();});button(91,102,70,"下一页",()->{page++;rebuild();});
        for(int i=page*size;i<Math.min(total,(page+1)*size);i++){
            final int index=i;String label=projects?names.get(i):c.regions.get(i).name()+"   "+CodingChestClient.format(c.regions.get(i).min())+" → "+CodingChestClient.format(c.regions.get(i).max());
            button(16,130+(i-page*size)*28,width-32,(selected==i?"▶ ":"")+textRenderer.trimToWidth(label,width-55),()->{selected=index;draft=projects?names.get(index):c.regions.get(index).name();rebuild();});
        }
        if(!projects&&selected>=0&&selected<c.regions.size()){
            SavedRegion r=c.regions.get(selected);
            button(16,height-68,190,"载入此选区并进入识别",()->{c.useRegion(r);client.setScreen(new ProjectScreen(c,1));});
            button(212,height-68,110,"按上方名称重命名",()->{c.renameRegion(r.id(),name.getText());rebuild();});
            button(328,height-68,90,"删除此选区…",()->client.setScreen(new ConfirmScreen(ok->{if(ok)try{c.deleteRegion(r.id());selected=-1;draft="";}catch(Exception e){c.error(e);}client.setScreen(this);},Text.literal("删除已保存选区"),Text.literal("仅删除选区记录，不会更改世界中的方块或其他选区。"))));
        }
        button(width-116,height-35,100,"返回",this::close);
    }
    @Override public void render(DrawContext ctx,int x,int y,float delta){ctx.getMatrices().push();ctx.getMatrices().scale(scale,scale,1);renderBackground(ctx,(int)(x/scale),(int)(y/scale),delta);ctx.fill(6,6,width-6,height-6,0xBB102038);super.render(ctx,(int)(x/scale),(int)(y/scale),delta);ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,projects?"选择已有项目，或在输入框填写名称；打开前请保存当前修改。":"先框选再保存；每个世界独立存放，可保存多个选区，识别时再逐个载入。",16,32,0xC8D7EA);
        int total=projects?names.size():c.regions.size();ctx.drawTextWithShadow(textRenderer,"共 "+total+" 项 · 第 "+(page+1)+" 页",180,107,0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(c.status,width-32),16,height-92,c.status.startsWith("错误")?0xFF7777:0xAAEEBB);ctx.getMatrices().pop();}
    @Override public boolean mouseClicked(double x,double y,int b){return super.mouseClicked(x/scale,y/scale,b);}
    @Override public boolean mouseReleased(double x,double y,int b){return super.mouseReleased(x/scale,y/scale,b);}
    @Override public boolean mouseDragged(double x,double y,int b,double dx,double dy){return super.mouseDragged(x/scale,y/scale,b,dx/scale,dy/scale);}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){return super.mouseScrolled(x/scale,y/scale,h,v);}
    @Override public void close(){client.setScreen(parent);}
    @Override public boolean shouldPause(){return false;}
}
