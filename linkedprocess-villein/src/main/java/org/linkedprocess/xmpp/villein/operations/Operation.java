package org.linkedprocess.xmpp.villein.operations;

import org.linkedprocess.xmpp.villein.XmppVillein;
import org.jivesoftware.smack.packet.XMPPError;

/**
 * User: marko
 * Date: Aug 7, 2009
 * Time: 3:50:57 PM
 */
public abstract class Operation {

    protected final XmppVillein xmppVillein;

    public Operation(final XmppVillein xmppVillein) {
        this.xmppVillein = xmppVillein;
    }

}
