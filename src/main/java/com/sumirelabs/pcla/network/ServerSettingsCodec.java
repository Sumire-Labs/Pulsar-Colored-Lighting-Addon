package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.config.*;
import com.sumirelabs.pcla.light.Rgb;
import io.netty.buffer.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded typed settings, avoiding recursive JSON parsing on the network. */
public final class ServerSettingsCodec {
    public static final int MAX_BYTES=131072,MAX_RULES=1024,MAX_PROPERTIES=16,MAX_STRING=256;
    public static void validate(LightingConfig config) {
        var bytes=Unpooled.buffer();try{encode(new ServerSettings(new UUID(0,0),1,0,4,config.simulationProfile(),config),bytes);}finally{bytes.release();}
    }
    public static void encode(ServerSettings value,ByteBuf out) {
        int start=out.writerIndex();out.writeByte(1);out.writeByte(value.protocol());out.writeLong(value.session().getMostSignificantBits());out.writeLong(value.session().getLeastSignificantBits());
        out.writeLong(value.epoch());out.writeInt(value.dimension());out.writeBytes(value.profile().getBytes(StandardCharsets.US_ASCII));
        var config=value.config();out.writeBoolean(config.enabled());int features=0;for(var f:config.configuredFeatures())features|=1<<f.ordinal();out.writeInt(features);
        int total=0;for(var kind:RuleKind.values()) {
            var rules=config.rules(kind);total+=rules.size();if(total>MAX_RULES)throw new IllegalArgumentException("Too many synchronized RGB rules");out.writeShort(rules.size());
            for(var rule:rules) {
                string(out,rule.target().id());out.writeByte(rule.mode().ordinal());out.writeShort(rule.rgb()==null?-1:rule.rgb().packed());
                var properties=rule.target().properties();if(properties.size()>MAX_PROPERTIES)throw new IllegalArgumentException("Too many state properties");out.writeByte(properties.size());
                for(var p:properties.entrySet()){string(out,p.getKey());string(out,p.getValue());}
                if(out.writerIndex()-start>MAX_BYTES)throw new IllegalArgumentException("RGB settings exceed packet limit");
            }
        }
    }
    public static ServerSettings decode(ByteBuf in) {
        if(in.readableBytes()<107 || in.readableBytes()>MAX_BYTES || in.readUnsignedByte()!=1)throw new IllegalArgumentException("Invalid settings packet");
        int protocol=in.readUnsignedByte();
        var session=new UUID(in.readLong(),in.readLong());long epoch=in.readLong();int dimension=in.readInt();
        String profile=in.readCharSequence(64,StandardCharsets.US_ASCII).toString();int enabled=in.readUnsignedByte(),mask=in.readInt();
        if(enabled>1 || (mask&~((1<<Feature.values().length)-1))!=0)throw new IllegalArgumentException("Invalid settings flags");
        var features=EnumSet.noneOf(Feature.class);for(var feature:Feature.values())if((mask&(1<<feature.ordinal()))!=0)features.add(feature);
        var rules=new EnumMap<RuleKind,List<TargetRule>>(RuleKind.class);int total=0;
        for(var kind:RuleKind.values()) {
            require(in,2);int count=in.readUnsignedShort();total+=count;if(total>MAX_RULES)throw new IllegalArgumentException("Too many settings rules");
            var list=new ArrayList<TargetRule>(count);
            for(int i=0;i<count;i++) {
                String id=string(in);require(in,4);int mode=in.readUnsignedByte(),color=in.readShort(),properties=in.readUnsignedByte();
                if(mode>=RuleMode.values().length || properties>MAX_PROPERTIES || color < -1 || color>4095)throw new IllegalArgumentException("Invalid settings rule");
                var state=new TreeMap<String,String>();for(int j=0;j<properties;j++)if(state.put(string(in),string(in))!=null)throw new IllegalArgumentException("Duplicate state property");
                list.add(new TargetRule(new Selector(id,state),RuleMode.values()[mode],color==-1?null:Rgb.unpack(color)));
            }
            rules.put(kind,list);
        }
        if(in.isReadable())throw new IllegalArgumentException("Trailing settings bytes");
        return new ServerSettings(session,epoch,dimension,protocol,profile,new LightingConfig(enabled==1,features,rules));
    }
    private static void string(ByteBuf out,String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length==0 || bytes.length>MAX_STRING)throw new IllegalArgumentException("Invalid settings string length");out.writeShort(bytes.length);out.writeBytes(bytes);}
    private static String string(ByteBuf in){require(in,2);int length=in.readUnsignedShort();if(length==0 || length>MAX_STRING)throw new IllegalArgumentException("Invalid settings string length");require(in,length);return in.readCharSequence(length,StandardCharsets.UTF_8).toString();}
    private static void require(ByteBuf in,int bytes){if(in.readableBytes()<bytes)throw new IllegalArgumentException("Truncated settings packet");}
    private ServerSettingsCodec() {}
}
