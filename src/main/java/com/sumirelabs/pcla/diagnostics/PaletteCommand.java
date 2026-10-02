package com.sumirelabs.pcla.diagnostics;

import com.google.gson.GsonBuilder;
import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pcla.config.PaletteAudit;
import com.sumirelabs.pcla.light.MinecraftColorTable;
import net.minecraft.command.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import java.nio.file.*;
import java.util.*;

/** Available even with RGB disabled, so configuration targets can still be checked. */
public final class PaletteCommand extends CommandBase {
    public String getName(){return "pcla_palette";}
    public List<String> getAliases(){return List.of("pcp");}
    public String getUsage(ICommandSender sender){return "/pcp";}
    public int getRequiredPermissionLevel(){return 2;}
    public void execute(MinecraftServer server,ICommandSender sender,String[] args)throws CommandException {
        if(args.length!=0)throw new WrongUsageException(getUsage(sender));
        var entities=new TreeSet<String>();ForgeRegistries.ENTITIES.getKeys().forEach(id->entities.add(id.toString()));entities.add("minecraft:player");
        var report=PaletteAudit.inspect(Pcla.startupConfig(),MinecraftColorTable.definitions(),entities);
        var path=Path.of("logs","pcla-palette-"+System.currentTimeMillis()+".json");
        var gson=new GsonBuilder().setPrettyPrinting().create();var json=gson.toJsonTree(report).getAsJsonObject();json.add("livePreview",ColorPreview.paletteSamples());
        try{Files.createDirectories(path.getParent());Files.writeString(path,gson.toJson(json));}
        catch(java.io.IOException e){throw new CommandException("Cannot save palette report: "+e);}
        long present=report.sources().stream().filter(s->s.registeredStates()>0).count();
        long unmatched=report.rules().stream().filter(r->r.matches()==0).count();
        sender.sendMessage(new TextComponentString("PCLA palette: "+present+"/"+report.sources().size()+" source IDs registered; "+unmatched+" unmatched rules. "+path));
    }
}
