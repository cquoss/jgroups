package org.jgroups.protocols;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jgroups.util.Util;

/**
 * Sends num_ping_request GET_MBRS_REQ messages, distributed over timeout ms
 * @author Bela Ban
 * @version $Id: PingSender.java,v 1.5.10.1 2007/04/27 08:03:51 belaban Exp $
 */
public class PingSender implements Runnable {
    Thread              t=null;
    long                timeout=3000;
    double              interval;
    int                 num_requests=1;
    Discovery           discovery_prot;
    protected final Log log=LogFactory.getLog(this.getClass());
    protected boolean   trace=log.isTraceEnabled();


    public PingSender(long timeout, int num_requests, Discovery d) {
        this.timeout=timeout;
        this.num_requests=num_requests;
        this.discovery_prot=d;
        interval=timeout / (double)num_requests;
    }


    public synchronized void start() {
        if(t == null || !t.isAlive()) {
            t=new Thread(this, "PingSender");
            t.setDaemon(true);
            t.start();
        }
    }

    public synchronized void stop() {
        if(t != null) {
            Thread tmp=t;
            t=null;
            try {tmp.interrupt();} catch(SecurityException ex) {}
        }
    }


    public synchronized boolean isRunning() {
        return t != null && t.isAlive();
    }



    public void run() {
        for(int i=0; i < num_requests; i++) {
            if(t == null || !t.equals(Thread.currentThread()))
                break;
            if(log.isTraceEnabled())
                log.trace("sending GET_MBRS_REQ");
            discovery_prot.sendGetMembersRequest();
            Util.sleep((long)interval);
        }
    }
}
