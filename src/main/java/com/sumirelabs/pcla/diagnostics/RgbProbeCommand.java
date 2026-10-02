package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pcla.light.CompiledColor;
import com.sumirelabs.pcla.light.ColorResolver;
import com.sumirelabs.pcla.light.Rgb;
import com.sumirelabs.pcla.light.engine.*;
import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pulsar.compat.FluidLightBridge;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.properties.IProperty;
import net.minecraft.command.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import java.nio.file.*;
import java.util.*;

/** Opt-in development diagnostic. Reads the world; all edits and RGB publications stay in detached memory. */
public final class RgbProbeCommand extends CommandBase {
    private static final int RADIUS = 18, SIDE = 2 * RADIUS + 1;
    // Core directions +X,-X,+Y,-Y,+Z,-Z -> Pulsar AxisDirection +X,-X,+Z,-Z,+Y,-Y.
    private static final int[] PULSAR_DIRECTION = {0, 1, 4, 5, 2, 3};
    @Override public String getName() { return "pcla_probe"; }
    @Override public String getUsage(ICommandSender sender) { return "/pcla_probe"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 0) throw new WrongUsageException(getUsage(sender));
        if (FluidLightBridge.LOADED) throw new CommandException("RGB probe does not yet model fluidlogged source colors");
        World world = sender.getEntityWorld();
        BlockPos center = sender.getPosition(), min = center.add(-RADIUS, -RADIUS, -RADIUS);
        BlockPos max = center.add(RADIUS, RADIUS, RADIUS);
        if (!world.isAreaLoaded(min, max)) throw new CommandException("The probe requires a fully loaded 37-cube region");
        View rgb = new View(), scalar = new View();
        int[] ordinary = new int[rgb.cellCount()];
        int[] outgoing = new int[rgb.cellCount()];
        var position = new BlockPos.MutableBlockPos();
        int sourceCount = 0, firstSource = -1, coloredSources = 0, compiledMismatches = 0;
        var resolver = new ColorResolver(Pcla.startupConfig());
        for (int cell = 0; cell < rgb.cellCount(); cell++) {
            int x = min.getX() + cell % SIDE, z = min.getZ() + cell / SIDE % SIDE, y = min.getY() + cell / (SIDE * SIDE);
            position.setPos(x, y, z);
            IBlockState state = world.getBlockState(position);
            int info = LightInfo.resolveContextual(LightInfo.of(state), state, world, position, x, y, z);
            CompiledColor color = Pcla.colorTable().get(state);
            int emission = LightInfo.emission(info);
            rgb.sources[cell] = color.emission(emission);
            scalar.sources[cell] = emission * 0x111;
            String id = Objects.requireNonNull(state.getBlock().getRegistryName()).toString();
            var properties = new HashMap<String, String>();
            for (IProperty<?> property : state.getPropertyKeys())
                properties.put(property.getName(), propertyValue(state, property));
            if (rgb.sources[cell] != resolver.emission(id, properties, emission).packed()) compiledMismatches++;
            if (rgb.sources[cell] != emission * 0x111) coloredSources++;
            if (emission > 0) { sourceCount++; if (firstSource < 0) firstSource = cell; }
            outgoing[cell] = LightInfo.faceBits(info);
            for (int direction = 0; direction < 6; direction++) {
                int loss = LightInfo.absorption(info, state, PULSAR_DIRECTION[direction]);
                rgb.incoming[direction][cell] = color.absorption(loss);
                if (rgb.incoming[direction][cell] != resolver.absorption(id, properties, loss).packed()) compiledMismatches++;
                scalar.incoming[direction][cell] = loss * 0x111;
            }
            position.setPos(x, y, z);
            ordinary[cell] = world.getLightFor(EnumSkyBlock.BLOCK, position);
        }
        rgb.blocked = outgoing; scalar.blocked = outgoing;
        var report = new JsonObject();
        report.addProperty("version", "foundation.3");
        report.addProperty("scope", "detached world snapshot; no world RGB rendering or updates");
        report.addProperty("dimension", world.provider.getDimension());
        report.addProperty("center", center.toString());
        report.addProperty("cells", rgb.cellCount());
        report.addProperty("sourceCells", sourceCount);
        report.addProperty("coloredSourceCells", coloredSources);
        report.addProperty("compiledProfileComparisons", rgb.cellCount() * 7);
        report.addProperty("compiledProfileMismatches", compiledMismatches);
        var phases = new JsonArray();
        var engine = new RgbBlockPropagator(rgb);
        phases.add(check("initial", rgb, engine.relight()));
        new RgbBlockPropagator(scalar).relight();
        int scalarCompared = 0, scalarMismatches = 0, scalarLit = 0;
        // A 15-cell margin excludes every possible positive block-light contribution from outside the view.
        for (int y = 15; y < SIDE - 15; y++) for (int z = 15; z < SIDE - 15; z++) for (int x = 15; x < SIDE - 15; x++) {
            int cell = x + SIDE * (z + SIDE * y);
            scalarCompared++;
            if (ordinary[cell] > 0) scalarLit++;
            if (scalar.light(cell) != ordinary[cell] * 0x111) scalarMismatches++;
        }
        report.addProperty("scalarInteriorCompared", scalarCompared);
        report.addProperty("scalarInteriorLit", scalarLit);
        report.addProperty("scalarInteriorMismatches", scalarMismatches);
        if (firstSource >= 0) {
            int old = rgb.sources[firstSource];
            // Force a different tint even for a white crystal, preserving peak intensity.
            int peak = Rgb.unpack(old).max();
            rgb.sources[firstSource] = old == peak << 8 ? peak : peak << 8;
            phases.add(check("rotate_source_channels", rgb, engine.update(firstSource)));
            rgb.sources[firstSource] = 0;
            phases.add(check("remove_source", rgb, engine.update(firstSource)));
            rgb.sources[firstSource] = old;
            phases.add(check("restore_source", rgb, engine.update(firstSource)));
        }
        int middle = RADIUS + SIDE * (RADIUS + SIDE * RADIUS);
        int oldFace = rgb.blocked[middle];
        rgb.blocked[middle] = 63;
        phases.add(check("close_faces", rgb, engine.update(middle)));
        rgb.blocked[middle] = oldFace;
        phases.add(check("restore_faces", rgb, engine.update(middle)));
        int[] removed = new int[sourceCount];
        for (int cell = 0, index = 0; cell < rgb.cellCount(); cell++) if (rgb.sources[cell] != 0) {
            removed[index++] = cell; rgb.sources[cell] = 0;
        }
        phases.add(check("remove_all_sources", rgb, engine.update(removed)));
        report.add("phases", phases);
        try {
            Path output = Path.of("logs", "pcla-rgb-probe-" + System.currentTimeMillis() + ".json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            sender.sendMessage(new TextComponentString("PCLA detached RGB probe saved: " + output
                    + "; sources=" + sourceCount + "; scalar mismatches=" + scalarMismatches));
        } catch (java.io.IOException e) { throw new CommandException("Cannot save RGB probe: " + e.getMessage()); }
    }

    private static JsonObject check(String name, View view, RgbBlockPropagator.Work work) {
        int[] expected = reference(view);
        int mismatches = 0, lit = 0;
        var snapshot = view.volume.publish();
        for (int cell = 0; cell < expected.length; cell++) {
            if (snapshot.get(cell) != expected[cell]) mismatches++;
            if (snapshot.get(cell) != 0) lit++;
        }
        var result = new JsonObject();
        result.addProperty("name", name); result.addProperty("compared", expected.length);
        result.addProperty("mismatches", mismatches); result.addProperty("litCells", lit);
        result.addProperty("decreaseVisits", work.decreaseVisits());
        result.addProperty("increaseVisits", work.increaseVisits()); result.addProperty("writes", work.writes());
        result.addProperty("absorptionReads", work.absorptionReads());
        return result;
    }

    private static <T extends Comparable<T>> String propertyValue(IBlockState state, IProperty<T> property) {
        return property.getName(state.getValue(property));
    }

    /** Deliberately slow independent three-scalar relaxation reference for diagnostics only. */
    private static int[] reference(View view) {
        int[] result = new int[view.cellCount()];
        for (int shift = 0; shift <= 8; shift += 4) {
            int[] lane = new int[result.length];
            for (int cell = 0; cell < lane.length; cell++) lane[cell] = view.sources[cell] >> shift & 15;
            for (int pass = 0; pass < 16; pass++) {
                boolean changed = false;
                for (int cell = 0; cell < lane.length; cell++) for (int direction = 0; direction < 6; direction++) {
                    int neighbor = view.neighbor(cell, direction);
                    if (neighbor < 0) continue;
                    int loss = Math.max(1, view.absorption(neighbor, direction ^ 1) >> shift & 15);
                    int candidate = lane[neighbor] - loss;
                    if (candidate > lane[cell]) { lane[cell] = candidate; changed = true; }
                }
                if (!changed) break;
                if (pass == 15) throw new IllegalStateException("Scalar reference failed to converge");
            }
            for (int cell = 0; cell < lane.length; cell++) result[cell] |= lane[cell] << shift;
        }
        return result;
    }

    private static final class View implements RgbBlockAccess {
        final RgbLightVolume volume = new RgbLightVolume(SIDE, SIDE, SIDE);
        final int[] sources = new int[SIDE * SIDE * SIDE];
        final int[][] incoming = new int[6][sources.length];
        int[] blocked;
        public int cellCount() { return sources.length; }
        public int neighbor(int cell, int direction) {
            int x = cell % SIDE, z = cell / SIDE % SIDE, y = cell / (SIDE * SIDE);
            return switch (direction) {
                case 0 -> x + 1 < SIDE ? cell + 1 : -1;
                case 1 -> x > 0 ? cell - 1 : -1;
                case 2 -> y + 1 < SIDE ? cell + SIDE * SIDE : -1;
                case 3 -> y > 0 ? cell - SIDE * SIDE : -1;
                case 4 -> z + 1 < SIDE ? cell + SIDE : -1;
                case 5 -> z > 0 ? cell - SIDE : -1;
                default -> throw new IllegalArgumentException();
            };
        }
        public int emission(int cell) { return sources[cell]; }
        public int absorption(int cell, int direction) {
            if ((blocked[cell] & 1 << PULSAR_DIRECTION[direction]) != 0) return 0xfff;
            int target = neighbor(cell, direction);
            return target < 0 ? 0xfff : incoming[direction ^ 1][target];
        }
        public int light(int cell) { return volume.getUpdating(cell); }
        public void light(int cell, int color) { volume.set(cell, color); }
    }
}
