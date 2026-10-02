package com.sumirelabs.pcla.light.world;

import java.util.Objects;
import java.util.function.Consumer;

/** One RGB owner thread with a coalesced wake flag; no polling and no unbounded task queue. */
public final class RgbContinuationWorker implements AutoCloseable {
    public record Stats(boolean alive,boolean pending,boolean stopped,long turns) {}
    private final Object monitor=new Object();
    private final Runnable work;
    private final Consumer<RuntimeException> failure;
    private final Thread thread;
    private boolean pending,stopped;
    private long turns;

    public RgbContinuationWorker(String name,Runnable work,Consumer<RuntimeException> failure) {
        this.work=Objects.requireNonNull(work);this.failure=Objects.requireNonNull(failure);
        thread=new Thread(this::loop,Objects.requireNonNull(name));thread.setDaemon(true);thread.start();
    }
    public void signal() {synchronized(monitor){if(!stopped){pending=true;monitor.notifyAll();}}}
    public void requestStop() {synchronized(monitor){stopped=true;pending=false;monitor.notifyAll();}}
    public Stats stats(){synchronized(monitor){return new Stats(thread.isAlive(),pending,stopped,turns);}}
    private void loop() {
        for(;;) {
            synchronized(monitor) {
                while(!pending&&!stopped)try{monitor.wait();}catch(InterruptedException interrupted){stopped=true;}
                if(stopped)return;
                pending=false;
            }
            try{work.run();}catch(RuntimeException error){failure.accept(error);}
            synchronized(monitor){turns++;}
        }
    }
    public boolean awaitStopped(long milliseconds) {
        if(Thread.currentThread()==thread)return false;
        try{thread.join(milliseconds);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        return !thread.isAlive();
    }
    @Override public void close(){requestStop();awaitStopped(1000);}
}
