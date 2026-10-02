package com.sumirelabs.pcla.light;

import com.sumirelabs.pcla.config.LightingConfig;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraftforge.common.property.IExtendedBlockState;
import java.util.*;

/** Startup adapter; never asks a block for world-dependent light while compiling its color rules. */
public final class MinecraftColorTable {
    private final CompiledColorTable<IBlockState> table;
    private final Set<IBlockState> renderAllowed;

    private MinecraftColorTable(CompiledColorTable<IBlockState> table,Set<IBlockState> renderAllowed) { this.table = table; this.renderAllowed=renderAllowed; }

    public static MinecraftColorTable compile(LightingConfig config) {
        if (!config.hasRgbLane())
            return new MinecraftColorTable(CompiledColorTable.compile(config, List.of()),Set.of());
        var definitions = definitions();
        Set<IBlockState> allowed=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var state:definitions)if(config.resolve(com.sumirelabs.pcla.config.RuleKind.BLOCK_RENDERING,state.registryId(),state.properties(),null).enabled())allowed.add(state.identity());
        return new MinecraftColorTable(CompiledColorTable.compile(config, definitions),Collections.unmodifiableSet(allowed));
    }

    /** Enumerate listed states without reading world-dependent emission/opacity. */
    public static List<CompiledColorTable.State<IBlockState>> definitions() {
        var definitions = new ArrayList<CompiledColorTable.State<IBlockState>>();
        for (Block block : Block.REGISTRY) {
            for (IBlockState listed : block.getBlockState().getValidStates()) {
                IBlockState state=clean(listed);
                String id=Objects.requireNonNull(state.getBlock().getRegistryName(),"state owner registry name").toString();
                var properties = new HashMap<String, String>();
                for (IProperty<?> property : state.getPropertyKeys())
                    properties.put(property.getName(), propertyValue(state, property));
                definitions.add(new CompiledColorTable.State<>(state, id, properties));
            }
        }
        return List.copyOf(definitions);
    }

    private static <T extends Comparable<T>> String propertyValue(IBlockState state, IProperty<T> property) {
        return property.getName(state.getValue(property));
    }

    public static IBlockState clean(IBlockState state) {
        return state instanceof IExtendedBlockState extended ? extended.getClean() : state;
    }
    /** Compile display decisions without changing the common/server simulation table. */
    public static Set<IBlockState> compileRenderRules(com.sumirelabs.pcla.config.ClientRenderPolicy policy) {
        Set<IBlockState> result=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var state:definitions())
            if(policy.allowed(com.sumirelabs.pcla.config.RuleKind.BLOCK_RENDERING,state.registryId(),state.properties()))result.add(state.identity());
        return Collections.unmodifiableSet(result);
    }

    public CompiledColor get(IBlockState state) { return table.get(clean(state)); }
    public boolean renderAllowed(IBlockState state) { return renderAllowed.contains(clean(state)); }
    public int stateCount() { return table.stateCount(); }
    public int profileCount() { return table.profileCount(); }
}
