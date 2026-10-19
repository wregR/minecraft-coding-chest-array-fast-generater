package dev.codingchest.client;

import dev.codingchest.core.Model.*;
import dev.codingchest.core.ProjectAudit;
import dev.codingchest.core.ChestFilling;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;
import java.util.*;

/** Native game UI. Sampling keys work with this screen closed; all inferred geometry is editable. */
public final class ProjectScreen extends Screen {
    private final CodingChestClient c;private int tab,page,selected=-1;private int left,top,panelWidth;
    public boolean refreshSoon;private TextFieldWidget name,first,step,slices,group,ignored,edit,origin,manualMasks,anchorPos,anchorFace;
    private boolean containers,expand=true;private int sampleIndex,reviewFilter,groupPage;
    private final List<TextFieldWidget> fields=new ArrayList<>();
    private final List<String> labels=new ArrayList<>();
    private final Map<String,String> draft=new HashMap<>();private float uiScale=1;
    private interface Action{void run()throws Exception;}
    public ProjectScreen(CodingChestClient c){super(Text.literal("编码全物品"));this.c=c;}
    public ProjectScreen(CodingChestClient c,int initialTab){this(c);tab=initialTab;}
    @Override protected void init(){
        int actualW=client.getWindow().getScaledWidth(),actualH=client.getWindow().getScaledHeight();
        uiScale=Math.min(1f,Math.min(actualW/800f,actualH/500f));width=Math.round(actualW/uiScale);height=Math.round(actualH/uiScale);
        left=12;top=47;panelWidth=width-24;fields.clear();labels.clear();
        String[] tabs={"①选区 / 项目","②识别 / 编码","③物品校对","④生成 / 保存投影"};
        for(int i=0;i<tabs.length;i++){final int t=i;button(left+i*(panelWidth/4),23,panelWidth/4-3,tabs[i],()->{tab=t;page=0;selected=-1;rebuild();});}
        if(tab==0)project();else if(tab==1)geometry();else if(tab==2)review();else output();
        button(left,height-28,70,"回到游戏",this::close);button(left+74,height-28,70,"取消任务",c::cancel);
    }
    private void rebuild(){clearChildren();init();}
    private ButtonWidget button(int x,int y,int w,String label,Action action){ButtonWidget b=ButtonWidget.builder(Text.literal(label),pressed->{try{action.run();}catch(Exception e){c.error(e);}}).dimensions(x,y,w,20).build();addDrawableChild(b);return b;}
    private TextFieldWidget field(int x,int y,int w,String value){TextFieldWidget f=new TextFieldWidget(textRenderer,x,y,w,20,Text.empty());f.setMaxLength(32000);f.setText(value);addDrawableChild(f);fields.add(f);return f;}
    private TextFieldWidget geometryField(String key,int x,int y,int w,String value){TextFieldWidget f=field(x,y,w,draft.getOrDefault(key,value));f.setChangedListener(v->draft.put(key,v));return f;}
    private void label(String text,int y){labels.add(y+":"+text);}
    private void project(){EncodingProfile p=c.project.profile();
        button(width-160,top+42,145,"框选："+(c.selectionMode?"开 · 左/右键选角":"关 · "+c.selectKey()),()->{c.toggleSelection();rebuild();});
        button(width-160,top+67,70,"选区库 ("+c.regions.size()+")",()->client.setScreen(new LibraryScreen(c,this,false)));
        button(width-85,top+67,70,"项目列表",()->client.setScreen(new LibraryScreen(c,this,true)));
        button(width-160,top+92,145,"修改快捷键…",()->client.setScreen(new net.minecraft.client.gui.screen.option.KeybindsScreen(this,client.options)));
        button(width-160,top+117,145,"排版编号显示："+(c.showLayout?"开":"关"),()->{c.showLayout=!c.showLayout;rebuild();});
        label("项目名（保存和打开均使用此名称）",top);name=field(left,top+13,Math.min(270,panelWidth-150),c.project.name());
        button(left+280,top+13,65,"保存",()->{c.rename(name.getText());c.save();});button(left+349,top+13,65,"打开",()->{c.rename(name.getText());c.load();rebuild();});
        button(left+475,top+13,140,"扫描项目全部分组",c::scanAll);
        button(left,top+42,130,"类型："+(p.radix()==2?"二进制":p.radix()+"进制"),()->{int r=p.radix()==2?10:p.radix()==14?2:p.radix()+1;int g=r==2?(Set.of(1,2,4,8).contains(p.groups())?p.groups():2):Math.min(p.groups(),r);c.profile(new EncodingProfile(r,p.totalBits(),g,p.maxGroupWidth(),p.crossFields(),List.of(),p.waterwayCount()==0?0:Math.min(p.lanes(),r==2?16:r)));rebuild();});
        button(left+135,top+42,105,"单片组数："+p.groups(),()->{int g=p.radix()==2?(p.groups()==8?1:p.groups()*2):p.groups()%p.radix()+1;c.profile(new EncodingProfile(p.radix(),p.totalBits(),g,p.maxGroupWidth(),p.crossFields(),List.of(),p.waterwayCount()));rebuild();});
        button(left+340,top+42,130,"实际水道数："+p.lanes(),()->{c.profile(new EncodingProfile(p.radix(),p.totalBits(),p.groups(),p.maxGroupWidth(),p.crossFields(),List.of(),p.lanes()%(p.radix()==2?16:p.radix())+1));rebuild();});
        button(left+475,top+42,140,"按首片样本设水道数",()->{if(c.samples.isEmpty())throw new IllegalArgumentException("请先用"+c.sampleKey()+"采样首片或载入已有组");c.profile(new EncodingProfile(p.radix(),p.totalBits(),p.groups(),p.maxGroupWidth(),p.crossFields(),List.of(),c.samples.size()));rebuild();});
        if(p.radix()==2){button(left+245,top+42,90,"码长："+p.totalBits(),()->{c.profile(new EncodingProfile(2,p.totalBits()==12?10:p.totalBits()+1,p.groups(),Math.min(p.maxGroupWidth(),p.totalBits()==12?10:p.totalBits()+1),p.crossFields(),List.of(),p.waterwayCount()));rebuild();});
            button(left,top+67,130,"最大组合位宽："+p.maxGroupWidth(),()->{c.profile(new EncodingProfile(2,p.totalBits(),p.groups(),p.maxGroupWidth()%p.totalBits()+1,p.crossFields(),List.of(),p.waterwayCount()));rebuild();});
            button(left+135,top+67,200,"跨字段组合："+(p.crossFields()?"允许":"不允许"),()->{c.profile(new EncodingProfile(2,p.totalBits(),p.groups(),p.maxGroupWidth(),!p.crossFields(),List.of(),p.waterwayCount()));rebuild();});
            label("字段："+p.groupBits()+"位分组 / "+p.sliceBits()+"位单片 / "+p.laneBits()+"位水道；单片1起",top+96);
            label("手动位组：用十进制位掩码，逗号分隔；空白为自动优化",top+113);manualMasks=field(left,top+127,270,p.lockedMasks().stream().map(Object::toString).reduce((a,b)->a+","+b).orElse(""));
            button(left+275,top+127,90,"应用位组",()->{List<Integer> masks=manualMasks.getText().isBlank()?List.of():Arrays.stream(manualMasks.getText().split("[,\\s]+" )).map(Integer::parseInt).toList();c.profile(new EncodingProfile(2,p.totalBits(),p.groups(),p.maxGroupWidth(),p.crossFields(),masks,p.waterwayCount()));c.status="手动位组已更新，下次计算时验证覆盖和边界";});
        }else label("信号地址：[分组1～"+p.groups()+" | 单片1～"+p.radix()+" | 水道1～"+p.lanes()+"]",top+73);
        int y=top+(p.radix()==2?157:103);label("当前项目 "+c.project.entries().size()+"个位置；服务端支持："+(c.server()?"已连接":"未安装，仅客户端可见数据"),y);
        int count=Math.max(1,(height-y-100)/22);groupPage=Math.max(0,Math.min(groupPage,Math.max(0,(c.project.groups().size()-1)/count)));
        button(left,height-75,90,"上一页分组",()->{groupPage--;rebuild();});button(left+95,height-75,90,"下一页分组",()->{groupPage++;rebuild();});
        int n=0;for(GroupConfig g:c.project.groups().stream().skip((long)groupPage*count).limit(count).toList()){final int number=g.number();button(left,y+17+n*22,Math.min(430,panelWidth),"组"+g.number()+" · "+g.slices()+"片 · "+g.lanes().size()+"水道（点击载入首片配置）",()->{c.corner1=g.min();c.corner2=g.max();c.sampleOrigin=g.first();c.samples.clear();c.samples.addAll(g.lanes());containers=g.containers();expand=g.expandShulkers();draft.clear();tab=1;rebuild();group.setText(""+number);step.setText(CodingChestClient.format(g.step()));slices.setText(""+g.slices());ignored.setText(String.join(",",g.ignoredBlocks()));});n++;}
        button(width-115,height-28,100,"新建空项目",()->client.setScreen(new ConfirmScreen(ok->{if(ok){c.cancel();c.project=LayoutProject.empty(client.world.getRegistryKey().getValue().toString());c.plan=null;c.preview=false;}client.setScreen(this);},Text.literal("新建项目"),Text.literal("未保存的当前项目会从面板中移除，请先保存。"))));
    }
    private void geometry(){
        label(c.selectKey()+"开关框选 → 左/右键选角 → 选区库保存；"+c.sampleKey()+"采样（Shift附着 / Ctrl清空）",top);
        button(width-150,top+14,130,"选区库 / 保存当前虚框",()->client.setScreen(new LibraryScreen(c,this,false)));
        label("选区："+(c.corner1==null?"未选择":CodingChestClient.format(c.corner1))+" → "+(c.corner2==null?"未选择":CodingChestClient.format(c.corner2)),top+15);
        label("首片原点 x y z",top+33);first=geometryField("first",left,top+47,155,c.sampleOrigin==null?"0 0 0":CodingChestClient.format(c.sampleOrigin));
        button(left+300,top+47,84,"回①调整编码",()->{tab=0;rebuild();});
        label("每片步进 dx dy dz（正负指定内→外）",top+73);step=geometryField("step",left,top+87,155,"1 0 0");
        label("单片数",top+113);slices=geometryField("slices",left,top+127,70,"1");label("组号",top+157);group=geometryField("group",left,top+171,70,""+c.nextGroup());
        button(left+80,top+127,75,"倒转内外",()->{Pos d=CodingChestClient.parse(step.getText()),f=CodingChestClient.parse(first.getText());int n=Integer.parseInt(slices.getText());first.setText(CodingChestClient.format(f.add(d.scale(n-1))));step.setText(CodingChestClient.format(d.scale(-1)));});
        label("忽略的背景方块ID（逗号分隔）",top+201);ignored=geometryField("ignored",left,top+215,Math.min(330,panelWidth),"minecraft:smooth_quartz,minecraft:smooth_quartz_stairs,minecraft:bedrock");
        button(left+165,top+47,130,"推测重复结构",()->c.detect(ignoredIds()));
        button(left+165,top+72,130,"候选 "+(c.suggestion+1)+"/"+c.suggestions.size(),()->{if(!c.suggestions.isEmpty())c.suggestion=(c.suggestion+1)%c.suggestions.size();rebuild();});
        button(left+165,top+97,130,"载入候选",()->{c.applySuggestion();StructureDetector.Candidate s=c.suggestions.get(c.suggestion);rebuild();first.setText(CodingChestClient.format(s.first()));step.setText(CodingChestClient.format(s.step()));slices.setText(""+Math.min(s.slices(),c.project.profile().maxSlices()));});
        button(left+165,top+127,130,containers?"模式：容器内容":"模式：展示排版",()->{containers=!containers;rebuild();});
        button(left+165,top+152,130,"展开潜影盒："+(expand?"是":"否"),()->{expand=!expand;rebuild();});
        button(left+165,top+177,130,"确认首片并扫描",()->{GroupConfig g=new GroupConfig(Integer.parseInt(group.getText()),c.min(),c.max(),CodingChestClient.parse(first.getText()),CodingChestClient.parse(step.getText()),Integer.parseInt(slices.getText()),List.copyOf(c.samples),containers,expand,ignoredIds());g.validate(c.project.profile());
            client.setScreen(new ConfirmScreen(ok->{client.setScreen(this);if(ok)try{c.startScan(g);}catch(Exception e){c.error(e);}},Text.literal("确认排版地址"),Text.literal("组"+g.number()+"；首片"+CodingChestClient.format(g.first())+"；内→外步进"+CodingChestClient.format(g.step())+"；"+g.slices()+"片。采样顺序就是水道递增顺序。扫描会替换此组旧记录；盒内物品展开到同一地址。")));});
        button(left+300,top+177,84,"仅保存组配置",()->c.registerGroup(new GroupConfig(Integer.parseInt(group.getText()),c.min(),c.max(),CodingChestClient.parse(first.getText()),CodingChestClient.parse(step.getText()),Integer.parseInt(slices.getText()),List.copyOf(c.samples),containers,expand,ignoredIds())));
        int rx=left+Math.max(345,panelWidth/2);if(rx+170>width)rx=left+310;
        button(rx,height-139,235,"校正展示框附着方向",()->{Set<String> backgrounds=c.calibrateSamples();Set<String> all=ignoredIds();all.addAll(backgrounds);rebuild();first.setText(CodingChestClient.format(c.sampleOrigin));ignored.setText(String.join(",",new TreeSet<>(all)));});
        button(rx,top+47,85,"水道反转",()->{Collections.reverse(c.samples);rebuild();});
        button(rx+90,top+47,80,"清空样本",()->{c.samples.clear();c.sampleOrigin=null;rebuild();});
        int available=Math.max(1,(height-111-(top+73)-8)/22),start=sampleIndex/available*available;
        button(rx+175,top+47,32,"<",()->{sampleIndex=Math.max(0,sampleIndex-available);rebuild();});
        button(rx+211,top+47,32,">",()->{sampleIndex=Math.min(Math.max(0,c.samples.size()-1),sampleIndex+available);rebuild();});
        for(int i=start;i<Math.min(c.samples.size(),start+available);i++){final int row=i;LaneAnchor a=c.samples.get(i);button(rx,top+73+(i-start)*22,Math.min(235,width-rx-15),(i==sampleIndex?"▶ ":"")+i+": "+CodingChestClient.format(a.offset())+" / "+a.face(),()->{sampleIndex=row;rebuild();});}
        if(!c.samples.isEmpty()){sampleIndex=Math.min(sampleIndex,c.samples.size()-1);LaneAnchor a=c.samples.get(sampleIndex);int y=height-111;
            anchorPos=field(rx,y,130,CodingChestClient.format(a.offset()));anchorFace=field(rx+134,y,80,a.face());
            button(rx,y+24,60,"改坐标",()->{c.samples.set(sampleIndex,new LaneAnchor(CodingChestClient.parse(anchorPos.getText()),anchorFace.getText().trim()));rebuild();});
            button(rx+64,y+24,45,"上移",()->{if(sampleIndex>0)Collections.swap(c.samples,sampleIndex,sampleIndex-- -1);rebuild();});
            button(rx+113,y+24,45,"下移",()->{if(sampleIndex+1<c.samples.size())Collections.swap(c.samples,sampleIndex,sampleIndex++ +1);rebuild();});
        }
    }
    private Set<String> ignoredIds(){Set<String> set=new HashSet<>();for(String s:ignored.getText().split("[,;\\s]+"))if(!s.isBlank())set.add(s.contains(":")?s:"minecraft:"+s);return set;}
    private int perPage(){return Math.max(1,(height-top-205)/21);}
    private void review(){
        Set<String> conflicts=ProjectAudit.conflictingItems(c.project);List<Integer> visible=new ArrayList<>();
        for(int i=0;i<c.project.entries().size();i++){LayoutEntry e=c.project.entries().get(i);boolean conflict=!e.excluded()&&e.items().stream().anyMatch(item->!item.excluded()&&conflicts.contains(item.id()));
            boolean include=switch(reviewFilter){case 1->!e.excluded()&&!e.issues().isEmpty();case 2->conflict;case 3->!e.excluded()&&e.items().stream().anyMatch(item->!item.excluded()&&!ChestFilling.supported(item.maxStack()));case 4->e.items().isEmpty();case 5->e.items().stream().anyMatch(item->item.maxStack()==16);default->true;};if(include)visible.add(i);}
        int size=perPage(),total=visible.size();page=Math.max(0,Math.min(page,Math.max(0,(total-1)/size)));
        label(ProjectAudit.summary(c.project),top);
        button(left,top+16,70,"上一页",()->{page--;rebuild();});button(left+75,top+16,70,"下一页",()->{page++;rebuild();});button(left+150,top+16,160,"排除不支持堆叠物品",()->{c.excludeUnsupported();rebuild();});
        button(left+315,top+16,200,"重复物品保留首个地址…",()->client.setScreen(new ConfirmScreen(ok->{client.setScreen(this);if(ok){c.keepFirstDestination();rebuild();}},Text.literal("处理重复目标"),Text.literal("只保留同一物品最小的组/单片/水道地址，其余目标标为排除，原排版记录保留。请确认这符合机器的目标选择。"))));
        String[] filters={"全部","读取问题","重复目标","不支持堆叠","空位","16堆叠（含已排除）"};
        button(left+135,top+41,180,"恢复已排除16堆叠…",()->client.setScreen(new ConfirmScreen(ok->{client.setScreen(this);if(ok){c.restoreExcluded16();rebuild();}},Text.literal("恢复16堆叠物品"),Text.literal("恢复未排除位置中所有已排除的16堆叠物品，包括手动排除过的。不会恢复整个已排除位置。可能出现重复目标，请重新校对；原文件在保存前不变。"))));
        button(left,top+41,130,"筛选："+filters[reviewFilter],()->{reviewFilter=(reviewFilter+1)%filters.length;page=0;selected=-1;rebuild();});
        label("筛选结果 "+total+"；第"+(page+1)+"/"+Math.max(1,(total+size-1)/size)+"页；红色待修正、灰色已排除，空位不前移。",top+65);
        for(int i=page*size;i<Math.min(total,(page+1)*size);i++){final int index=visible.get(i);LayoutEntry e=c.project.entries().get(index);boolean conflict=!e.excluded()&&e.items().stream().anyMatch(item->!item.excluded()&&conflicts.contains(item.id()));String ids=e.items().stream().map(item->(item.excluded()?"[已排除]":"")+item.id()).reduce((a,b)->a+";"+b).orElse("空位");String row=(e.excluded()?"[排除] ":!e.issues().isEmpty()?"[待修正] ":conflict?"[冲突] ":"")+e.address().display(c.project.profile())+" "+ids;ButtonWidget b=button(left,top+80+(i-page*size)*21,panelWidth,trim(row,panelWidth-10),()->{selected=index;rebuild();});b.setMessage(Text.literal(trim(row,panelWidth-10)).styled(style->style.withColor(e.excluded()?0x888888:!e.issues().isEmpty()||conflict?0xFF7777:0xFFFFFF)));}
        int y=height-110;
        if(selected>=0&&selected<c.project.entries().size()&&visible.contains(selected)){LayoutEntry e=c.project.entries().get(selected);label("选中 "+e.address().display(c.project.profile())+" @ "+CodingChestClient.format(e.position())+" "+String.join(";",e.issues()),y-13);
            edit=field(left,y,panelWidth-190,e.items().stream().map(ItemData::id).reduce((a,b)->a+","+b).orElse(""));
            button(width-192,y,85,"应用物品ID",()->{c.editEntry(selected,edit.getText());rebuild();});button(width-103,y,90,e.excluded()?"恢复此位置":"排除此位置",()->{c.exclude(selected);rebuild();});
            label(trim("状态："+e.blockState()+"；来源："+e.source(),panelWidth),y+28);
        }else label("点击一行以校对物品ID、排除或恢复整个位置。",y);
    }
    private void output(){
        button(left,top,130,"仅计算 / 校验方案",c::compute);button(left+135,top,160,"生成投影（自动保存）",c::computeAndExport);button(left+300,top,130,"打开投影文件夹",c::openSchematicFolder);
        label("自动保存：游戏目录 / schematics / codingchest / 项目-时间（每箱有编码牌，起点有方向牌）",top+22);
        origin=field(left,top+44,160,CodingChestClient.format(c.previewOrigin));label("阵列原点 x y z",top+33);
        button(left+165,top+44,115,"用玩家脚下",()->{c.previewOrigin=new Pos(client.player.getBlockX(),client.player.getBlockY(),client.player.getBlockZ());origin.setText(CodingChestClient.format(c.previewOrigin));});
        button(left+285,top+44,90,"旋转："+c.rotation*90,()->{c.previewOrigin=CodingChestClient.parse(origin.getText());c.rotation=(c.rotation+1)%4;rebuild();});
        button(left,top+70,130,c.preview?"关闭世界预览":"开启世界预览",()->{if(c.plan==null)throw new IllegalArgumentException("先计算方案");c.previewOrigin=CodingChestClient.parse(origin.getText());c.preview=!c.preview;rebuild();});
        button(left+135,top+70,130,"创造模式放置",()->{c.previewOrigin=CodingChestClient.parse(origin.getText());client.setScreen(new ConfirmScreen(ok->{client.setScreen(this);if(ok)try{c.place();}catch(Exception e){c.error(e);}},Text.literal("放置编码箱阵列"),Text.literal("只在空气中放置；会写入箱子物品。原点 "+CodingChestClient.format(c.previewOrigin)+"，旋转"+c.rotation*90+"°。可以撤销本次生成。")));});
        button(left+270,top+70,105,"撤销本次生成",()->c.send("undo",""));
        if(c.plan==null){label("计算后显示位组、信号箱和材料统计。",top+103);return;}
        label((c.plan.profile().radix()==2?"双层组合阵列 · ":"单层信号阵列 · ")+c.plan.addresses().size()+"种物品 · "+c.plan.chests().size()+"双箱 · 逐位基准"+c.plan.baselineChests()+"箱",top+103);
        label(trim("位组掩码（十进制）："+c.plan.masks()+"；每箱完整输出见导出的 signals.csv",panelWidth),top+119);
        int size=Math.max(1,(height-top-195)/21);page=Math.max(0,Math.min(page,Math.max(0,(c.plan.chests().size()-1)/size)));
        button(left,top+135,70,"上一页",()->{page--;rebuild();});button(left+75,top+135,70,"下一页",()->{page++;rebuild();});
        for(int i=page*size;i<Math.min(c.plan.chests().size(),(page+1)*size);i++){EncoderChest chest=c.plan.chests().get(i);label(trim("#"+(i+1)+" "+chest.signal().label()+" 拆分"+chest.part()+"，"+chest.items().size()+"种物品",panelWidth),top+161+(i-page*size)*21);}
    }
    private String trim(String s,int w){return textRenderer.trimToWidth(s,Math.max(20,w));}
    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta){context.getMatrices().push();context.getMatrices().scale(uiScale,uiScale,1);mouseX=(int)(mouseX/uiScale);mouseY=(int)(mouseY/uiScale);renderBackground(context,mouseX,mouseY,delta);context.fill(6,3,width-6,top-5,0xBB102038);context.fill(6,top-3,width-6,height-50,0x66102038);context.drawCenteredTextWithShadow(textRenderer,title,width/2,7,0xFFFFFF);super.render(context,mouseX,mouseY,delta);
        for(String row:labels){int split=row.indexOf(':');int y=Integer.parseInt(row.substring(0,split));context.drawTextWithShadow(textRenderer,trim(row.substring(split+1),panelWidth),left,y,0xDDDDE8);}
        context.drawTextWithShadow(textRenderer,trim(c.status,panelWidth),left,height-44,c.status.startsWith("错误")?0xFF7777:0xAAEEBB);
        context.getMatrices().pop();
    }
    @Override public void tick(){if(refreshSoon&&!fields.stream().anyMatch(TextFieldWidget::isFocused)){refreshSoon=false;rebuild();}}
    @Override public boolean mouseClicked(double x,double y,int button){return super.mouseClicked(x/uiScale,y/uiScale,button);}
    @Override public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(x/uiScale,y/uiScale,button);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){return super.mouseDragged(x/uiScale,y/uiScale,button,dx/uiScale,dy/uiScale);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return super.mouseScrolled(x/uiScale,y/uiScale,horizontal,vertical);}
    @Override public void mouseMoved(double x,double y){super.mouseMoved(x/uiScale,y/uiScale);}
    @Override public boolean shouldPause(){return false;}
}
