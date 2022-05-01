package org.jgroups.protocols;

import EDU.oswego.cs.dl.util.concurrent.*;
import org.jgroups.*;
import org.jgroups.stack.Protocol;
import org.jgroups.util.BoundedList;
import org.jgroups.util.Streamable;
import org.jgroups.util.Util;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

/**
 * Simple flow control protocol based on a credit system. Each sender has a number of credits (bytes
 * to send). When the credits have been exhausted, the sender blocks. Each receiver also keeps track of
 * how many credits it has received from a sender. When credits for a sender fall below a threshold,
 * the receiver sends more credits to the sender. Works for both unicast and multicast messages.
 * <p/>
 * Note that this protocol must be located towards the top of the stack, or all down_threads from JChannel to this
 * protocol must be set to false ! This is in order to block JChannel.send()/JChannel.down().
 * <br/>This is the second simplified implementation of the same model. The algorithm is sketched out in
 * doc/FlowControl.txt
 * <br/>
 * Changes (Brian) April 2006:
 * <ol>
 * <li>Receivers now send credits to a sender when more than min_credits have been received (rather than when min_credits
 * are left)
 * <li>Receivers don't send the full credits (max_credits), but rather tha actual number of bytes received
 * <ol/>
 * @author Bela Ban
 * @version $Id: FC.java,v 1.53.2.11 2007/04/27 08:03:51 belaban Exp $
 */
public class FC extends Protocol {

    /**
     * HashMap<Address,Long>: keys are members, values are credits left. For each send, the
     * number of credits is decremented by the message size
     */
    final Map sent=new HashMap(11);
    // final Map sent=new ConcurrentHashMap(11);

    /**
     * HashMap<Address,Long>: keys are members, values are credits left (in bytes).
     * For each receive, the credits for the sender are decremented by the size of the received message.
     * When the credits are 0, we refill and send a CREDIT message to the sender. Sender blocks until CREDIT
     * is received after reaching <tt>min_credits</tt> credits.
     */
    final Map received=new ConcurrentReaderHashMap(11);
    // final Map received=new ConcurrentHashMap(11);


    /**
     * List of members from whom we expect credits
     */
    final List creditors=new ArrayList(11);

    /** Peers who have asked for credit that we didn't have */
    final Set pending_requesters = new HashSet(11);
    
    /**
     * Max number of bytes to send per receiver until an ack must
     * be received before continuing sending
     */
    private long max_credits=500000;
    private Long max_credits_constant=new Long(max_credits);

    /**
     * Max time (in milliseconds) to block. If credit hasn't been received after max_block_time, we send
     * a REPLENISHMENT request to the members from which we expect credits. A value <= 0 means to
     * wait forever.
     */
    private long max_block_time=5000;

    /**
     * If credits fall below this limit, we send more credits to the sender. (We also send when
     * credits are exhausted (0 credits left))
     */
    private double min_threshold=0.25;

    /**
     * Computed as <tt>max_credits</tt> times <tt>min_theshold</tt>. If explicitly set, this will
     * override the above computation
     */
    private long min_credits=0;

    /**
     * Whether FC is still running, this is set to false when the protocol terminates (on stop())
     */
    private boolean running=true;

    /**
     * Determines whether or not to block on down(). Set when not enough credit is available to send a message
     * to all or a single member
     */
    private boolean insufficient_credit=false;

    /**
     * the lowest credits of any destination (sent_msgs)
     */
    private long lowest_credit=max_credits;

    /**
     * Lock to be used with the CondVar below.
     */
    final Sync lock=new ReentrantLock();

    /**
     * Mutex to block on down()
     */
    final CondVar mutex=new CondVar(lock);

    /**
     * Whether an up thread that comes back down should be allowed to
     * bypass blocking if all credits are exhausted. Avoids JGRP-465.
     */
    private boolean ignore_synchronous_response=true;

    /**
     * Thread that carries messages through up() and shouldn't be blocked
     * in down() if ignore_synchronous_response==true. JGRP-465.
     */
    private Thread ignore_thread;

    static final String name="FC";

    private long start_blocking=0;


    /**
     * Map<Address, Long> of the last time we requested credit
     */
    private final Map last_credit_request=new ConcurrentHashMap();

    private int num_blockings=0;
    private int num_credit_requests_received=0, num_credit_requests_sent=0;
    private int num_credit_responses_sent=0, num_credit_responses_received=0;
    private long total_time_blocking=0;

