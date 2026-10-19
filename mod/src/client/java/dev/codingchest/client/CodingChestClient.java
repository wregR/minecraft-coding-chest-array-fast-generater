package dev.codingchest.client;

import dev.codingchest.core.*;
import dev.codingchest.core.Model.*;
import dev.codingchest.net.Wire;
import dev.codingchest.world.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.*;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.text.Text;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class CodingChestClient implements ClientModInitializer {
    public static CodingChestClient INSTANCE;
    public LayoutProject project;public EncoderPlan plan;public Pos corner1,corner2;
    public final List<LaneAnchor> samples=new ArrayList<>();public Pos sampleOrigin;
    public final Set<String> suggestedBackgrounds=new LinkedHashSet<>();
    public List<StructureDetector.Candidate> suggestions=List.of();public int suggestion;
    public String status="F7选两角，F8依次采样首片各水道，F6打开面板";
    public Pos previewOrigin=new Pos(0,0,0);public int rotation;public boolean preview;
    private WorldScanner.Job scan;private StructureDetector detector;private Future<?> computation;private long revision;
    private String scanToken;
    private final ArrayDeque<GroupConfig> pendingScans=new ArrayDeque<>();private int batchTotal;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"codingchest-client-plan");t.setDaemon(true);return t;});
    private final Wire.Assembler assembler=new Wire.Assembler();
    private KeyBinding menu,select,sample;
    public boolean selectionMode,showLayout=true;public String activeRegionId="";
    public List<SavedRegion> regions=List.of();private RegionRepository regionRepository;private String regionScope="";
    public Path lastExport;
    public MinecraftClient mc(){return MinecraftClient.getInstance();}
    @Override public void onInitializeClient() {
        INSTANCE=this;
        menu=key("menu",GLFW.GLFW_KEY_F6);select=key("select",GLFW.GLFW_KEY_F7);sample=key("sample",GLFW.GLFW_KEY_F8);
        try{Files.createDirectories(directory().resolve("projects"));Files.createDirectories(directory().resolve("selections"));Files.createDirectories(schematicDirectory());}catch(Exception e){status="错误：初始化目录失败："+e.getMessage();}
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((context,tick)->{
            if(selectionMode&&mc().world!=null&&mc().currentScreen==null){int y=mc().getWindow().getScaledHeight()-78;context.fill(6,y-3,Math.min(mc().getWindow().getScaledWidth()-6,410),y+25,0xB0000000);context.drawTextWithShadow(mc().textRenderer,"框选模式：左键角点1 · 右键角点2 · "+selectKey()+"退出",10,y,0xFFFFAA);context.drawTextWithShadow(mc().textRenderer,"① "+(corner1==null?"待选择":format(corner1))+"  ② "+(corner2==null?"待选择":format(corner2))+" · "+menuKey()+"保存",10,y+12,0xFFFFFF);}
        });
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(client.world==null){if(scanToken!=null||scan!=null||detector!=null||computation!=null)cancel();preview=false;return;}
            if(scanToken!=null&&!clientWorldMatches()){cancel();status="维度已变化，扫描取消";}
            ensureProject();while(menu.wasPressed())client.setScreen(new ProjectScreen(this));
            while(select.wasPressed())toggleSelection();while(sample.wasPressed())try{captureSample();}catch(Exception e){error(e);}
            if(scan!=null) {
                if(scan.world!=client.world){scan=null;status="维度已变化，扫描取消";}
                else if(scan.tick()){if(!scan.cancelled)addScan(scan.config,scan.entries());scan=null;}
                else status="本地扫描 "+scan.progress()+"/"+scan.total();
            }
            if(detector!=null)try {if(detector.world!=client.world){detector=null;status="维度已变化，推测取消";}
                else if(detector.tick()){suggestions=detector.candidates();suggestion=0;status=suggestions.isEmpty()?"未找到可靠结构，请用F8手动采样首片": "找到"+suggestions.size()+"个候选，请确认方向和首片";detector=null;refresh();}
                else status=detector.progress();}catch(Exception e){detector=null;error(e);}
            if(scan==null&&scanToken==null&&detector==null&&!pendingScans.isEmpty())try{dispatchScan(pendingScans.removeFirst());}catch(Exception ex){pendingScans.clear();error(ex);}
        });
        ClientPlayNetworking.registerGlobalReceiver(Wire.Message.ID,(m,ctx)->{
            try {assembler.accept(m).ifPresent(json->{if(m.kind().equals("scan_result")){Wire.ScanResult r=PlanExporter.GSON.fromJson(json,Wire.ScanResult.class);if(!Objects.equals(scanToken,r.token())||scanToken==null)return;scanToken=null;if(clientWorldMatches())addScan(r.config(),r.entries());}else {status=json;if(m.kind().equals("error")){scanToken=null;pendingScans.clear();}}refresh();});}
            catch(Exception e){error(e);}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->{cancel();preview=false;});
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->dispatcher.register(ClientCommandManager.literal("codingchest").executes(ctx->{mc().setScreen(new ProjectScreen(this));return 1;})));
        WorldRenderEvents.AFTER_ENTITIES.register(PreviewRenderer::render);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client->PreviewRenderer.close());
    }
    private KeyBinding key(String name,int code){return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.codingchest."+name,InputUtil.Type.KEYSYM,code,"category.codingchest"));}
    public void ensureProject(){if(project==null)project=LayoutProject.empty(mc().world.getRegistryKey().getValue().toString());ensureRegions();}
    public String menuKey(){return menu.getBoundKeyLocalizedText().getString();}
    public String selectKey(){return select.getBoundKeyLocalizedText().getString();}
    public String sampleKey(){return sample.getBoundKeyLocalizedText().getString();}
    public void toggleSelection(){selectionMode=!selectionMode;mc().options.attackKey.setPressed(false);mc().options.useKey.setPressed(false);if(mc().interactionManager!=null)mc().interactionManager.cancelBlockBreaking();status=selectionMode?"框选已开启：左键角点1、右键角点2（64格内）；不会破坏或使用方块":"框选已关闭；选区保留，打开面板可保存";if(mc().player!=null)mc().player.sendMessage(Text.literal(status),true);refresh();}
    public boolean handleSelectionMouse(long window,int button,int action){
        if(mc().world==null||mc().currentScreen!=null||window!=mc().getWindow().getHandle())return false;
        if(menu.matchesMouse(button)||select.matchesMouse(button)||sample.matchesMouse(button)){
            if(action==GLFW.GLFW_PRESS)try{if(menu.matchesMouse(button))mc().setScreen(new ProjectScreen(this));else if(select.matchesMouse(button))toggleSelection();else captureSample();}catch(Exception e){error(e);}return true;
        }
        if(!selectionMode||(button!=0&&button!=1))return false;
        if(action==GLFW.GLFW_PRESS){HitResult hit=mc().crosshairTarget;Pos p=null;
            if(hit instanceof EntityHitResult e&&e.getEntity() instanceof ItemFrameEntity)p=WorldScanner.pos(e.getEntity().getBlockPos());
            else {HitResult ray=mc().player.raycast(64,1f,false);if(ray.getType()==HitResult.Type.BLOCK&&ray instanceof BlockHitResult b)p=WorldScanner.pos(b.getBlockPos());}
            if(p==null)status="请指向64格内已加载的方块或展示框";else{if(button==0)corner1=p;else corner2=p;activeRegionId="";status="角点"+(button+1)+"："+format(p)+(corner1!=null&&corner2!=null?"；虚框已就绪，按"+menuKey()+"保存选区":"；请再选择另一个角点");}
            mc().player.sendMessage(Text.literal(status),true);refresh();
        }return true;
    }
    public Pos target(){HitResult hit=mc().crosshairTarget;if(hit.getType()==HitResult.Type.BLOCK&&hit instanceof BlockHitResult b)return WorldScanner.pos(b.getBlockPos());if(hit instanceof EntityHitResult e&&e.getEntity() instanceof ItemFrameEntity)return WorldScanner.pos(e.getEntity().getBlockPos());status="请指向方块或展示框";return null;}
    private void captureSample() {
        Pos p=target();if(p==null)return;
        if(ProjectScreen.hasControlDown()){samples.clear();sampleOrigin=null;suggestedBackgrounds.clear();status="已清空首片样本";return;}
        String face="none";if(ProjectScreen.hasShiftDown()&&mc().crosshairTarget instanceof BlockHitResult hit)face=hit.getSide().getName();
        WorldScanner.DisplaySample resolved=WorldScanner.displaySample(mc().world,p,face);p=resolved.base();face=resolved.face();if(!resolved.background().isBlank())suggestedBackgrounds.add(resolved.background());
        if(sampleOrigin==null)sampleOrigin=p;
        LaneAnchor anchor=new LaneAnchor(p.sub(sampleOrigin),face);
        if(samples.stream().anyMatch(a->a.offset().equals(anchor.offset()))){status="该样本已添加";return;}
        samples.add(anchor);status="已采样首片水道 "+samples.size()+"，背板 "+format(p)+" 附着面 "+face;mc().player.sendMessage(Text.literal(status),true);refresh();
    }
    public Set<String> calibrateSamples(){
        if(sampleOrigin==null||samples.isEmpty())throw new IllegalArgumentException("请先采样首片或载入已有组");
        List<WorldScanner.DisplaySample> resolved=new ArrayList<>();
        for(LaneAnchor lane:samples){Pos base=sampleOrigin.add(lane.offset()),display=base;if(!lane.face().equals("none"))display=display.add(lane.outward());var value=WorldScanner.displaySample(mc().world,display,lane.face());resolved.add(value.background().isEmpty()?new WorldScanner.DisplaySample(base,lane.face(),""):value);}
        Pos first=resolved.getFirst().base();samples.clear();for(var s:resolved){samples.add(new LaneAnchor(s.base().sub(first),s.face()));if(!s.background().isBlank())suggestedBackgrounds.add(s.background());}sampleOrigin=first;status="已校正展示框附着面，裸方块将从背板位置读取";return Set.copyOf(suggestedBackgrounds);
    }
    public Pos min(){if(corner1==null||corner2==null)throw new IllegalArgumentException("请先F7选定两个角");return new Pos(Math.min(corner1.x(),corner2.x()),Math.min(corner1.y(),corner2.y()),Math.min(corner1.z(),corner2.z()));}
    public Pos max(){min();return new Pos(Math.max(corner1.x(),corner2.x()),Math.max(corner1.y(),corner2.y()),Math.max(corner1.z(),corner2.z()));}
    public void detect(Set<String> ignored){if(busy())throw new IllegalArgumentException("请先结束当前任务");detector=new StructureDetector(mc().world,min(),max(),ignored,sampleOrigin,List.copyOf(samples));}
    public void applySuggestion(){if(suggestions.isEmpty())throw new IllegalArgumentException("没有候选结构");StructureDetector.Candidate c=suggestions.get(suggestion);samples.clear();samples.addAll(c.lanes());sampleOrigin=c.first();
        EncodingProfile old=project.profile();int radix=samples.size()==8?2:Math.max(10,samples.size());int groups=radix==2?(Set.of(1,2,4,8).contains(old.groups())?old.groups():2):Math.min(old.groups(),radix);
        profile(new EncodingProfile(radix,old.totalBits(),groups,old.maxGroupWidth(),old.crossFields(),List.of(),samples.size()));status="已载入候选样本（"+samples.size()+"水道）；请确认组号、靠内方向及间距";
    }
    private boolean clientWorldMatches(){return mc().world!=null&&project!=null&&project.dimension().equals(mc().world.getRegistryKey().getValue().toString());}
    public boolean busy(){return !pendingScans.isEmpty()||scanToken!=null||scan!=null||detector!=null||(computation!=null&&!computation.isDone());}
    public void cancel(){revision++;pendingScans.clear();batchTotal=0;scanToken=null;if(scan!=null)scan.cancelled=true;scan=null;detector=null;if(computation!=null)computation.cancel(true);computation=null;if(server())send("cancel","");status="任务已取消";}
    public boolean server(){return mc().getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Wire.Message.ID);}
    public void send(String kind,String json){if(!server())throw new IllegalArgumentException("服务器没有安装模组支持");Wire.send(kind,json,m->ClientPlayNetworking.send(m));}
    public void scanAll(){if(busy())throw new IllegalArgumentException("已有任务运行");if(project.groups().isEmpty())throw new IllegalArgumentException("请先登记全部分组");for(GroupConfig g:project.groups())g.validate(project.profile());invalidate();pendingScans.addAll(project.groups().stream().sorted(Comparator.comparingInt(GroupConfig::number)).toList());batchTotal=pendingScans.size();project=new LayoutProject(1,project.name(),project.dimension(),project.profile(),project.groups(),List.of());status="准备扫描全部"+batchTotal+"个组；旧记录已移出，取消后不允许混用旧扫描";}
    public void startScan(GroupConfig g){if(busy())throw new IllegalArgumentException("已有本地任务运行");batchTotal=0;g.validate(project.profile());invalidate();dispatchScan(g);}
    private void dispatchScan(GroupConfig g){if(!project.dimension().equals(mc().world.getRegistryKey().getValue().toString()))throw new IllegalArgumentException("项目维度不符，请新建项目");g.validate(project.profile());
        if(server()){scanToken=UUID.randomUUID().toString();try{send("scan",PlanExporter.GSON.toJson(new Wire.ScanRequest(project.profile(),g,scanToken)));}catch(Exception e){scanToken=null;throw e;}status="已请求服务端扫描";}
        else {scan=new WorldScanner.Job(mc().world,g,project.profile());status="本地扫描；容器内容需要服务端支持";}}
    private void addScan(GroupConfig g,List<LayoutEntry> entries){List<GroupConfig> groups=new ArrayList<>(project.groups());groups.removeIf(a->a.number()==g.number());groups.add(g);groups.sort(Comparator.comparingInt(GroupConfig::number));List<LayoutEntry> all=new ArrayList<>(project.entries());all.removeIf(e->e.address().group()==g.number());all.addAll(entries);project=new LayoutProject(1,project.name(),project.dimension(),project.profile(),List.copyOf(groups),List.copyOf(all));invalidate();status=batchTotal>0?"全图扫描 "+(batchTotal-pendingScans.size())+"/"+batchTotal+"组；已记录"+all.size()+"个位置":"扫描完成："+entries.size()+"个位置，"+entries.stream().filter(e->!e.issues().isEmpty()).count()+"个待修正";refresh();}
    public void profile(EncodingProfile p){if(busy())throw new IllegalArgumentException("先取消当前任务再修改编码配置");p.validate();project=new LayoutProject(1,project.name(),project.dimension(),p,project.groups(),project.entries());invalidate();}
    public void invalidate(){revision++;plan=null;preview=false;if(computation!=null)computation.cancel(true);}
    public void compute(){compute(false);}
    public void computeAndExport(){if(plan!=null)try{export();}catch(Exception e){error(e);}else compute(true);}
    private void compute(boolean saveSchematic){if(busy())throw new IllegalArgumentException("已有任务运行");for(GroupConfig g:project.groups())g.validate(project.profile());LayoutProject snapshot=project;long version=++revision;plan=null;preview=false;status="正在搜索最少箱数并反向验证…";
        computation=worker.submit(()->{try{EncoderPlan result=EncodingEngine.plan(snapshot);mc().execute(()->{if(revision!=version)return;plan=result;status="完成："+result.addresses().size()+"种物品，"+result.chests().size()+"双箱（逐位基准"+result.baselineChests()+"）";if(saveSchematic)try{export();}catch(Exception e){error(e);}refresh();});}catch(Exception e){mc().execute(()->{if(revision==version)error(e);});}});
    }
    public void editEntry(int index,String ids){LayoutEntry old=project.entries().get(index);List<ItemData> items=new ArrayList<>();for(String raw:ids.split("[,;\\s]+")){if(raw.isBlank())continue;String id=raw.contains(":")?raw:"minecraft:"+raw;Identifier key=Identifier.of(id);if(!Registries.ITEM.containsId(key)||Registries.ITEM.get(key)==Items.AIR)throw new IllegalArgumentException("未知物品: "+id);ItemStack s=new ItemStack(Registries.ITEM.get(key));items.add(new ItemData(id,s.getMaxCount(),s.encode(mc().world.getRegistryManager()).toString(),false));}
        replace(index,new LayoutEntry(old.address(),old.position(),old.blockState(),"manual",List.copyOf(items),List.of(),false));}
    public void exclude(int index){LayoutEntry e=project.entries().get(index);replace(index,new LayoutEntry(e.address(),e.position(),e.blockState(),e.source(),e.items(),e.issues(),!e.excluded()));}
    private void replace(int index,LayoutEntry e){List<LayoutEntry> list=new ArrayList<>(project.entries());list.set(index,e);project=new LayoutProject(1,project.name(),project.dimension(),project.profile(),project.groups(),List.copyOf(list));invalidate();}
    public void excludeUnsupported(){List<LayoutEntry> entries=project.entries().stream().map(e->new LayoutEntry(e.address(),e.position(),e.blockState(),e.source(),e.items().stream().map(i->new ItemData(i.id(),i.maxStack(),i.stackNbt(),i.maxStack()!=64||i.excluded())).toList(),e.issues(),e.excluded())).toList();project=new LayoutProject(1,project.name(),project.dimension(),project.profile(),project.groups(),entries);invalidate();status="已排除非64堆叠物品，目标地址保持不变";}
    public void keepFirstDestination(){
        if(busy())throw new IllegalArgumentException("请先完成扫描");Map<String,Address> first=new HashMap<>();int removed=0;List<LayoutEntry> entries=new ArrayList<>();
        for(LayoutEntry e:project.entries().stream().sorted(Comparator.comparing(LayoutEntry::address)).toList()){
            List<ItemData> items=new ArrayList<>();for(ItemData i:e.items()) {boolean excluded=i.excluded();if(!excluded&&!e.excluded()){Address previous=first.putIfAbsent(i.id(),e.address());if(previous!=null&&!previous.equals(e.address())){excluded=true;removed++;}}items.add(new ItemData(i.id(),i.maxStack(),i.stackNbt(),excluded));}
            entries.add(new LayoutEntry(e.address(),e.position(),e.blockState(),e.source(),List.copyOf(items),e.issues(),e.excluded()));
        }
        project=new LayoutProject(1,project.name(),project.dimension(),project.profile(),project.groups(),List.copyOf(entries));invalidate();status="已排除"+removed+"个重复目标；保留组号/单片号/水道号最小的地址";
    }
    public int nextGroup(){int n=project.profile().radix()==2?0:1;Set<Integer> used=new HashSet<>();project.groups().forEach(g->used.add(g.number()));while(used.contains(n))n++;return n;}
    public Path directory(){return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("config/codingchest");}
    public Path schematicDirectory(){return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("schematics/codingchest");}
    private Path projectFile(){String name=project.name().replaceAll("[^\\p{L}\\p{N}_.-]","_");return directory().resolve("projects").resolve(name+".json");}
    public void rename(String name){if(name.isBlank()||name.length()>80)throw new IllegalArgumentException("项目名为1～80字符");project=new LayoutProject(1,name,project.dimension(),project.profile(),project.groups(),project.entries());}
    public void save()throws Exception{Path f=projectFile();Files.createDirectories(f.getParent());Path temp=f.resolveSibling(f.getFileName()+".tmp");Files.writeString(temp,PlanExporter.GSON.toJson(project),StandardCharsets.UTF_8);try{Files.move(temp,f,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,f,StandardCopyOption.REPLACE_EXISTING);}status="已保存 "+f;}
    public void load()throws Exception{Path f=projectFile();if(Files.size(f)>8_000_000)throw new IllegalArgumentException("项目文件过大");LayoutProject loaded=PlanExporter.GSON.fromJson(Files.readString(f),LayoutProject.class);if(loaded.schemaVersion()!=1)throw new IllegalArgumentException("不支持的项目版本");loaded.profile().validate();for(GroupConfig g:loaded.groups())g.validate(loaded.profile());project=loaded;invalidate();status="已打开 "+f;}
    public void export()throws Exception{if(plan==null)throw new IllegalArgumentException("请先计算方案");var result=PlanExporter.exportFiles(schematicDirectory(),project,plan,mc().world.getRegistryManager(),rotation);lastExport=result.directory();status="投影已自动保存："+result.schematic();refresh();}
    public void openSchematicFolder()throws Exception{Files.createDirectories(schematicDirectory());net.minecraft.util.Util.getOperatingSystem().open(lastExport==null?schematicDirectory():lastExport);}
    private void ensureRegions(){try{
        String world=mc().getServer()!=null?mc().getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize().toString():mc().getCurrentServerEntry()!=null?mc().getCurrentServerEntry().address:"unknown-server";
        String scope=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((world+"|"+mc().world.getRegistryKey().getValue()).getBytes(StandardCharsets.UTF_8))).substring(0,32);
        if(scope.equals(regionScope))return;RegionRepository repository=new RegionRepository(directory().resolve("selections"),scope);List<SavedRegion> loaded=repository.load();regionRepository=repository;regionScope=scope;regions=loaded;activeRegionId="";selectionMode=false;corner1=null;corner2=null;samples.clear();sampleOrigin=null;
    }catch(Exception e){regionScope="";error(e);}}
    public void saveRegion(String name)throws Exception{ensureRegions();SavedRegion r=new SavedRegion(UUID.randomUUID().toString(),name,mc().world.getRegistryKey().getValue().toString(),corner1,corner2);List<SavedRegion> next=new ArrayList<>(regions);next.add(r);regionRepository.save(next);regions=List.copyOf(next);activeRegionId=r.id();status="选区已保存："+name+"；可继续框选并保存其他区域";}
    public void useRegion(SavedRegion r){if(!r.dimension().equals(mc().world.getRegistryKey().getValue().toString()))throw new IllegalArgumentException("请返回选区所属维度");corner1=r.first();corner2=r.second();activeRegionId=r.id();selectionMode=false;samples.clear();sampleOrigin=null;suggestedBackgrounds.clear();suggestions=List.of();suggestion=0;status="已载入选区："+r.name()+"；请识别首片或采样水道";}
    public void renameRegion(String id,String name)throws Exception{List<SavedRegion> next=new ArrayList<>();for(SavedRegion r:regions)next.add(r.id().equals(id)?new SavedRegion(id,name,r.dimension(),r.first(),r.second()):r);regionRepository.save(next);regions=List.copyOf(next);}
    public void deleteRegion(String id)throws Exception{List<SavedRegion> next=regions.stream().filter(r->!r.id().equals(id)).toList();regionRepository.save(next);regions=next;if(activeRegionId.equals(id))activeRegionId="";}
    public void registerGroup(GroupConfig group){if(busy())throw new IllegalArgumentException("请先结束任务");group.validate(project.profile());List<GroupConfig> groups=new ArrayList<>(project.groups());groups.removeIf(g->g.number()==group.number());groups.add(group);groups.sort(Comparator.comparingInt(GroupConfig::number));var entries=project.entries().stream().filter(e->e.address().group()!=group.number()).toList();project=new LayoutProject(1,project.name(),project.dimension(),project.profile(),List.copyOf(groups),entries);invalidate();status="已保存组"+group.number()+"配置；可添加其他组后统一扫描";}
    public List<String> savedProjects()throws Exception{Files.createDirectories(directory().resolve("projects"));try(var files=Files.list(directory().resolve("projects"))){return files.filter(f->f.getFileName().toString().endsWith(".json")).map(f->f.getFileName().toString().replaceFirst("\\.json$","")).sorted().toList();}}
    public void loadProject(String name)throws Exception{LayoutProject old=project;try{rename(name);load();}catch(Exception e){project=old;throw e;}}
    public void place(){if(plan==null)throw new IllegalArgumentException("请先计算方案");send("place",PlanExporter.GSON.toJson(new Wire.PlacementRequest(project,previewOrigin,rotation)));status="服务端正在验证并生成";}
    public void error(Exception e){status="错误："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());refresh();}
    public void refresh(){if(mc().currentScreen instanceof ProjectScreen s)s.refreshSoon=true;}
    public static String format(Pos p){return p.x()+" "+p.y()+" "+p.z();}
    public static Pos parse(String s){String[] parts=s.trim().split("[,\\s]+");if(parts.length!=3)throw new IllegalArgumentException("坐标格式为 x y z");return new Pos(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));}
}
