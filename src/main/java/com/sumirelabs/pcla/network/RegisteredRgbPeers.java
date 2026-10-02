package com.sumirelabs.pcla.network;

import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/** Observe Forge REGISTER messages before selecting a protocol; never infer support from a mod version. */
public final class RegisteredRgbPeers {
    private static final PeerChannels<NetworkManager> CHANNELS=new PeerChannels<>();
    public static boolean supportsV2(NetworkManager manager) { return CHANNELS.supportsV2(manager); }
    public static boolean supportsV3(NetworkManager manager) { return CHANNELS.supportsV3(manager); }
    public static boolean supportsV4(NetworkManager manager) { return CHANNELS.supportsV4(manager); }
    public static boolean supportsSettings(NetworkManager manager) { return CHANNELS.supportsSettings(manager); }
    @SubscribeEvent public void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        CHANNELS.registration(event.getManager(),event.getOperation(),event.getRegistrations());
        if(Boolean.getBoolean("pcla.networkDiagnostics") && event.getRegistrations().contains(PeerChannels.V2))
            org.apache.logging.log4j.LogManager.getLogger("PCLA").info("RGB v2 peer registration side={} operation={}",event.getSide(),event.getOperation());
    }
    @SubscribeEvent public void disconnected(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) { CHANNELS.disconnected(event.getManager()); }
    @SubscribeEvent public void clientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) { CHANNELS.disconnected(event.getManager()); }
}
