package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.Pcla;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;
import java.util.UUID;

public final class RgbNetwork {
    public static final SimpleNetworkWrapper CHANNEL=NetworkRegistry.INSTANCE.newSimpleChannel("pcla_rgb_v1");
    public static final SimpleNetworkWrapper CHANNEL_V2=NetworkRegistry.INSTANCE.newSimpleChannel(PeerChannels.V2);
    public static final SimpleNetworkWrapper CHANNEL_V3=NetworkRegistry.INSTANCE.newSimpleChannel(PeerChannels.V3);
    public static final SimpleNetworkWrapper CHANNEL_V4=NetworkRegistry.INSTANCE.newSimpleChannel(PeerChannels.V4);
    public static final SimpleNetworkWrapper SETTINGS=NetworkRegistry.INSTANCE.newSimpleChannel(PeerChannels.SETTINGS);
    public static SimpleNetworkWrapper channel(int protocol) { return protocol==4?CHANNEL_V4:protocol==3?CHANNEL_V3:protocol==2 ? CHANNEL_V2 : CHANNEL; }
    public static void register() {
        CHANNEL.registerMessage(FrameHandler.class,FrameMessage.class,0,Side.CLIENT);
        CHANNEL.registerMessage(AckHandler.class,AckMessage.class,1,Side.SERVER);
        CHANNEL_V2.registerMessage(FrameHandlerV2.class,FrameMessageV2.class,0,Side.CLIENT);
        CHANNEL_V2.registerMessage(AckHandler.class,AckMessage.class,1,Side.SERVER);
        CHANNEL_V3.registerMessage(FrameHandlerV3.class,FrameMessageV3.class,0,Side.CLIENT);
        CHANNEL_V3.registerMessage(AckHandler.class,AckMessage.class,1,Side.SERVER);
        CHANNEL_V4.registerMessage(FrameHandlerV4.class,FrameMessageV4.class,0,Side.CLIENT);
        CHANNEL_V4.registerMessage(AckHandler.class,AckMessage.class,1,Side.SERVER);
        CHANNEL_V4.registerMessage(ResyncHandler.class,ResyncMessage.class,2,Side.SERVER);
        SETTINGS.registerMessage(SettingsHandler.class,SettingsMessage.class,0,Side.CLIENT);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new RegisteredRgbPeers());
    }
    public static final class SettingsMessage implements IMessage {
        public ServerSettings settings;
        public SettingsMessage() {}
        public SettingsMessage(ServerSettings settings){this.settings=settings;}
        public void fromBytes(ByteBuf in){settings=ServerSettingsCodec.decode(in);}
        public void toBytes(ByteBuf out){ServerSettingsCodec.encode(settings,out);}
    }
    public static final class SettingsHandler implements IMessageHandler<SettingsMessage,IMessage> {
        public IMessage onMessage(SettingsMessage message,MessageContext context){Pcla.proxy.receiveSettings(message.settings,context);return null;}
    }
    public static final class FrameMessageV4 implements IMessage {
        public RegionFrameV4Codec.Packet packet;
        public FrameMessageV4() {}
        public FrameMessageV4(RegionFrameV4Codec.Packet packet){this.packet=packet;}
        public void fromBytes(ByteBuf in){packet=RegionFrameV4Codec.decode(in);}
        public void toBytes(ByteBuf out){RegionFrameV4Codec.encode(packet,out);}
    }
    public static final class FrameHandlerV4 implements IMessageHandler<FrameMessageV4,IMessage> {
        public IMessage onMessage(FrameMessageV4 message,MessageContext context) {
            if(message.packet.frame()!=null)Pcla.proxy.receive(message.packet.frame(),context);
            else Pcla.proxy.receiveDelta(message.packet.delta(),context);
            return null;
        }
    }
    public static final class ResyncMessage implements IMessage {
        public UUID session;public long epoch;public int slot;
        public ResyncMessage() {}
        public ResyncMessage(UUID session,long epoch,int slot){this.session=session;this.epoch=epoch;this.slot=slot;}
        public void fromBytes(ByteBuf in) {
            if(in.readableBytes()!=26)throw new IllegalArgumentException("Invalid RGB resync request");
            session=new UUID(in.readLong(),in.readLong());epoch=in.readLong();slot=in.readUnsignedShort();
            if(epoch<=0 || slot<4 || slot>=256)throw new IllegalArgumentException("Invalid RGB resync header");
        }
        public void toBytes(ByteBuf out){out.writeLong(session.getMostSignificantBits());out.writeLong(session.getLeastSignificantBits());out.writeLong(epoch);out.writeShort(slot);}
    }
    public static final class ResyncHandler implements IMessageHandler<ResyncMessage,IMessage> {
        public IMessage onMessage(ResyncMessage message,MessageContext context) {
            var player=context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(()->ServerRgbSync.resynchronize(player,message.session,message.epoch,message.slot));return null;
        }
    }
    public static final class FrameMessageV3 implements IMessage {
        public RegionFrame frame;
        public FrameMessageV3() {}
        public FrameMessageV3(RegionFrame frame){this.frame=frame;}
        public void fromBytes(ByteBuf in){frame=RegionFrameV3Codec.decode(in);}
        public void toBytes(ByteBuf out){RegionFrameV3Codec.encode(frame,out);}
    }
    public static final class FrameHandlerV3 implements IMessageHandler<FrameMessageV3,IMessage> {
        public IMessage onMessage(FrameMessageV3 message,MessageContext context){Pcla.proxy.receive(message.frame,context);return null;}
    }
    public static final class FrameMessageV2 implements IMessage {
        public RegionFrame frame;
        public FrameMessageV2() {}
        public FrameMessageV2(RegionFrame frame) { this.frame=frame; }
        public void fromBytes(ByteBuf in) { frame=RegionFrameV2Codec.decode(in); }
        public void toBytes(ByteBuf out) { RegionFrameV2Codec.encode(frame,out); }
    }
    public static final class FrameHandlerV2 implements IMessageHandler<FrameMessageV2,IMessage> {
        public IMessage onMessage(FrameMessageV2 message,MessageContext context) { Pcla.proxy.receive(message.frame,context); return null; }
    }
    public static final class FrameMessage implements IMessage {
        public RegionFrame frame;
        public FrameMessage() {}
        public FrameMessage(RegionFrame frame) { this.frame=frame; }
        public void fromBytes(ByteBuf in) { frame=RegionFrameCodec.decode(in); }
        public void toBytes(ByteBuf out) { RegionFrameCodec.encode(frame,out); }
    }
    public static final class FrameHandler implements IMessageHandler<FrameMessage,IMessage> {
        public IMessage onMessage(FrameMessage message,MessageContext context) {
            Pcla.proxy.receive(message.frame,context); return null;
        }
    }
    public static final class AckMessage implements IMessage {
        public UUID session;
        public AckMessage() {}
        public AckMessage(UUID session) { this.session=session; }
        public void fromBytes(ByteBuf in) {
            if(in.readableBytes()!=16) throw new IllegalArgumentException("Invalid RGB acknowledgement");
            session=new UUID(in.readLong(),in.readLong());
        }
        public void toBytes(ByteBuf out) { out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits()); }
    }
    public static final class AckHandler implements IMessageHandler<AckMessage,IMessage> {
        public IMessage onMessage(AckMessage message,MessageContext context) {
            var player=context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(()->ServerRgbSync.acknowledge(player,message.session));
            return null;
        }
    }
}
