package com.sumirelabs.pcla;

import com.sumirelabs.pcla.config.ConfigFile;
import com.sumirelabs.pcla.config.LightingConfig;
import com.sumirelabs.pcla.light.MinecraftColorTable;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION,
        dependencies = "required-after:pulsar@[0.3.1-pcla-test.4,)", acceptableRemoteVersions = "*")
public final class Pcla {
    @net.minecraftforge.fml.common.SidedProxy(clientSide="com.sumirelabs.pcla.client.ClientProxy",serverSide="com.sumirelabs.pcla.network.CommonProxy")
    public static com.sumirelabs.pcla.network.CommonProxy proxy;
    private static final Logger LOG = LogManager.getLogger(Reference.MOD_NAME);
    private static LightingConfig startupConfig;
    private static MinecraftColorTable colorTable;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // No client classes: this entry point is also safe on a dedicated server.
        startupConfig = ConfigFile.load(event.getModConfigurationDirectory().toPath().resolve("pcla.json"));
        proxy.configure(startupConfig,event.getModConfigurationDirectory().toPath());
        com.sumirelabs.pcla.network.ServerSettingsCodec.validate(startupConfig);
        com.sumirelabs.pcla.network.RgbNetwork.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new com.sumirelabs.pcla.network.ServerRgbSync());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new com.sumirelabs.pcla.diagnostics.LightingLatencySequence());
        proxy.initialize();
        LOG.info("PCLA configuration loaded; simulation profile {}", startupConfig.simulationProfile());
        LOG.info("PCLA RGB lighting is enabled by default; pcla.json controls categories and targets. Startup overrides remain available for diagnostics.");
    }

    public static LightingConfig startupConfig() {
        if (startupConfig == null) throw new IllegalStateException("PCLA configuration is not initialized");
        return startupConfig;
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        colorTable = MinecraftColorTable.compile(startupConfig());
        if(com.sumirelabs.pcla.config.RuntimeOptions.AUTOMATIC) {
            com.sumirelabs.pcla.config.RuntimeOptions.automaticRadius();
            try {com.sumirelabs.pulsar.api.lighting.WorldLightingBackend.class.getMethod("bindBlockContinuation",Runnable.class);}
            catch(NoSuchMethodException e){throw new IllegalStateException("Automatic RGB requires the continuation-capable Pulsar companion",e);}
        }
        LOG.info("PCLA compiled {} block states into {} shared color profiles.",
                colorTable.stateCount(), colorTable.profileCount());
        if (com.sumirelabs.pcla.config.RuntimeOptions.worldRgb()
                && (startupConfig().feature(com.sumirelabs.pcla.config.Feature.BLOCK_LIGHT)
                || com.sumirelabs.pcla.config.RuntimeOptions.skyRgb() && startupConfig().feature(com.sumirelabs.pcla.config.Feature.SKY_LIGHT))) {
            if (com.sumirelabs.pulsar.compat.FluidLightBridge.LOADED)
                LOG.info("Fluidlogged API RGB optical input bridge enabled.");
            com.sumirelabs.pulsar.api.lighting.LightingBackendRegistry.register((world, height) ->
                    world.isRemote ? null : new com.sumirelabs.pcla.light.world.ExperimentalRgbBackend(world, height));
            if(com.sumirelabs.pcla.config.RuntimeOptions.AUTOMATIC)LOG.info("Automatic RGB enabled with radius {} chunks; follows registered RGB clients without manual region commands.",com.sumirelabs.pcla.config.RuntimeOptions.automaticRadius());
            else LOG.info("Experimental RGB region backend enabled on servers; use /pcla_live to select a region. Clients may opt into the block tint preview.");
        }
    }

    public static MinecraftColorTable colorTable() {
        if (colorTable == null) throw new IllegalStateException("PCLA color table is not initialized");
        return colorTable;
    }
    public static boolean colorsReady() { return colorTable!=null; }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new com.sumirelabs.pcla.diagnostics.ServerRgbCommand());
        event.registerServerCommand(new com.sumirelabs.pcla.diagnostics.PaletteCommand());
        com.sumirelabs.pcla.diagnostics.FluidPreviewRecovery.restore(event.getServer());
        if (Boolean.getBoolean("pcla.diagnostics"))
            event.registerServerCommand(new com.sumirelabs.pcla.diagnostics.RgbProbeCommand());
        if (com.sumirelabs.pcla.config.RuntimeOptions.worldRgb()) {
            event.registerServerCommand(new com.sumirelabs.pcla.diagnostics.LiveRgbCommand());
            event.registerServerCommand(new com.sumirelabs.pcla.diagnostics.ColumnRgbCommand());
            if(com.sumirelabs.pcla.config.RuntimeOptions.AUTOMATIC)event.registerServerCommand(new com.sumirelabs.pcla.diagnostics.AutomaticRgbCommand());
        }
    }

    @Mod.EventHandler
    public void serverStopping(net.minecraftforge.fml.common.event.FMLServerStoppingEvent event) {
        com.sumirelabs.pcla.diagnostics.LightingLatencySequence.stop();
        com.sumirelabs.pcla.diagnostics.AutomaticRgbCommand.stop();
        com.sumirelabs.pcla.network.ServerRgbSync.clear();
        com.sumirelabs.pcla.diagnostics.LiveRgbCommand.stopTests();
        com.sumirelabs.pcla.diagnostics.ColumnRgbCommand.stop();
    }
}