    final BoundedList last_blockings=new BoundedList(50);

    final static FcHeader REPLENISH_HDR=new FcHeader(FcHeader.REPLENISH);
    final static FcHeader CREDIT_REQUEST_HDR=new FcHeader(FcHeader.CREDIT_REQUEST);


    public final String getName() {
        return name;
    }

    public void resetStats() {
        super.resetStats();
        num_blockings=0;
        num_credit_responses_sent=num_credit_responses_received=num_credit_requests_received=num_credit_requests_sent=0;
        total_time_blocking=0;
        last_blockings.removeAll();
    }

    public long getMaxCredits() {
        return max_credits;
    }

    public void setMaxCredits(long max_credits) {
        this.max_credits=max_credits;
        max_credits_constant=new Long(this.max_credits);
    }

    public double getMinThreshold() {
        return min_threshold;
    }

    public void setMinThreshold(double min_threshold) {
        this.min_threshold=min_threshold;
    }

    public long getMinCredits() {
        return min_credits;
    }

    public void setMinCredits(long min_credits) {
        this.min_credits=min_credits;
    }

    public boolean isBlocked() {
        return insufficient_credit;
    }

    public int getNumberOfBlockings() {
        return num_blockings;
    }

    public long getMaxBlockTime() {
        return max_block_time;
    }

    public void setMaxBlockTime(long t) {
        max_block_time=t;
    }

    public long getTotalTimeBlocked() {
        return total_time_blocking;
    }

    public double getAverageTimeBlocked() {
        return num_blockings == 0? 0.0 : total_time_blocking / (double)num_blockings;
    }

    public int getNumberOfCreditRequestsReceived() {
        return num_credit_requests_received;
    }

    public int getNumberOfCreditRequestsSent() {
        return num_credit_requests_sent;
    }

    public int getNumberOfCreditResponsesReceived() {
        return num_credit_responses_received;
    }

    public int getNumberOfCreditResponsesSent() {
        return num_credit_responses_sent;
    }

    public String printSenderCredits() {
        return printMap(sent);
    }

    public String printReceiverCredits() {
        return printMap(received);
    }

    public String printCredits() {
        StringBuffer sb=new StringBuffer();
        sb.append("senders:\n").append(printMap(sent)).append("\n\nreceivers:\n").append(printMap(received));
        return sb.toString();
    }

    public Map dumpStats() {
        Map retval=super.dumpStats();
        if(retval == null)
            retval=new HashMap();
        retval.put("senders", printMap(sent));
        retval.put("receivers", printMap(received));
        retval.put("num_blockings", new Integer(this.num_blockings));
        retval.put("avg_time_blocked", new Double(getAverageTimeBlocked()));
        retval.put("num_replenishments", new Integer(this.num_credit_responses_received));
        retval.put("total_time_blocked", new Long(total_time_blocking));
        return retval;
    }

    public String showLastBlockingTimes() {
        return last_blockings.toString();
    }


    /**
     * Allows to unblock a blocked sender from an external program, e.g. JMX
     */
    public void unblock() {
        if(Util.acquire(lock)) {
            try {
                if(log.isTraceEnabled())
                    log.trace("unblocking the sender and replenishing all members, creditors are " + creditors);

                Map.Entry entry;
                for(Iterator it=sent.entrySet().iterator(); it.hasNext();) {
                    entry=(Map.Entry)it.next();
                    entry.setValue(max_credits_constant);
                }

                lowest_credit=computeLowestCredit(sent);
                creditors.clear();
                insufficient_credit=false;
                mutex.broadcast();
            }
            finally {
                Util.release(lock);
            }
        }
    }


    public boolean setProperties(Properties props) {
        String str;
        boolean min_credits_set=false;

        super.setProperties(props);
        str=props.getProperty("max_credits");
        if(str != null) {
            max_credits=Long.parseLong(str);
            props.remove("max_credits");
        }

        str=props.getProperty("min_threshold");
        if(str != null) {
            min_threshold=Double.parseDouble(str);
            props.remove("min_threshold");
        }

        str=props.getProperty("min_credits");
        if(str != null) {
            min_credits=Long.parseLong(str);
            props.remove("min_credits");
            min_credits_set=true;
        }

        if(!min_credits_set)
            min_credits=(long)((double)max_credits * min_threshold);

        str=props.getProperty("max_block_time");
        if(str != null) {
            max_block_time=Long.parseLong(str);
            props.remove("max_block_time");
        }

        str=props.getProperty("ignore_synchronous_response");
        if(str != null) {
            ignore_synchronous_response=Boolean.valueOf(str).booleanValue();
            props.remove("ignore_synchronous_response");
        }

        if(!props.isEmpty()) {
            log.error("the following properties are not recognized: " + props);
            return false;
        }
        max_credits_constant=new Long(max_credits);
        return true;
    }

