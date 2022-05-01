package org.jgroups.protocols;

import org.jgroups.stack.Protocol;
import org.jgroups.Event;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;
import java.util.LinkedList;
import java.util.Vector;

/**
 * Class that waits for n PingRsp'es, or m milliseconds to return the initial membership
 * @author Bela Ban
 * @version $Id: PingWaiter.java,v 1.11.10.2 2007/04/27 08:03:52 belaban Exp $
 */
public class PingWaiter implements Runnable {
    Thread              thread=null;
    final List          rsps=new LinkedList();
    long                timeout=3000;
    int                 num_rsps=3;
    Protocol            parent=null;
    PingSender          ping_sender;
    protected final Log log=LogFactory.getLog(this.getClass());
    private boolean     trace=log.isTraceEnabled();


    public PingWaiter(long timeout, int num_rsps, Protocol parent, PingSender ping_sender) {
        this.timeout=timeout;
        this.num_rsps=num_rsps;
        this.parent=parent;
        this.ping_sender=ping_sender;
    }


    void setTimeout(long timeout) {
        this.timeout=timeout;
    }

    void setNumRsps(int num) {
        this.num_rsps=num;
    }



    public synchronized void start() {
        // ping_sender.start();
        if(thread == null || !thread.isAlive()) {
            thread=new Thread(this, "PingWaiter");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public synchronized void stop() {
        if(ping_sender != null)
            ping_sender.stop();
        if(thread != null) {
            // Thread tmp=t;
            thread=null;
            // tmp.interrupt();
            synchronized(rsps) {
                rsps.notifyAll();
            }
        }
    }


    public synchronized boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    public void addResponse(PingRsp rsp) {
        if(rsp != null) {
            synchronized(rsps) {
                if(rsps.contains(rsp))
                    rsps.remove(rsp); // overwrite existing element
                rsps.add(rsp);
                rsps.notifyAll();
            }
        }
    }

    public void clearResponses() {
        synchronized(rsps) {
            rsps.clear();
            rsps.notifyAll();
        }
    }


    public List getResponses() {
        return rsps;
    }



    public void run() {
        Vector responses=findInitialMembers();
        synchronized(this) {
            thread=null;
        }
        if(parent != null)
            parent.passUp(new Event(Event.FIND_INITIAL_MBRS_OK, responses));
    }


    public Vector findInitialMembers() {
        long start_time, time_to_wait;

        synchronized(rsps) {
            if(rsps.size() > 0) {
                rsps.clear();
            }

            ping_sender.start();

            start_time=System.currentTimeMillis();
            time_to_wait=timeout;

            try {
                while(rsps.size() < num_rsps && time_to_wait > 0 && thread != null && Thread.currentThread().equals(thread)) {
                    if(log.isTraceEnabled()) // +++ remove
                        log.trace(new StringBuffer("waiting for initial members: time_to_wait=").append(time_to_wait)
                                  .append(", got ").append(rsps.size()).append(" rsps"));

                    try {
                        rsps.wait(time_to_wait);
                    }
                    catch(InterruptedException intex) {
                    }
                    catch(Exception e) {
                        log.error("got an exception waiting for responses", e);
                    }
                    time_to_wait=timeout - (System.currentTimeMillis() - start_time);
                }
                if(log.isTraceEnabled())
                    log.trace(new StringBuffer("initial mbrs are ").append(rsps));
                return new Vector(rsps);
            }
            finally {
                if(ping_sender != null)
                    ping_sender.stop();
            }
        }
    }

}
