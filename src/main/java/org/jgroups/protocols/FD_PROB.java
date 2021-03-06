// $Id: FD_PROB.java,v 1.10.6.1 2007/04/27 08:03:52 belaban Exp $

package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Util;
import org.jgroups.util.Streamable;

import java.io.*;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Properties;
import java.util.Vector;


/**
 * Probabilistic failure detection protocol based on "A Gossip-Style Failure Detection Service"
 * by Renesse, Minsky and Hayden.<p>
 * Each member maintains a list of all other members: for each member P, 2 data are maintained, a heartbeat
 * counter and the time of the last increment of the counter. Each member periodically sends its own heartbeat
 * counter list to a randomly chosen member Q. Q updates its own heartbeat counter list and the associated
 * time (if counter was incremented). Each member periodically increments its own counter. If, when sending
 * its heartbeat counter list, a member P detects that another member Q's heartbeat counter was not incremented
 * for timeout seconds, Q will be suspected.<p>
 * This protocol can be used both with a PBCAST *and* regular stacks.
 * @author Bela Ban 1999
 * @version $Revision: 1.10.6.1 $
 */
public class FD_PROB extends Protocol implements Runnable {
    Address local_addr=null;
    Thread hb=null;
    long timeout=3000;  // before a member with a non updated timestamp is suspected
    long gossip_interval=1000;
    Vector members=null;
    final Hashtable counters=new Hashtable();        // keys=Addresses, vals=FdEntries
    final Hashtable invalid_pingers=new Hashtable(); // keys=Address, vals=Integer (number of pings from suspected mbrs)
    int max_tries=2;   // number of times to send a are-you-alive msg (tot time= max_tries*timeout)


    public String getName() {
        return "FD_PROB";
    }


    public boolean setProperties(Properties props) {
        String str;

        super.setProperties(props);
        str=props.getProperty("timeout");
        if(str != null) {
            timeout=Long.parseLong(str);
            props.remove("timeout");
        }

        str=props.getProperty("gossip_interval");
        if(str != null) {
            gossip_interval=Long.parseLong(str);
            props.remove("gossip_interval");
        }

        str=props.getProperty("max_tries");
        if(str != null) {
            max_tries=Integer.parseInt(str);
            props.remove("max_tries");
        }

        if(props.size() > 0) {
            log.error("FD_PROB.setProperties(): the following properties are not recognized: " + props);

            return false;
        }
        return true;
    }


    public void start() throws Exception {
        if(hb == null) {
            hb=new Thread(this, "FD_PROB.HeartbeatThread");
            hb.setDaemon(true);
            hb.start();
        }
    }


    public void stop() {
        Thread tmp=null;
        if(hb != null && hb.isAlive()) {
            tmp=hb;
            hb=null;
            tmp.interrupt();
            try {
                tmp.join(timeout);
            }
            catch(Exception ex) {
            }
        }
        hb=null;
    }