    public void start() throws Exception {
        super.start();
        lock.acquire();
        try {
            running=true;
            insufficient_credit=false;
            lowest_credit=max_credits;
        }
        finally {
            lock.release();
        }
    }

    public void stop() {
        super.stop();
        if(Util.acquire(lock)) {
            try {
                running=false;
                ignore_thread=null;
                mutex.broadcast(); // notify all threads waiting on the mutex that we are done
            }
            finally {
                Util.release(lock);
            }
        }
    }


    /**
     * We need to receive view changes concurrent to messages on the down events: a message might blocks, e.g.
     * because we don't have enough credits to send to member P. However, if member P crashed, we need to unblock !
     * @param evt
     */
    protected void receiveDownEvent(Event evt) {
        if(evt.getType() == Event.VIEW_CHANGE) {
            View v=(View)evt.getArg();
            Vector mbrs=v.getMembers();
            handleViewChange(mbrs);
        }
        super.receiveDownEvent(evt);
    }

    public void down(Event evt) {
        switch(evt.getType()) {
            case Event.MSG:
                handleDownMessage(evt);
                return;
        }
        passDown(evt); // this could potentially use the lower protocol's thread which may block
    }


    public void up(Event evt) {
        switch(evt.getType()) {

            case Event.MSG:

                // JGRP-465. We only deal with msgs to avoid having to use
                // a concurrent collection; ignore views, suspicions, etc 
                // which can come up on unusual threads.
                if(ignore_thread == null && ignore_synchronous_response)
                    ignore_thread=Thread.currentThread();

                Message msg=(Message)evt.getArg();
                FcHeader hdr=(FcHeader)msg.removeHeader(name);
                if(hdr != null) {
                    switch(hdr.type) {
                        case FcHeader.REPLENISH:
                            num_credit_responses_received++;
                            handleCredit(msg.getSrc(), (Number)msg.getObject());
                            break;
                        case FcHeader.CREDIT_REQUEST:
                            num_credit_requests_received++;
                            Address sender=msg.getSrc();
                            Long sent_credits = (Long) msg.getObject();
                            handleCreditRequest(sender, sent_credits);
                            break;
                        default:
                            log.error("header type " + hdr.type + " not known");
                            break;
                    }
                    return; // don't pass message up
                }
                else {
                    adjustCredit(msg);
                }
                break;

            case Event.VIEW_CHANGE:
                handleViewChange(((View)evt.getArg()).getMembers());
                break;
        }

        passUp(evt);
    }


