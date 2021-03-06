package org.jgroups.jmx.protocols;

import org.jgroups.jmx.Protocol;

import java.util.Vector;

/**
 * @author Bela Ban
 * @version $Id: Discovery.java,v 1.4 2005/08/26 14:19:09 belaban Exp $
 */
public class Discovery extends Protocol implements DiscoveryMBean {
    org.jgroups.protocols.Discovery p;

    public Discovery() {
    }

    public Discovery(org.jgroups.stack.Protocol p) {
        super(p);
        this.p=(org.jgroups.protocols.Discovery)p;
    }

    public void attachProtocol(org.jgroups.stack.Protocol p) {
        super.attachProtocol(p);
        this.p=(org.jgroups.protocols.Discovery)p;
    }

    public long getTimeout() {
        return p.getTimeout();
    }

    public void setTimeout(long timeout) {
        p.setTimeout(timeout);
    }

    public int getInitialMembers() {
        return p.getNumInitialMembers();
    }

    public void setInitialMembers(int num_initial_members) {
        p.setNumInitialMembers(num_initial_members);
    }

    public int getPingRequests() {
        return p.getNumPingRequests();
    }

    public void setPingRequests(int num_ping_requests) {
        p.setNumPingRequests(num_ping_requests);
    }

    public int getDiscoveryRequestsSent() {
        return p.getNumberOfDiscoveryRequestsSent();
    }

    public Vector findInitialMembers() {
        return p.findInitialMembers();
    }

    public String findInitialMembersAsString() {
        return p.findInitialMembersAsString();
    }
}
