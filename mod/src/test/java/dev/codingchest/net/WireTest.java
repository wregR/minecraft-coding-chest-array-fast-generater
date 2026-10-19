package dev.codingchest.net;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WireTest {
    @Test void boundedFragmentRoundTrip(){String text="含水楼梯".repeat(15000);List<Wire.Message> parts=new ArrayList<>();Wire.send("scan_result",text,parts::add);Wire.Assembler assembler=new Wire.Assembler();String result=null;for(var part:parts){var value=assembler.accept(part);if(value.isPresent())result=value.get();}assertEquals(text,result);}
    @Test void rejectIncompleteOrInvalidSequences(){Wire.Assembler a=new Wire.Assembler();assertThrows(IllegalArgumentException.class,()->a.accept(new Wire.Message("a","scan",1,2,"x")));assertThrows(IllegalArgumentException.class,()->a.accept(new Wire.Message("a","scan",0,999,"x")));a.accept(new Wire.Message("a","scan",0,2,"x"));assertThrows(IllegalArgumentException.class,()->a.accept(new Wire.Message("a","scan",0,2,"x")));}
}