    private void handleDownMessage(Event evt) {
        Message msg=(Message)evt.getArg();
        int length=msg.getLength();
        Address dest=msg.getDest();

        if(Util.acquire(lock)) {
            try {
                if(lowest_credit <= length) {
                    if(ignore_synchronous_response && ignore_thread == Thread.currentThread()) { // JGRP-465
                        if(log.isTraceEnabled())
                            log.trace("Bypassing blocking to avoid deadlocking " + Thread.currentThread());
                    }
                    else {
                        determineCreditors(dest, length);
                        
                        long blockStart=System.currentTimeMillis(); 
                        if (!insufficient_credit) {
                           insufficient_credit = true;
                           start_blocking = blockStart;
                           if(log.isTraceEnabled()) {
                              log.trace("Starting blocking. lowest_credit=" + 
                                    lowest_credit + "; msg length =" + length);
                           }
                        }
                        num_blockings++;
                        
                        while(insufficient_credit && running) {
                            try {
                                mutex.timedwait(max_block_time);
                            }
                            catch(InterruptedException e) {
                            }
                            if(insufficient_credit && running) {
                                long waitTime = System.currentTimeMillis() - blockStart;
                                if(log.isTraceEnabled()) {
                                    log.trace("Still waiting for credits -- waiting " + waitTime + " ms");
                                }
                                
                                // Only ask for credit if we blocked over max_block_time,
                                // otherwise it's not an emergency
                                if (waitTime >= max_block_time) {
                                   
                                   // Creditors may have been cleared but credit
                                   // receipt was insufficient to let all
                                   // blocked threads proceed. So, redetermine
                                   determineCreditors(dest, length);
                                   
                                   Map sent_copy = new HashMap(sent);
                                   sent_copy.keySet().retainAll(creditors);
                                   // we need to send the credit requests down *without* holding the lock, otherwise we might
                                   // run into the deadlock described in http://jira.jboss.com/jira/browse/JGRP-292
                                   Util.release(lock);
                                   try {
                                       for(Iterator it = sent_copy.entrySet().iterator(); it.hasNext(); ) {
                                           Map.Entry e = (Entry) it.next();
                                           sendCreditRequest((Address)e.getKey(), (Long) e.getValue());
                                       }
                                   }
                                   finally {
                                       Util.acquire(lock);
                                   }
                                }
                            }
                        }
                        
                        long block_time=System.currentTimeMillis() - blockStart;
                        if(log.isTraceEnabled())
                            log.trace("total time blocked: " + block_time + " ms");
                        total_time_blocking+=block_time;
                        last_blockings.add(new Long(block_time));
                    }
                }

                long tmp=decrementCredit(sent, dest, length);
                if(tmp != -1)
                    lowest_credit=Math.min(tmp, lowest_credit);

            }
            finally {
                Util.release(lock);
            }
        }

        // send message - either after regular processing, or after blocking (when enough credits available again)
        passDown(evt);
    }

    /**
     * Checks whether one member (unicast msg) or all members (multicast msg) have enough credits. Add those
     * that don't to the creditors list
     * @param dest
     * @param length
     */
    private void determineCreditors(Address dest, int length) {
        boolean multicast=dest == null || dest.isMulticastAddress();
        Address mbr;
        Long credits;
        if(multicast) {
            Map.Entry entry;
            for(Iterator it=sent.entrySet().iterator(); it.hasNext();) {
                entry=(Map.Entry)it.next();
                mbr=(Address)entry.getKey();
                credits=(Long)entry.getValue();
                if(credits.longValue() <= length) {
                    if(!creditors.contains(mbr))
                        creditors.add(mbr);
                }
            }
        }
        else {
            credits=(Long)sent.get(dest);
            if(credits != null && credits.longValue() <= length) {
                if(!creditors.contains(dest))
                    creditors.add(dest);
            }
        }
    }


    /**
     * Decrements credits from a single member, or all members in sent_msgs, depending on whether it is a multicast
     * or unicast message. No need to acquire mutex (must already be held when this method is called)
     * @param dest
     * @param credits
     * @return The lowest number of credits left, or -1 if a unicast member was not found
     */
    private long decrementCredit(Map m, Address dest, long credits) {
        boolean multicast=dest == null || dest.isMulticastAddress();
        long lowest=max_credits, tmp;
        Long val;

        if(multicast) {
            if(m.isEmpty())
                return -1;
            Map.Entry entry;
            for(Iterator it=m.entrySet().iterator(); it.hasNext();) {
                entry=(Map.Entry)it.next();
                val=(Long)entry.getValue();
                tmp=val.longValue();
                tmp-=credits;
                entry.setValue(new Long(tmp));
                lowest=Math.min(tmp, lowest);
            }
            return lowest;
        }
        else {
            val=(Long)m.get(dest);
            if(val != null) {
                lowest=val.longValue();
                lowest-=credits;
                m.put(dest, new Long(lowest));
                return lowest;
            }
        }
        return -1;
    }


