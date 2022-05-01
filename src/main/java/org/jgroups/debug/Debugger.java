// $Id: Debugger.java,v 1.7 2006/11/17 13:39:18 belaban Exp $

package org.jgroups.debug;

import org.jgroups.JChannel;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Vector;


/**
 * The Debugger displays a graphical view of the protocol stack by showing all the protocols and
 * the events in them.
 *
 * @author Bela Ban
 */
public class Debugger extends JFrame {
    JChannel channel=null;
    Vector prots=new Vector();
    JButton b1, b2;
    JPanel button_panel;
    JTable table;
    DefaultTableModel table_model;
    JScrollPane scroll_pane;
    public static final Font helvetica_12=new Font("Helvetica", Font.PLAIN, 12);



    public Debugger() {
        super("Debugger Window");
    }


    public Debugger(JChannel channel) {
        super("Debugger Window");
        this.channel=channel;
    }


    public Debugger(JChannel channel, String name) {
        super(name);
        this.channel=channel;
    }


    public void setChannel(JChannel channel) {
        this.channel=channel;
    }


    public void start() {
        Protocol prot;
        ProtocolStack stack;
        ProtocolView view=null;

        if(channel == null) return;
        stack=channel.getProtocolStack();
        prots=stack.getProtocols();

        setBounds(new Rectangle(30, 30, 300, 300));
        table_model=new DefaultTableModel();
        table=new JTable(table_model);
        table.setFont(helvetica_12);
        scroll_pane=new JScrollPane(table);
        table_model.setColumnIdentifiers(new String[]{"Index", "Name", "up", "down"});

        getContentPane().add(scroll_pane);
        show();

        for(int i=0; i < prots.size(); i++) {
            prot=(Protocol)prots.elementAt(i);
            view=new ProtocolView(prot, table_model, i);
            prot.setObserver(view);
            table_model.insertRow(i, new Object[]{String.valueOf((i + 1)),
                    prot.getName(), String.valueOf(prot.getUpQueue().size()),
                    "0", "0", "0"});
        }
    }

    public void stop() {
        Protocol prot;
        ProtocolStack stack;

        if(channel == null) return;
        stack=channel.getProtocolStack();
        prots=stack.getProtocols();

        for(int i=0; i < prots.size(); i++) {
            prot=(Protocol)prots.elementAt(i);
            prot.setObserver(null);
        }
        dispose();
    }



    public static void main(String[] args) {
        Debugger d=new Debugger();
        d.start();
    }
}

