package com.sumirelabs.pcla.network;

import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Dedicated-server class loading never references Minecraft client classes. */
public class CommonProxy {
    public void initialize() {}
    public void receive(RegionFrame frame,MessageContext context) {}
    public void receiveDelta(SectionDelta delta,MessageContext context) {}
    public void receiveSettings(ServerSettings settings,MessageContext context) {}
    public void configure(com.sumirelabs.pcla.config.LightingConfig local,java.nio.file.Path directory) {}
}
