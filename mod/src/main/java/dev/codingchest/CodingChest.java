package dev.codingchest;

import dev.codingchest.core.*;
import dev.codingchest.core.Model.*;
import dev.codingchest.net.Wire;
import dev.codingchest.world.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import java.util.*;
import java.util.concurrent.*;

public final class CodingChest implements ModInitializer {
    private static final Map<UUID,Wire.Assembler> incoming=new HashMap<>();
    private static final Map<UUID,WorldScanner.Job> scans=new HashMap<>();
    private static final Map<UUID,String> scanTokens=new HashMap<>();
    private static final Map<UUID,PlacementJob> placements=new HashMap<>();
    private record Undo(ServerWorld world,List<PlacementJob.Snapshot> snapshots) {}
    private static final Map<UUID,Undo> undo=new HashMap<>();
    private static final Map<UUID,Future<?>> computations=new HashMap<>();
    private static final Map<UUID,UUID> generations=new HashMap<>();
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"codingchest-server-plan");t.setDaemon(true);return t;});
    @Override public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(Wire.Message.ID,Wire.Message.CODEC);
        PayloadTypeRegistry.playS2C().register(Wire.Message.ID,Wire.Message.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Wire.Message.ID,(m,ctx)->{
            try {incoming.computeIfAbsent(ctx.player().getUuid(),k->new Wire.Assembler()).accept(m).ifPresent(json->handle(ctx.player(),m.kind(),json));}
            catch(Exception e){send(ctx.player(),"error",e.getMessage());}
        });
        ServerTickEvents.END_SERVER_TICK.register(CodingChest::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{UUID id=h.player.getUuid();incoming.remove(id);scanTokens.remove(id);generations.remove(id);WorldScanner.Job scan=scans.remove(id);if(scan!=null)scan.cancelled=true;PlacementJob job=placements.remove(id);if(job!=null)job.rollback();Future<?> f=computations.remove(id);if(f!=null)f.cancel(true);undo.remove(id);});
        ServerLifecycleEvents.SERVER_STOPPING.register(s->{placements.values().forEach(PlacementJob::rollback);placements.clear();scans.clear();scanTokens.clear();generations.clear();undo.clear();incoming.clear();computations.values().forEach(f->f.cancel(true));computations.clear();});
    }
    private static void send(ServerPlayerEntity p,String kind,String json){Wire.send(kind,json,m->ServerPlayNetworking.send(p,m));}
    private static void permitted(ServerPlayerEntity p) {if(!p.isCreative()||!p.hasPermissionLevel(2))throw new IllegalArgumentException("服务端扫描/放置需要创造模式及管理员权限等级2");}
    private static void handle(ServerPlayerEntity player,String kind,String json) {
        UUID id=player.getUuid();
        if(kind.equals("cancel")) {scanTokens.remove(id);generations.remove(id);WorldScanner.Job scan=scans.remove(id);if(scan!=null)scan.cancelled=true;PlacementJob job=placements.get(id);if(job!=null)job.cancelled=true;Future<?> f=computations.remove(id);if(f!=null)f.cancel(true);send(player,"status","任务已取消");return;}
        permitted(player);
        if(kind.equals("scan")) {
            if(scans.containsKey(id)||placements.containsKey(id)||computations.containsKey(id))throw new IllegalArgumentException("已有任务运行，请等待或取消");
            Wire.ScanRequest r=PlanExporter.GSON.fromJson(json,Wire.ScanRequest.class);scans.put(id,new WorldScanner.Job(player.getServerWorld(),r.config(),r.profile()));scanTokens.put(id,r.token());
        } else if(kind.equals("place")) {
            if(placements.containsKey(id)||computations.containsKey(id)||scans.containsKey(id))throw new IllegalArgumentException("已有任务运行");
            Wire.PlacementRequest r=PlanExporter.GSON.fromJson(json,Wire.PlacementRequest.class);
            if(!player.getServerWorld().getRegistryKey().getValue().toString().equals(r.project().dimension()))throw new IllegalArgumentException("项目维度与当前维度不同");
            ServerWorld world=player.getServerWorld();MinecraftServer server=player.getServer();
            UUID generation=UUID.randomUUID();generations.put(id,generation);
            computations.put(id,worker.submit(()->{
                try {EncoderPlan plan=EncodingEngine.plan(r.project());server.execute(()->{
                    if(!generation.equals(generations.get(id)))return;computations.remove(id);generations.remove(id);
                    try {permitted(player);if(player.isDisconnected()||player.getServerWorld()!=world)return;placements.put(id,new PlacementJob(world,ChestFactory.blocks(plan,WorldScanner.block(r.origin()),r.rotation())));}
                    catch(Exception ex){send(player,"error",ex.getMessage());}
                });}catch(Exception ex){server.execute(()->{if(!generation.equals(generations.get(id)))return;computations.remove(id);generations.remove(id);if(!player.isDisconnected())send(player,"error",ex.getMessage());});}
            }));
        } else if(kind.equals("undo")) {
            if(placements.containsKey(id)||computations.containsKey(id))throw new IllegalArgumentException("请先结束生成任务");
            Undo last=undo.get(id);if(last==null)throw new IllegalArgumentException("没有可撤销的本次生成");
            if(last.world()!=player.getServerWorld())throw new IllegalArgumentException("请返回生成时的维度再撤销");
            PlacementJob.UndoResult result=PlacementJob.undoDetailed(last.world(),last.snapshots());if(result.conflicts().isEmpty())undo.remove(id);else undo.put(id,new Undo(last.world(),result.conflicts()));send(player,"status",result.summary());
        }
    }
    private static void tick(MinecraftServer server) {
        for(var it=scans.entrySet().iterator();it.hasNext();) {
            var pair=it.next();ServerPlayerEntity player=server.getPlayerManager().getPlayer(pair.getKey());WorldScanner.Job job=pair.getValue();
            if(player==null||player.getServerWorld()!=job.world||!player.isCreative()||!player.hasPermissionLevel(2)){scanTokens.remove(pair.getKey());it.remove();if(player!=null)send(player,"error","扫描取消：权限或维度已变化");continue;}
            boolean done=job.tick();
            if(done){it.remove();String token=scanTokens.remove(pair.getKey());send(player,"scan_result",PlanExporter.GSON.toJson(new Wire.ScanResult(job.config,job.entries(),token)));}
            else if(server.getTicks()%10==0)send(player,"progress","扫描 "+job.progress()+"/"+job.total());
        }
        for(var it=placements.entrySet().iterator();it.hasNext();) {
            var pair=it.next();PlacementJob job=pair.getValue();ServerPlayerEntity player=server.getPlayerManager().getPlayer(pair.getKey());
            if(player==null||player.getServerWorld()!=job.world||!player.isCreative()||!player.hasPermissionLevel(2))job.cancelled=true;
            if(job.tick()){it.remove();if(!job.snapshots().isEmpty())undo.put(pair.getKey(),new Undo(job.world,job.snapshots()));if(player!=null)send(player,"status",job.result());}
            else if(player!=null&&server.getTicks()%10==0)send(player,"progress",job.progress());
        }
    }
}
