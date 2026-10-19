package dev.codingchest.world;
import dev.codingchest.core.SavedRegion;
import dev.codingchest.core.Model.Pos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RegionRepositoryTest {
    @Test void multipleRegionsPersistAndWorldScopesDoNotMix(@TempDir Path directory)throws Exception{
        var first=new RegionRepository(directory,"a".repeat(32));var other=new RegionRepository(directory,"b".repeat(32));
        var a=new SavedRegion("one","南段","minecraft:overworld",new Pos(0,0,0),new Pos(5,6,7));var b=new SavedRegion("two","北段","minecraft:overworld",new Pos(10,0,0),new Pos(15,6,7));
        first.save(List.of(a,b));assertEquals(List.of(a,b),new RegionRepository(directory,"a".repeat(32)).load());assertTrue(other.load().isEmpty());first.save(List.of(b));assertEquals(List.of(b),first.load());
    }
}