    private void handleCredit(Address sender, Number increase) {
        if(sender == null) return;
        StringBuffer sb=null;

        if(Util.acquire(lock)) {
            try {
                Long old_credit=(Long)sent.get(sender);
                long increased = old_credit.longValue() + increase.longValue();
                Long new_credit=new Long(Math.min(max_credits, increased));

                if(log.isTraceEnabled()) {
                    sb=new StringBuffer();
                    sb.append("received " + increase + " credit from ").append(sender).append(", old credit was ").
                            append(old_credit).append(", new credits are ").append(new_credit);
                    if (increased > max_credits)
                       sb.append(" ignored over-credit of " + (increased - max_credits));
                }

                sent.put(sender, new_credit);
                lowest_credit=computeLowestCredit(sent);
                if(!creditors.isEmpty()) {  // we are blocked because we expect credit from one or more members
                    
                    if(log.isTraceEnabled())
                       sb.append(".\nCreditors before are: ").append(creditors);
                    
                    creditors.remove(sender);
                    
                    if(log.isTraceEnabled()) {
                        sb.append("\nCreditors after removal of ").append(sender)
                           .append(" are: ").append(creditors)
                           .append("; lowest_credit=").append(lowest_credit);
                    }
                }
                
                if(insufficient_credit && lowest_credit > 0 && creditors.isEmpty()) {
                    insufficient_credit=false;
                    mutex.broadcast();
                    if(log.isTraceEnabled())
                       sb.append("\nTotal block time = " + (System.currentTimeMillis() - start_blocking));
                }
                
                if(log.isTraceEnabled())
                   log.trace(sb.toString());
            }
            finally {
                Util.release(lock);
            }
        }
        else {
            if(log.isWarnEnabled())
                log.warn(increase + " credits from " + sender + " were dropped, lock could not be acquired");
        }
    }

    private static long computeLowestCredit(Map m) {
        Collection credits=m.values(); // List of Longs (credits)
        Long retval=(Long)Collections.min(credits);
        return retval.longValue();
    }


    /**
     * Check whether sender has enough credits left. If not, send him some more
     * @param msg
     */
    private void adjustCredit(Message msg) {
        Address src=msg.getSrc();
        long length=msg.getLength(); // we don't care about headers for the purpose of flow control

        if(src == null) {
            if(log.isErrorEnabled()) log.error("src is null");
            return;
        }

        if(length == 0)
            return; // no effect

        long remaining_cred=decrementCredit(received, src, length);
        long credit_response=max_credits - remaining_cred;
        if(credit_response >= min_credits) {
            received.put(src, max_credits_constant);
            if (!pending_requesters.isEmpty())
               pending_requesters.remove(src);
            if(log.isTraceEnabled()) log.trace("sending " + credit_response + " replenishment credits to " + src);
            sendCredit(src, credit_response);
        }
    }

    private void handleCreditRequest(Address sender, Long sender_credit) {
        if(sender == null) return;

        if(Util.acquire(lock)) {
            long credit_response=0;
            try {
                Long old_credit=(Long)received.get(sender);
                if(old_credit != null) {
                    credit_response=max_credits - old_credit.longValue();
                }

                if(credit_response > 0) {
                    if(log.isTraceEnabled())
                        log.trace("received credit request from " + sender + ": sending " + credit_response + " credits");
                    received.put(sender, max_credits_constant);
                    pending_requesters.remove(sender);
                }
                else {
                    if(pending_requesters.contains(sender)) {
                        // a sender might have negative credits, e.g. -20000. If we subtracted -20000 from max_credits,
                        // we'd end up with max_credits + 20000, and send too many credits back. So if the sender's
                        // credits is negative, we simply send max_credits back
                        long credits_left=sender_credit.longValue();
                        if(credits_left < 0)
                            credits_left=0;
                        credit_response = max_credits - credits_left;
                        // credit_response = max_credits;
                        received.put(sender, max_credits_constant);
                        pending_requesters.remove(sender);
                        if(log.isWarnEnabled())
                            log.warn("Received two credit requests from " + sender +
                                    " without any intervening messages; sending " + credit_response + " credits");
                    }
                    else {
                        pending_requesters.add(sender);
                        if(log.isTraceEnabled())
                            log.trace("received credit request from " + sender + " but have no credits available");
                    }
                }
            }
            finally {
                Util.release(lock);
            }

            if(credit_response > 0)
                sendCredit(sender, credit_response);
        }
    }


    /**
     * Returns the max credits. Handling a credit request should be the exception, not the normal case.
     * @param sender
     * todo: see if this solves Brian's deadlock problems. If not, use the (commented) method above !
     */
//    private void handleCreditRequest(Address sender) {
//        if(sender == null) {
//            if(log.isWarnEnabled())
//                log.warn("sender is null, not able to send credits");
//            return;
//        }
//        
//        if(log.isTraceEnabled()) {
//           Long recL = (Long) received.get(sender);
//           long rec = recL == null ? 0 : max_credits - recL.longValue();
//           log.trace("received credit request from " + sender + ": sending " + 
//                      max_credits + " credits: had received " + rec + " bytes");
//        }
//        
//        received.put(sender, max_credits_constant);
//        sendCredit(sender, max_credits);
//    }


