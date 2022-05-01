// $Id: DISCARD.java,v 1.10 2005/08/24 13:12:04 belaban Exp $

package org.jgroups.protocols;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Message;
import org.jgroups.stack.Protocol;

import java.util.Properties;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;


/**
 * Discards up or down messages based on a percentage; e.g., setting property 'up' to 0.1 causes 10%
 * of all up messages to be discarded. Setting 'down' or 'up' to 0 causes no loss, whereas 1 discards
 * all messages (not very useful).
 */

public class DISCARD extends Protocol {
    final Vector members=new Vector();
    double up=0.0;    // probability of dropping up   msgs
    double down=0.0;  // probability of dropping down msgs
    boolean excludeItself=false;   //if true don't discard messages sent/received in this stack
    Address localAddress;
    int num_down=0, num_up=0;


    /**
     * All protocol names have to be unique !
     */
    public String getName() {
        return "DISCARD";
    }


    public boolean setProperties(Properties props) {
        String str;

        super.setProperties(props);
        str=props.getProperty("up");
        if(str != null) {
            up=Double.parseDouble(str);
            props.remove("up");
        }

        str=props.getProperty("down");
        if(str != null) {
            down=Double.parseDouble(str);
            props.remove("down");
        }

        str=props.getProperty("excludeitself");
        if(str != null) {
            excludeItself=Boolean.valueOf(str).booleanValue();
            props.remove("excludeitself");
        }


        if(props.size() > 0) {
            log.error("DISCARD.setProperties(): these properties are not recognized: " + props);

            return false;
        }
        return true;
    }


    public void up(Event evt) {
        Message msg;
        double r;

        if(evt.getType() == Event.SET_LOCAL_ADDRESS)
            localAddress=(Address)evt.getArg();


        if(evt.getType() == Event.MSG) {
            msg=(Message)evt.getArg();
            if(up > 0) {
                r=Math.random();
                if(r < up) {
                    if(excludeItself && msg.getSrc().equals(localAddress)) {
                        if(log.isTraceEnabled()) log.trace("excluding itself");
                    }
                    else {
                        if(log.isTraceEnabled()) log.trace("dropping message");
                        num_up++;
                        return;
                    }
                }
            }
        }


        passUp(evt);
    }


    public void down(Event evt) {
        Message msg;
        double r;

        if(evt.getType() == Event.MSG) {
            msg=(Message)evt.getArg();

            if(down > 0) {
                r=Math.random();
                if(r < down) {
                    if(excludeItself && msg.getSrc().equals(localAddress)) {
                        if(log.isTraceEnabled()) log.trace("excluding itself");
                    }
                    else {
                        if(log.isTraceEnabled())
                            log.trace("dropping message");
                        num_down++;
                        return;
                    }
                }
            }

        }
        passDown(evt);
    }

    public void resetStats() {
        super.resetStats();
        num_down=num_up=0;
    }

    public Map dumpStats() {
        Map m=new HashMap(2);
        m.put("num_dropped_down", new Integer(num_down));
        m.put("num_dropped_up", new Integer(num_up));
        return m;
    }
}
