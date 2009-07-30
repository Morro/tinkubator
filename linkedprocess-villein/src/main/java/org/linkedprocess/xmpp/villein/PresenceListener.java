package org.linkedprocess.xmpp.villein;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.Namespace;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.jivesoftware.smackx.packet.DiscoverInfo;
import org.linkedprocess.LinkedProcess;
import org.linkedprocess.xmpp.LopListener;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

/**
 * User: marko
 * Date: Jul 8, 2009
 * Time: 11:57:44 AM
 */
public class PresenceListener extends LopVilleinListener {


    public PresenceListener(XmppVillein xmppVillein) {
        super(xmppVillein);
    }

    public void processPacket(Packet packet) {
        Presence presence = (Presence) packet;

        XmppVillein.LOGGER.info("Presence received from " + presence.getFrom());
        XmppVillein.LOGGER.info(presence.toXML());


        if (presence.getType() == Presence.Type.unavailable ||
                presence.getType() == Presence.Type.unsubscribe ||
                presence.getType() == Presence.Type.unsubscribed) {
            this.getXmppVillein().removeStruct(packet.getFrom());
            return;
        }

        //Struct struct = xmppVillein.getStruct(packet.getFrom());
        //if(null != struct && (struct instanceof HostStruct || struct instanceof FarmStruct || struct instanceof VmStruct)) {
        //    struct.setPresence(presence);
        //} else {
            DiscoverInfo discoInfo = this.getDiscoInfo(packet.getFrom());
    
            if (LinkedProcess.isBareJid(packet.getFrom())) {
                //System.out.println("Bare Jid " + packet.getFrom());
                Struct checkStruct = this.getXmppVillein().getStruct(packet.getFrom(), XmppVillein.StructType.HOST);
                if (checkStruct == null) {
                    HostStruct hostStruct = new HostStruct();
                    hostStruct.setFullJid(packet.getFrom());
                    hostStruct.setPresence(presence);
                    this.getXmppVillein().addHostStruct(hostStruct);
                } else {
                    checkStruct.setPresence(presence);
                }
            } else if (isFarm(discoInfo)) {
                //System.out.println("Farm Jid " + packet.getFrom());
                Struct checkStruct = this.getXmppVillein().getStruct(packet.getFrom(), XmppVillein.StructType.FARM);
                if (checkStruct == null) {
                    FarmStruct farmStruct = new FarmStruct();
                    farmStruct.setFullJid(packet.getFrom());
                    farmStruct.setPresence(presence);
                    farmStruct.setSupportedVmSpecies(this.getSupportedVmSpecies(discoInfo));
                    this.getXmppVillein().addFarmStruct(farmStruct);
                } else {
                    checkStruct.setPresence(presence);
                }

            } else {
                // ONLY REPRESENT THOSE VMS THAT YOU HAVE SPAWNEDs
                //System.out.println("Vm Jid " + packet.getFrom());
                Struct checkStruct = this.getXmppVillein().getStruct(packet.getFrom());
                if (checkStruct != null) {
                    //System.out.println("DOES NOT EQUAL NULL");
                    checkStruct.setPresence(presence);
                }
            }
        //}
    }
}
