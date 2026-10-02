package com.sumirelabs.pcla.network;

/** One bounded pending/active profile; reset on connection replacement, retire on world change. */
public final class SettingsSession {
    private ServerSettings candidate;
    private long retiredEpoch;
    public boolean accept(ServerSettings settings,int dimension) {
        if(settings.dimension()!=dimension || settings.epoch()<=retiredEpoch)return false;
        if(candidate!=null && settings.epoch()<=candidate.epoch())return settings.epoch()==candidate.epoch()
                && settings.session().equals(candidate.session()) && settings.protocol()==candidate.protocol() && settings.config().settingsProfile().equals(candidate.config().settingsProfile());
        candidate=settings;return true;
    }
    public ServerSettings forHello(RegionFrame hello){return candidate!=null && candidate.matches(hello)?candidate:null;}
    public void retire(long epoch){retiredEpoch=Math.max(retiredEpoch,epoch);candidate=null;}
    public void clear(){candidate=null;retiredEpoch=0;}
}
