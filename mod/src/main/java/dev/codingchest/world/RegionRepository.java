package dev.codingchest.world;
import dev.codingchest.core.SavedRegion;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
/** Versioned selections isolated by world/server hash; no server address is stored. */
public final class RegionRepository {
    private record Document(int version,List<SavedRegion> regions) {}
    private final Path file;
    public RegionRepository(Path directory,String scope){if(!scope.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("世界范围标识无效");file=directory.resolve(scope+".json");}
    public List<SavedRegion> load()throws IOException{
        if(!Files.exists(file))return List.of();if(Files.size(file)>2_000_000)throw new IOException("选区库过大");
        Document doc=PlanExporter.GSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),Document.class);
        if(doc==null||doc.version()!=1||doc.regions()==null||doc.regions().size()>256)throw new IOException("选区库版本或数量无效");
        Set<String> ids=new HashSet<>();for(SavedRegion r:doc.regions())if(!ids.add(r.id()))throw new IOException("选区ID重复");return List.copyOf(doc.regions());
    }
    public void save(List<SavedRegion> regions)throws IOException{
        if(regions.size()>256)throw new IOException("每个世界最多保存256个选区");Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp-"+UUID.randomUUID());
        Files.writeString(temp,PlanExporter.GSON.toJson(new Document(1,List.copyOf(regions))),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
}
