package dev.codingchest.world;

import dev.codingchest.core.Model.*;
import dev.codingchest.core.EncodingEngine;
import dev.codingchest.core.EncoderGeometry;
import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class PlanExporter {
    public static final Gson GSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public record ExportResult(Path directory,Path schematic,Path bundle) {}
    public static byte[] litematic(EncoderPlan plan,RegistryWrapper.WrapperLookup registry,int rotation) throws IOException {
        EncodingEngine.verify(plan);List<ChestFactory.Half> halves=ChestFactory.blocks(plan,BlockPos.ORIGIN,rotation);
        int minX=halves.stream().mapToInt(h->h.pos().getX()).min().orElse(0),minZ=halves.stream().mapToInt(h->h.pos().getZ()).min().orElse(0);
        int sx=halves.stream().mapToInt(h->h.pos().getX()).max().orElse(0)-minX+1,sz=halves.stream().mapToInt(h->h.pos().getZ()).max().orElse(0)-minZ+1;
        int sy=halves.stream().mapToInt(h->h.pos().getY()).max().orElse(0)+1;
        List<NbtCompound> palette=new ArrayList<>();NbtCompound air=new NbtCompound();air.putString("Name","minecraft:air");palette.add(air);
        int[] states=new int[sx*sz*sy];NbtList entities=new NbtList();
        for(ChestFactory.Half h:halves) {
            NbtCompound state=NbtHelper.fromBlockState(h.state());int index=palette.indexOf(state);if(index<0){index=palette.size();palette.add(state);}
            int x=h.pos().getX()-minX,z=h.pos().getZ()-minZ;states[(h.pos().getY()*sz+z)*sx+x]=index;
            NbtCompound entity=ChestFactory.entityNbt(h,registry);entity.putInt("x",x);entity.putInt("z",z);entities.add(entity);
        }
        int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));long[] words=new long[(states.length*bits+63)/64];
        for(int i=0;i<states.length;i++) {int off=i*bits,word=off/64,shift=off%64;words[word]|=(long)states[i]<<shift;if(shift+bits>64)words[word+1]|=(long)states[i]>>>(64-shift);}
        NbtList palettes=new NbtList();palette.forEach(palettes::add);NbtCompound region=new NbtCompound();
        region.put("Position",xyz(minX,0,minZ));region.put("Size",xyz(sx,sy,sz));region.put("BlockStatePalette",palettes);region.putLongArray("BlockStates",words);region.put("TileEntities",entities);
        for(String key:List.of("Entities","PendingBlockTicks","PendingFluidTicks"))region.put(key,new NbtList());
        NbtCompound regions=new NbtCompound();regions.put("encoder",region);NbtCompound root=new NbtCompound();
        root.putInt("Version",6);root.putInt("SubVersion",1);root.putInt("MinecraftDataVersion",SharedConstants.getGameVersion().getSaveVersion().getId());root.put("Regions",regions);
        NbtCompound metadata=new NbtCompound();metadata.putString("Name","Coding Chest 编码箱");metadata.putString("Author","wregR");metadata.putString("Description","编码信息见同包 signals.csv");
        metadata.putInt("RegionCount",1);metadata.putLong("TimeCreated",System.currentTimeMillis());metadata.putLong("TimeModified",System.currentTimeMillis());metadata.putInt("TotalBlocks",halves.size());metadata.putInt("TotalVolume",states.length);metadata.put("EnclosingSize",xyz(sx,sy,sz));root.put("Metadata",metadata);
        ByteArrayOutputStream out=new ByteArrayOutputStream();NbtIo.writeCompressed(root,out);return out.toByteArray();
    }
    private static NbtCompound xyz(int x,int y,int z) {NbtCompound n=new NbtCompound();n.putInt("x",x);n.putInt("y",y);n.putInt("z",z);return n;}
    public static Path export(Path directory,LayoutProject project,EncoderPlan plan,RegistryWrapper.WrapperLookup registry,int rotation) throws IOException {
        Files.createDirectories(directory);Path target=directory.resolve("encoder-"+System.currentTimeMillis()+".zip");
        StringBuilder mapping=new StringBuilder("item_id,group,slice,waterway,code\n"),signals=new StringBuilder("chest,layer,column,field,mask,value,part,item_count,items\n");
        plan.addresses().forEach((id,a)->mapping.append(id).append(',').append(a.group()).append(',').append(a.slice()).append(',').append(a.lane()).append(',').append(quote(a.display(plan.profile()))).append('\n'));
        Map<String,Integer> materials=new TreeMap<>();materials.put("minecraft:chest",plan.chests().size()*2);
        materials.put("minecraft:oak_sign",EncoderGeometry.labels(plan,rotation).size());
        List<EncoderGeometry.Slot> slots=EncoderGeometry.slots(plan);
        for(int i=0;i<plan.chests().size();i++) {EncoderChest c=plan.chests().get(i);EncoderGeometry.Slot slot=slots.get(i);signals.append(i+1).append(',').append(slot.level()).append(',').append(slot.column()).append(',').append(c.signal().field()).append(',').append(c.signal().mask()).append(',').append(c.signal().value()).append(',').append(c.part()).append(',').append(c.items().size()).append(',').append(quote(String.join(";",c.items()))).append('\n');for(String id:c.items())materials.merge(id,1,Integer::sum);materials.merge("minecraft:paper [ENCODER 占位]",55-c.items().size(),Integer::sum);materials.merge("minecraft:shears",3,Integer::sum);}
        StringBuilder material=new StringBuilder("item,count\n");materials.forEach((id,n)->material.append(quote(id)).append(',').append(n).append('\n'));
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(target))) {
            put(zip,"encoder.litematic",litematic(plan,registry,rotation));put(zip,"mapping.csv",mapping.toString().getBytes(StandardCharsets.UTF_8));put(zip,"signals.csv",signals.toString().getBytes(StandardCharsets.UTF_8));put(zip,"materials.csv",material.toString().getBytes(StandardCharsets.UTF_8));put(zip,"project.json",GSON.toJson(project).getBytes(StandardCharsets.UTF_8));put(zip,"plan.json",GSON.toJson(plan).getBytes(StandardCharsets.UTF_8));
            put(zip,"orientation.json",GSON.toJson(Map.of("rotation",Math.floorMod(rotation,4),"positiveDirection",new String[]{"+X","+Z","-X","-Z"}[Math.floorMod(rotation,4)],"chestAnchor",new Pos(0,0,0),"labels",EncoderGeometry.labels(plan,rotation))).getBytes(StandardCharsets.UTF_8));
        }return target;
    }
    public static ExportResult exportFiles(Path root,LayoutProject project,EncoderPlan plan,RegistryWrapper.WrapperLookup registry,int rotation)throws IOException {
        EncodingEngine.requireComplete(project);Files.createDirectories(root);
        String name=project.name().replaceAll("[^\\p{L}\\p{N}_.-]","_");if(name.isBlank())name="encoder";name=name.substring(0,Math.min(60,name.length()));
        String stamp=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path directory=root.resolve(name+"-"+stamp+"-"+UUID.randomUUID().toString().substring(0,8));Files.createDirectory(directory);
        Path bundle=export(directory,project,plan,registry,rotation);Map<String,byte[]> files=new LinkedHashMap<>();
        Set<String> allowed=Set.of("encoder.litematic","mapping.csv","signals.csv","materials.csv","project.json","plan.json","orientation.json");
        try(ZipInputStream in=new ZipInputStream(Files.newInputStream(bundle))){for(ZipEntry entry;(entry=in.getNextEntry())!=null;){if(!allowed.contains(entry.getName()))throw new IOException("非法导出文件名");files.put(entry.getName(),in.readAllBytes());}}
        for(var file:files.entrySet())if(!file.getKey().endsWith(".litematic"))atomicWrite(directory.resolve(file.getKey()),file.getValue());
        Path schematic=directory.resolve("encoder.litematic");atomicWrite(schematic,Objects.requireNonNull(files.get("encoder.litematic")));
        return new ExportResult(directory,schematic,bundle);
    }
    private static void atomicWrite(Path file,byte[] bytes)throws IOException{Path temp=file.resolveSibling(file.getFileName()+".tmp-"+UUID.randomUUID());Files.write(temp,bytes,StandardOpenOption.CREATE_NEW);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file);}}
    private static String quote(String s) {return "\""+s.replace("\"","\"\"")+"\"";}
    private static void put(ZipOutputStream out,String name,byte[] bytes)throws IOException{out.putNextEntry(new ZipEntry(name));out.write(bytes);out.closeEntry();}
}
