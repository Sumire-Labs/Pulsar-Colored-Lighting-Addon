package com.sumirelabs.pcla.light.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticInterestTest {
    @Test void followingCrossesNegativeChunkBoundariesWithoutDuplicatingTargets() {
        var first=AutomaticInterest.around(-1,0,1);var next=AutomaticInterest.around(0,0,1);
        assertEquals(9,first.size());assertEquals(RegionPublication.key(-1,0),first.getFirst());assertEquals(9,new HashSet<>(first).size());
        var overlap=new HashSet<>(first);overlap.retainAll(next);assertEquals(6,overlap.size());
        assertThrows(IllegalArgumentException.class,()->AutomaticInterest.around(0,0,5));
    }
    @Test void playerDepartureKeepsColumnsStillNeededByAnotherPlayer() {
        var desired=new LinkedHashMap<String,List<Long>>();desired.put("a",AutomaticInterest.around(0,0,1));desired.put("b",AutomaticInterest.around(1,0,1));
        var before=AutomaticInterest.allocate(desired,256);assertEquals(12,before.columns().size());
        desired.remove("a");var after=AutomaticInterest.allocate(desired,256);assertEquals(9,after.columns().size());assertTrue(after.columns().contains(RegionPublication.key(0,0)));
        assertEquals(0,AutomaticInterest.allocate(Map.of(),256).columns().size());
    }
    @Test void worldBudgetAdmitsNearColumnsAcrossPlayersAndNeverExceedsLimit() {
        var desired=new LinkedHashMap<String,List<Long>>();desired.put("a",AutomaticInterest.around(0,0,2));desired.put("b",AutomaticInterest.around(100,100,2));
        var result=AutomaticInterest.allocate(desired,6);assertEquals(6,result.columns().size());assertEquals(3,result.players().get("a").size());assertEquals(3,result.players().get("b").size());
        assertTrue(result.columns().contains(RegionPublication.key(0,0)));assertTrue(result.columns().contains(RegionPublication.key(100,100)));
        assertThrows(UnsupportedOperationException.class,()->result.columns().clear());
    }
}
