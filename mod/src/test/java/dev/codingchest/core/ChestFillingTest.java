package dev.codingchest.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChestFillingTest {
    @Test void exhaustEveryMixtureWithExactIntegerComparatorArithmetic(){
        int configurations=0,triggers=0;
        for(int size=0;size<=50;size++)for(int n16=0;n16<=size;n16++){
            var tail=ChestFilling.tail(n16);assertEquals(4,tail.size());
            int units=50+3*n16;
            for(var filler:tail){assertTrue(filler.count()>=1&&filler.count()<=(filler.shears()?1:64));units+=filler.count()*(filler.shears()?64:1);}
            assertEquals(247,units);assertEquals(2,1+units*14/3456);
            for(int i=0;i<size;i++){assertEquals(1,1+(units-(i<n16?4:1))*14/3456);triggers++;}
            configurations++;
        }
        assertEquals(1326,configurations);assertEquals(44200,triggers);
    }
    @Test void preservesLegacyAndHandlesBoundaryTransitions(){
        assertEquals(List.of(new ChestFilling.Filler(false,5),new ChestFilling.Filler(true,1),new ChestFilling.Filler(true,1),new ChestFilling.Filler(true,1)),ChestFilling.tail(0));
        assertEquals(List.of(new ChestFilling.Filler(false,44),new ChestFilling.Filler(false,1),new ChestFilling.Filler(false,1),new ChestFilling.Filler(false,1)),ChestFilling.tail(50));
        for(int n:new int[]{1,2,22,23,43,44})assertEquals(247,50+3*n+ChestFilling.tail(n).stream().mapToInt(f->f.count()*(f.shears()?64:1)).sum());
    }
    @Test void rejectsInvalidCountsAndStackSizes(){
        assertThrows(IllegalArgumentException.class,()->ChestFilling.tail(-1));assertThrows(IllegalArgumentException.class,()->ChestFilling.tail(51));
        assertTrue(ChestFilling.supported(16));assertTrue(ChestFilling.supported(64));
        for(int size:new int[]{0,1,8,32,99})assertFalse(ChestFilling.supported(size));
    }
}