    public void up(Event evt) {
        Message msg;
        FdHeader hdr=null;
        Object obj;

        switch(evt.getType()) {

            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address) evt.getArg();
                break;

            case Event.MSG:
                msg=(Message) evt.getArg();
                obj=msg.getHeader(getName());
                if(obj == null || !(obj instanceof FdHeader)) {
                    updateCounter(msg.getSrc());  // got a msg from this guy, reset its time (we heard from it now)
                    break;
                }

                hdr=(FdHeader) msg.removeHeader(getName());
                switch(hdr.type) {
                    case FdHeader.HEARTBEAT:                           // heartbeat request; send heartbeat ack
                        if(checkPingerValidity(msg.getSrc()) == false) // false == sender of heartbeat is not a member
                            return;

                        // 2. Update my own array of counters

                            if(log.isInfoEnabled()) log.info("<-- HEARTBEAT from " + msg.getSrc());
                        updateCounters(hdr);
                        return;                                     // don't pass up !
                    case FdHeader.NOT_MEMBER:
                        if(log.isWarnEnabled()) log.warn("NOT_MEMBER: I'm being shunned; exiting");
                        passUp(new Event(Event.EXIT));
                        return;
                    default:
                        if(log.isWarnEnabled()) log.warn("FdHeader type " + hdr.type + " not known");
                        return;
                }
        }
        passUp(evt);                                        // pass up to the layer above us
    }


    public void down(Event evt) {
        int num_mbrs;
        Vector excluded_mbrs;
        FdEntry entry;
        Address mbr;

        switch(evt.getType()) {

            // Start heartbeat thread when we have more than 1 member; stop it when membership drops below 2
            case Event.VIEW_CHANGE:
                passDown(evt);
                synchronized(this) {
                    View v=(View) evt.getArg();

                    // mark excluded members
                    excluded_mbrs=computeExcludedMembers(members, v.getMembers());
                    if(excluded_mbrs != null && excluded_mbrs.size() > 0) {
                        for(int i=0; i < excluded_mbrs.size(); i++) {
                            mbr=(Address) excluded_mbrs.elementAt(i);
                            entry=(FdEntry) counters.get(mbr);
                            if(entry != null)
                                entry.setExcluded(true);
                        }
                    }

                    members=v != null ? v.getMembers() : null;
                    if(members != null) {
                        num_mbrs=members.size();
                        if(num_mbrs >= 2) {
                            if(hb == null) {
                                try {
                                    start();
                                }
                                catch(Exception ex) {
                                    if(log.isWarnEnabled()) log.warn("exception when calling start(): " + ex);
                                }
                            }
                        }
                        else
                            stop();
                    }
                }
                break;

            default:
                passDown(evt);
                break;
        }
    }


    /**
     Loop while more than 1 member available. Choose a member randomly (not myself !) and send a
     heartbeat. Wait for ack. If ack not received withing timeout, mcast SUSPECT message.
     */
    public void run() {
        Message hb_msg;
        FdHeader hdr;
        Address hb_dest, key;
        FdEntry entry;
        long curr_time, diff;



            if(log.isInfoEnabled()) log.info("heartbeat thread was started");

        while(hb != null && members.size() > 1) {

            // 1. Get a random member P (excluding ourself)
            hb_dest=getHeartbeatDest();
            if(hb_dest == null) {
                if(log.isWarnEnabled()) log.warn("hb_dest is null");
                Util.sleep(gossip_interval);
                continue;
            }


            // 2. Increment own counter
            entry=(FdEntry) counters.get(local_addr);
            if(entry == null) {
                entry=new FdEntry();
                counters.put(local_addr, entry);
            }
            entry.incrementCounter();


            // 3. Send heartbeat to P
            hdr=createHeader();
            if(hdr == null)
                if(log.isWarnEnabled()) log.warn("header could not be created. Heartbeat will not be sent");
            else {
                hb_msg=new Message(hb_dest, null, null);
                hb_msg.putHeader(getName(), hdr);

                    if(log.isInfoEnabled()) log.info("--> HEARTBEAT to " + hb_dest);
                passDown(new Event(Event.MSG, hb_msg));
            }


                if(log.isInfoEnabled()) log.info("own counters are " + printCounters());


            // 4. Suspect members from which we haven't heard for timeout msecs
            for(Enumeration e=counters.keys(); e.hasMoreElements();) {
                curr_time=System.currentTimeMillis();
                key=(Address) e.nextElement();
                entry=(FdEntry) counters.get(key);

                if(entry.getTimestamp() > 0 && (diff=curr_time - entry.getTimestamp()) >= timeout) {
                    if(entry.excluded()) {
                        if(diff >= 2 * timeout) {  // remove members marked as 'excluded' after 2*timeout msecs
                            counters.remove(key);
                            if(log.isInfoEnabled()) log.info("removed " + key);
                        }
                    }
                    else {
                        if(log.isInfoEnabled()) log.info("suspecting " + key);
                        passUp(new Event(Event.SUSPECT, key));
                    }
                }
            }
            Util.sleep(gossip_interval);
        } // end while


            if(log.isInfoEnabled()) log.info("heartbeat thread was stopped");
    }







    /* -------------------------------- Private Methods ------------------------------- */

    Address getHeartbeatDest() {
        Address retval=null;
        int r, size;
        Vector members_copy;

        if(members == null || members.size() < 2 || local_addr == null)
            return null;
        members_copy=(Vector) members.clone();
        members_copy.removeElement(local_addr); // don't select myself as heartbeat destination
        size=members_copy.size();
        r=((int) (Math.random() * (size + 1))) % size;
        retval=(Address) members_copy.elementAt(r);
        return retval;
    }


    /** Create a header containing the counters for all members */
    FdHeader createHeader() {
        int num_mbrs=counters.size(), index=0;
        FdHeader ret=null;
        Address key;
        FdEntry entry;

        if(num_mbrs <= 0)
            return null;
        ret=new FdHeader(FdHeader.HEARTBEAT, num_mbrs);
        for(Enumeration e=counters.keys(); e.hasMoreElements();) {
            key=(Address) e.nextElement();
            entry=(FdEntry) counters.get(key);
            if(entry.excluded())
                continue;
            if(index >= ret.members.length) {
                if(log.isWarnEnabled()) log.warn("index " + index + " is out of bounds (" +
                                                     ret.members.length + ')');
                break;
            }
            ret.members[index]=key;
            ret.counters[index]=entry.getCounter();
            index++;
        }
        return ret;
    }


    /** Set my own counters values to max(own-counter, counter) */
    void updateCounters(FdHeader hdr) {
        Address key;
        FdEntry entry;

        if(hdr == null || hdr.members == null || hdr.counters == null) {
            if(log.isWarnEnabled()) log.warn("hdr is null or contains no counters");
            return;
        }

        for(int i=0; i < hdr.members.length; i++) {
            key=hdr.members[i];
            if(key == null) continue;
            entry=(FdEntry) counters.get(key);
            if(entry == null) {
                entry=new FdEntry(hdr.counters[i]);
                counters.put(key, entry);
                continue;
            }

            if(entry.excluded())
                continue;

            // only update counter (and adjust timestamp) if new counter is greater then old one
            entry.setCounter(Math.max(entry.getCounter(), hdr.counters[i]));
        }
    }


    /** Resets the counter for mbr */
    void updateCounter(Address mbr) {
        FdEntry entry;

        if(mbr == null) return;
        entry=(FdEntry) counters.get(mbr);
        if(entry != null)
            entry.setTimestamp();
    }


    String printCounters() {
        StringBuffer sb=new StringBuffer();
        Address mbr;
        FdEntry entry;

        for(Enumeration e=counters.keys(); e.hasMoreElements();) {
            mbr=(Address) e.nextElement();
            entry=(FdEntry) counters.get(mbr);
            sb.append("\n" + mbr + ": " + entry._toString());
        }
        return sb.toString();
    }


    static Vector computeExcludedMembers(Vector old_mbrship, Vector new_mbrship) {
        Vector ret=new Vector();
        if(old_mbrship == null || new_mbrship == null) return ret;
        for(int i=0; i < old_mbrship.size(); i++)
            if(!new_mbrship.contains(old_mbrship.elementAt(i)))
                ret.addElement(old_mbrship.elementAt(i));
        return ret;
    }


    /** If hb_sender is not a member, send a SUSPECT to sender (after n pings received) */
    boolean checkPingerValidity(Object hb_sender) {
        int num_pings=0;
        Message shun_msg;
        Header hdr;

        if(hb_sender != null && members != null && !members.contains(hb_sender)) {
            if(invalid_pingers.containsKey(hb_sender)) {
                num_pings=((Integer) invalid_pingers.get(hb_sender)).intValue();
                if(num_pings >= max_tries) {
                    if(log.isErrorEnabled()) log.error("sender " + hb_sender +
                                                                  " is not member in " + members + " ! Telling it to leave group");
                    shun_msg=new Message((Address) hb_sender, null, null);
                    hdr=new FdHeader(FdHeader.NOT_MEMBER);
                    shun_msg.putHeader(getName(), hdr);
                    passDown(new Event(Event.MSG, shun_msg));
                    invalid_pingers.remove(hb_sender);
                }
                else {
                    num_pings++;
                    invalid_pingers.put(hb_sender, new Integer(num_pings));
                }
            }
            else {
                num_pings++;
                invalid_pingers.put(hb_sender, new Integer(num_pings));
            }
            return false;
        }
        else
            return true;
    }


    /* ----------------------------- End of Private Methods --------------------------- */






    public static class FdHeader extends Header implements Streamable {
        static final byte HEARTBEAT=1;  // sent periodically to a random member
        static final byte NOT_MEMBER=2;  // sent to the sender, when it is not a member anymore (shunned)


        byte type=HEARTBEAT;
        Address[] members=null;
        long[] counters=null;  // correlates with 'members' (same indexes)


        public FdHeader() {
        } // used for externalization

        FdHeader(byte type) {
            this.type=type;
        }

        FdHeader(byte type, int num_elements) {
            this(type);
            members=new Address[num_elements];
            counters=new long[num_elements];
        }


        public String toString() {
            switch(type) {
                case HEARTBEAT:
                    return "[FD_PROB: HEARTBEAT]";
                case NOT_MEMBER:
                    return "[FD_PROB: NOT_MEMBER]";
                default:
                    return "[FD_PROB: unknown type (" + type + ")]";
            }
        }

        public String printDetails() {
            StringBuffer sb=new StringBuffer();
            Address mbr;

            if(members != null && counters != null)
                for(int i=0; i < members.length; i++) {
                    mbr=members[i];
                    if(mbr == null)
                        sb.append("\n<null>");
                    else
                        sb.append("\n" + mbr);
                    sb.append(": " + counters[i]);
                }
            return sb.toString();
        }


        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeByte(type);

            if(members != null) {
                out.writeInt(members.length);
                out.writeObject(members);
            }
            else
                out.writeInt(0);

            if(counters != null) {
                out.writeInt(counters.length);
                for(int i=0; i < counters.length; i++)
                    out.writeLong(counters[i]);
            }
            else
                out.writeInt(0);
        }


        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            int num;
            type=in.readByte();

            num=in.readInt();
            if(num == 0)
                members=null;
            else {
                members=(Address[]) in.readObject();
            }

            num=in.readInt();
            if(num == 0)
                counters=null;
            else {
                counters=new long[num];
                for(int i=0; i < counters.length; i++)
                    counters[i]=in.readLong();
            }
        }

        public long size() {
            long retval=Global.BYTE_SIZE;
            retval+=Global.SHORT_SIZE; // number of members
            if(members != null && members.length > 0) {
                for(int i=0; i < members.length; i++) {
                    Address member=members[i];
                    retval+=Util.size(member);
                }
            }

            retval+=Global.SHORT_SIZE; // counters
            if(counters != null && counters.length > 0) {
                retval+=counters.length * Global.LONG_SIZE;
            }

            return retval;
        }

        public void writeTo(DataOutputStream out) throws IOException {
            out.writeByte(type);
            if(members == null || members.length == 0)
                out.writeShort(0);
            else {
                out.writeShort(members.length);
                for(int i=0; i < members.length; i++) {
                    Address member=members[i];
                    Util.writeAddress(member, out);
                }
            }

            if(counters == null || counters.length == 0) {
                out.writeShort(0);
            }
            else {
                out.writeShort(counters.length);
                for(int i=0; i < counters.length; i++) {
                    long counter=counters[i];
                    out.writeLong(counter);
                }
            }
        }

        public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException {
            type=in.readByte();
            short len=in.readShort();
            if(len > 0) {
                members=new Address[len];
                for(int i=0; i < len; i++) {
                    members[i]=Util.readAddress(in);
                }
            }

            len=in.readShort();
            if(len > 0) {
                counters=new long[len];
                for(int i=0; i < counters.length; i++) {
                    counters[i]=in.readLong();
                }
            }
        }


    }


    private static class FdEntry {
        private long counter=0;       // heartbeat counter
        private long timestamp=0;     // last time the counter was incremented
        private boolean excluded=false;  // set to true if member was excluded from group


        FdEntry() {

        }

        FdEntry(long counter) {
            this.counter=counter;
            timestamp=System.currentTimeMillis();
        }


        long getCounter() {
            return counter;
        }

        long getTimestamp() {
            return timestamp;
        }

        boolean excluded() {
            return excluded;
        }


        synchronized void setCounter(long new_counter) {
            if(new_counter > counter) { // only set time if counter was incremented
                timestamp=System.currentTimeMillis();
                counter=new_counter;
            }
        }

        synchronized void incrementCounter() {
            counter++;
            timestamp=System.currentTimeMillis();
        }

        synchronized void setTimestamp() {
            timestamp=System.currentTimeMillis();
        }

        synchronized void setExcluded(boolean flag) {
            excluded=flag;
        }


        public String toString() {
            return "counter=" + counter + ", timestamp=" + timestamp + ", excluded=" + excluded;
        }

        public String _toString() {
            return "counter=" + counter + ", age=" + (System.currentTimeMillis() - timestamp) +
                    ", excluded=" + excluded;
        }
    }


}
