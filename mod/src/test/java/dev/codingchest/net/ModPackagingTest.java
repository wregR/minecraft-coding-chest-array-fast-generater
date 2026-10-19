package dev.codingchest.net;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ModPackagingTest {
    @Test void entrypointsAreOutsideTheMixinOnlyPackage()throws Exception{
        var loader=getClass().getClassLoader();
        try(var config=new InputStreamReader(java.util.Objects.requireNonNull(loader.getResourceAsStream("codingchest.client.mixins.json")),StandardCharsets.UTF_8);var metadata=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("build/resources/main/fabric.mod.json"),StandardCharsets.UTF_8)){
            String restricted=JsonParser.parseReader(config).getAsJsonObject().get("package").getAsString()+".";
            var mod=JsonParser.parseReader(metadata).getAsJsonObject();assertEquals("codingchest",mod.get("id").getAsString());var entries=mod.getAsJsonObject("entrypoints");
            for(var group:entries.entrySet())for(var entry:group.getValue().getAsJsonArray())assertFalse(entry.getAsString().startsWith(restricted),"Ordinary entrypoint must never be in a mixin-only package");
        }
    }
}