    private void sendCredit(Address dest, long credit) {
        Number number;
        if(credit < Integer.MAX_VALUE)
            number=new Integer((int)credit);
        else
            number=new Long(credit);
        Message msg=new Message(dest, null, number);
        msg.putHeader(name, REPLENISH_HDR);
        passDown(new Event(Event.MSG, msg));
        num_credit_responses_sent++;
    }

    /**
     * Sends a credit request to dest. If the last credit request was sent shortly before (less than max_block_time
     * milliseconds ago), then we discard the request. This ensures that credit requests are not sent more frequently
     * than every max_block_time milliseconds, preventing credit request storms
     * @param dest
     * @param credit_balance
     */
    private void sendCreditRequest(final Address dest, final Long credit_balance) {
        if(max_block_time > 0) {
            // This call is made with the lock released, so ensure the get/put is atomic
            long now=System.currentTimeMillis();
            Long last=(Long)last_credit_request.get(dest);
            if(last != null && now - last.longValue() < max_block_time) {
                return;
            }
            last_credit_request.put(dest, new Long(now));
        }

        if(log.isTraceEnabled())
            log.trace("sending credit request to " + dest + "; balance=" + credit_balance);

        Message msg=new Message(dest, null, credit_balance);
        msg.putHeader(name, CREDIT_REQUEST_HDR);
        passDown(new Event(Event.MSG, msg));
        num_credit_requests_sent++;
    }


    private void handleViewChange(Vector mbrs) {
        Address addr;
        if(mbrs == null) return;
        if(log.isTraceEnabled()) log.trace("new membership: " + mbrs);

        if(Util.acquire(lock)) {
            try {
                // add members not in membership to received and sent hashmap (with full credits)
                for(int i=0; i < mbrs.size(); i++) {
                    addr=(Address)mbrs.elementAt(i);
                    if(!received.containsKey(addr))
                        received.put(addr, max_credits_constant);
                    if(!sent.containsKey(addr))
                        sent.put(addr, max_credits_constant);
                }
                // remove members that left
                for(Iterator it=received.keySet().iterator(); it.hasNext();) {
                    addr=(Address)it.next();
                    if(!mbrs.contains(addr))
                        it.remove();
                }

                // remove members that left
                for(Iterator it=sent.keySet().iterator(); it.hasNext();) {
                    addr=(Address)it.next();
                    if(!mbrs.contains(addr))
                        it.remove(); // modified the underlying map
                }

                // remove all creditors which are not in the new view
                for(int i=0; i < creditors.size(); i++) {
                    Address creditor=(Address)creditors.get(i);
                    if(!mbrs.contains(creditor))
                        creditors.remove(creditor);
                }

                if(log.isTraceEnabled()) log.trace("creditors are " + creditors);
                if(insufficient_credit && creditors.isEmpty()) {
                    lowest_credit=computeLowestCredit(sent);
                    insufficient_credit=false;
                    mutex.broadcast();
                }

                // keep it simple and just clear the last_credit_request Map
                // at worst we get an extra credit request
                last_credit_request.clear();
            }
            finally {
                Util.release(lock);
            }
        }
    }

    private static String printMap(Map m) {
        Map.Entry entry;
        StringBuffer sb=new StringBuffer();
        for(Iterator it=m.entrySet().iterator(); it.hasNext();) {
            entry=(Map.Entry)it.next();
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }


    public static class FcHeader extends Header implements Streamable {
        public static final byte REPLENISH=1;
        public static final byte CREDIT_REQUEST=2; // the sender of the message is the requester

        byte type=REPLENISH;

        public FcHeader() {

        }

        public FcHeader(byte type) {
            this.type=type;
        }

        public long size() {
            return Global.BYTE_SIZE;
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeByte(type);
        }

        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            type=in.readByte();
        }

        public void writeTo(DataOutputStream out) throws IOException {
            out.writeByte(type);
        }

        public void readFrom(DataInputStream in) throws IOException, IllegalAccessException, InstantiationException {
            type=in.readByte();
        }

        public String toString() {
            switch(type) {
                case REPLENISH:
                    return "REPLENISH";
                case CREDIT_REQUEST:
                    return "CREDIT_REQUEST";
                default:
                    return "<invalid type>";
            }
        }
    }


}
