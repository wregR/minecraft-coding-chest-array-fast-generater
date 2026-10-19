package dev.codingchest.core;

import java.util.*;

/** Pure capacity arithmetic: a paper is one unit, a 16-stack item four, shears 64. */
public final class ChestFilling {
    public static final int CODING_SLOTS=50, TARGET_UNITS=247;
    private ChestFilling() {}
    public static boolean supported(int maxStack){return maxStack==16||maxStack==64;}
    public record Filler(boolean shears,int count) {}
    public static List<Filler> tail(int count16){
        if(count16<0||count16>CODING_SLOTS)throw new IllegalArgumentException("16堆叠种数应为0～50");
        // The first 50 slots each contain one item or one paper: 50 + 3*count16 units.
        int remaining=TARGET_UNITS-CODING_SLOTS-3*count16;
        int shears=(remaining-4)/63; // Reserve one paper per tail slot before replacing 1 by 64.
        int paper=remaining-3-63*shears;
        List<Filler> result=new ArrayList<>();result.add(new Filler(false,paper));
        for(int i=0;i<3-shears;i++)result.add(new Filler(false,1));
        for(int i=0;i<shears;i++)result.add(new Filler(true,1));
        return List.copyOf(result);
    }
}
