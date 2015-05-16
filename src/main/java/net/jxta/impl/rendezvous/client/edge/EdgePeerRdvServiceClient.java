/*
 * Copyright (c) 2001-2007 Sun Microsystems, Inc.  All rights reserved.
 *
 *  The Sun Project JXTA(TM) Software License
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *  3. The end-user documentation included with the redistribution, if any, must
 *     include the following acknowledgment: "This product includes software
 *     developed by Sun Microsystems, Inc. for JXTA(TM) technology."
 *     Alternately, this acknowledgment may appear in the software itself, if
 *     and wherever such third-party acknowledgments normally appear.
 *
 *  4. The names "Sun", "Sun Microsystems, Inc.", "JXTA" and "Project JXTA" must
 *     not be used to endorse or promote products derived from this software
 *     without prior written permission. For written permission, please contact
 *     Project JXTA at http://www.jxta.org.
 *
 *  5. Products derived from this software may not be called "JXTA", nor may
 *     "JXTA" appear in their name, without prior written permission of Sun.
 *
 *  THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES,
 *  INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 *  FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL SUN
 *  MICROSYSTEMS OR ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 *  LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 *  OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 *  EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *  JXTA is a registered trademark of Sun Microsystems, Inc. in the United
 *  States and other countries.
 *
 *  Please see the license information page at :
 *  <http://www.jxta.org/project/www/license.html> for instructions on use of
 *  the license in source files.
 *
 *  ====================================================================
 *
 *  This software consists of voluntary contributions made by many individuals
 *  on behalf of Project JXTA. For more information on Project JXTA, please see
 *  http://www.jxta.org.
 *
 *  This license is based on the BSD license adopted by the Apache Foundation.
 */

package net.jxta.impl.rendezvous.client.edge;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.jxta.discovery.DiscoveryService;
import net.jxta.document.Advertisement;
import net.jxta.document.AdvertisementFactory;
import net.jxta.document.StructuredDocumentFactory;
import net.jxta.document.XMLDocument;
import net.jxta.endpoint.EndpointAddress;
import net.jxta.endpoint.Message;
import net.jxta.endpoint.MessageElement;
import net.jxta.endpoint.MessageTransport;
import net.jxta.endpoint.Messenger;
import net.jxta.endpoint.TextDocumentMessageElement;
import net.jxta.id.ID;
import net.jxta.id.IDFactory;
import net.jxta.impl.endpoint.relay.RelayReferralSeedingManager;
import net.jxta.impl.protocol.RdvConfigAdv;
import net.jxta.impl.rendezvous.PeerConnection;
import net.jxta.impl.rendezvous.RendezVousPropagateMessage;
import net.jxta.impl.rendezvous.RendezVousServiceImpl;
import net.jxta.impl.rendezvous.RendezVousServiceProvider;
import net.jxta.impl.rendezvous.StdRendezVousService;
import net.jxta.impl.rendezvous.rendezvousMeter.RendezvousConnectionMeter;
import net.jxta.impl.rendezvous.rendezvousMeter.RendezvousMeterBuildSettings;
import net.jxta.impl.rendezvous.rpv.PeerviewSeedingManager;
import net.jxta.impl.util.SeedingManager;
import net.jxta.impl.util.TimeUtils;
import net.jxta.impl.util.URISeedingManager;
import net.jxta.impl.util.threads.SelfCancellingTask;
import net.jxta.logging.Logger;
import net.jxta.logging.Logging;
import net.jxta.peer.PeerID;
import net.jxta.peergroup.PeerGroup;
import net.jxta.peergroup.PeerGroupID;
import net.jxta.platform.Module;
import net.jxta.protocol.ConfigParams;
import net.jxta.protocol.PeerAdvertisement;
import net.jxta.protocol.RouteAdvertisement;
import net.jxta.rendezvous.RendezvousEvent;

/**
 * A JXTA {@link net.jxta.rendezvous.RendezVousService} implementation which
 * implements the client portion of the standard JXTA Rendezvous Protocol (RVP).
 *
 * @see net.jxta.rendezvous.RendezVousService
 * @see <a href="https://jxta-spec.dev.java.net/nonav/JXTAProtocols.html#proto-rvp" target="_blank">JXTA Protocols Specification : Rendezvous Protocol</a>
 */
public class EdgePeerRdvServiceClient extends StdRendezVousService {

    private final static transient Logger LOG = Logging.getLogger(EdgePeerRdvServiceClient.class.getName());

    /**
     * Interval in milliseconds at which we will check our rendezvous connection.
     */
    private final static long MONITOR_INTERVAL = 15 * TimeUtils.ASECOND;

    /**
     * Number of rendezvous we will try to connect to.
     */
    private final int MAX_RDV_CONNECTIONS = 1;

    /**
     * The default amount of time we will attempt to renew a lease before it
     * expires.
     */
    private long LEASE_MARGIN = 5 * TimeUtils.AMINUTE;

    /**
     * Our source for rendezvous server seeds.
     */
    private final SeedingManager seedingManager;

    /**
     * Our current seeds.
     */
    private final List<RouteAdvertisement> seeds = new ArrayList<>();

    /**
     * Our current connections with RendezVous peers.
     */
    private final Map<ID, RdvConnection> rendezVous = Collections.synchronizedMap(new HashMap<ID, RdvConnection>());

    private MonitorTask monitorTask;

    /**
     * Standard Constructor
     *
     * @param peerGroup      Description of Parameter
     * @param rendezvousServiceImplementation Description of Parameter
     */
    public EdgePeerRdvServiceClient(PeerGroup peerGroup, RendezVousServiceImpl rendezvousServiceImplementation) {
        super(peerGroup, rendezvousServiceImplementation);

        Advertisement adv = null;
        ConfigParams confAdv = peerGroup.getConfigAdvertisement();

        // Get the config. If we do not have a config, we're done; we just keep the defaults (edge peer/no auto-rdv)
        if (confAdv != null) {
            adv = confAdv.getSvcConfigAdvertisement(rendezvousServiceImplementation.getAssignedID());
        }

        RdvConfigAdv rendezvoudConfigurationAdvertisement;

        if (!(adv instanceof RdvConfigAdv)) {
            Logging.logCheckedDebug(LOG, "Creating new RdvConfigAdv for defaults.");
            rendezvoudConfigurationAdvertisement = (RdvConfigAdv) AdvertisementFactory.newAdvertisement(RdvConfigAdv.getAdvertisementType());
        } else {
            rendezvoudConfigurationAdvertisement = (RdvConfigAdv) adv;
        }

        if (rendezvoudConfigurationAdvertisement.getMaxTTL() != -1) {
            MAX_TTL = rendezvoudConfigurationAdvertisement.getMaxTTL();
        }

        if (rendezvoudConfigurationAdvertisement.getLeaseMargin() != 0) {
            LEASE_MARGIN = rendezvoudConfigurationAdvertisement.getLeaseMargin();
        }

        String serviceName = rendezvousServiceImplementation.getAssignedID().toString() + peerGroup.getPeerGroupID().getUniqueValue().toString();
        System.err.println("EdgePeerRdvService: " + peerGroup.getPeerGroupID().toString());

        URISeedingManager uriSeedingManager;

        if (rendezvoudConfigurationAdvertisement.getProbeRelays()) {
            uriSeedingManager = new RelayReferralSeedingManager(rendezvoudConfigurationAdvertisement.getAclUri(), rendezvoudConfigurationAdvertisement.getUseOnlySeeds(), peerGroup, serviceName);
        } else {
            uriSeedingManager = new URISeedingManager(rendezvoudConfigurationAdvertisement.getAclUri(), rendezvoudConfigurationAdvertisement.getUseOnlySeeds(), peerGroup, serviceName);
        }

        for (URI aSeeder : Arrays.asList(rendezvoudConfigurationAdvertisement.getSeedingURIs())) {
            Logging.logCheckedConfig(LOG, "EdgePeerRdvService adding seeding: ", aSeeder);
            uriSeedingManager.addSeedingURI(aSeeder);
        }

        for (URI aSeed : Arrays.asList(rendezvoudConfigurationAdvertisement.getSeedRendezvous())) {
            Logging.logCheckedConfig(LOG, "EdgePeerRdvService adding seed   : ", aSeed);
            uriSeedingManager.addSeed(aSeed);
        }

        this.seedingManager = uriSeedingManager;

        Logging.logCheckedInfo(LOG, "RendezVous Service is initialized for ", peerGroup.getPeerGroupID(), " as an EDGE peer.");
    }

    /**
     * Listener for
     * <p/>
     * &lt;assignedID>
     */
    private class StdRdvEdgeProtocolListener implements StdRendezVousService.StdRdvProtocolListener {

        /**
         * {@inheritDoc}
         */
        @Override
        public void processIncomingMessage(Message msg, EndpointAddress srcAddr, EndpointAddress dstAddr) {

            Logging.logCheckedDebug(LOG, "[", peerGroup.getPeerGroupID(), "] processing ", msg);

            if ((msg.getMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, ConnectedPeerReply) != null)
                    || (msg.getMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, ConnectedRdvAdvReply) != null)) {
                processConnectedReply(msg);
            }

            if (msg.getMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, DisconnectRequest) != null) {
                processDisconnectRequest(msg);
            }
        }
    }

    /**
     * {@inheritDoc}
     * @return 
     */
    @Override
    protected int startApp(String[] arg) {

        super.startApp(arg, new StdRdvEdgeProtocolListener());

        // The other services may not be fully functional but they're there
        // so we can start our subsystems.
        // As for us, it does not matter if our methods are called between init
        // and startApp().

        if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousMeter != null)) {
            rendezvousMeter.startEdge();
        }

        rendezvousServiceImplementation.generateEvent(RendezvousEvent.BECAMEEDGE, peerGroup.getPeerID());

        scheduleMonitor(0);

        return Module.START_OK;
    }

    private void scheduleMonitor(long delayInMs) {
        stopMonitor();
        ScheduledExecutorService scheduledExecutor = peerGroup.getTaskManager().getScheduledExecutorService();
        MonitorTask monitorTask = new MonitorTask();
        monitorTask.setHandle(scheduledExecutor.scheduleAtFixedRate(monitorTask, delayInMs, MONITOR_INTERVAL, TimeUnit.MILLISECONDS));
    }

    private void stopMonitor() {
        if(monitorTask != null) {
            monitorTask.cancel();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void stopApp() {
        if (closed) {
            return;
        }
        
        closed = true;
        seedingManager.stop();
        disconnectFromAllRendezVous();
        stopMonitor();
        super.stopApp();

        if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousMeter != null)) {
            rendezvousMeter.stopEdge();
        }
    }

    /**
     * {@inheritDoc}
     * @return 
     */
    @Override
    public Vector<ID> getConnectedPeerIDs() {
        return new Vector<ID>(rendezVous.keySet());
    }

    /**
     * {@inheritDoc}
     * @return 
     */
    @Override
    public boolean isConnectedToRendezVous() {
        return !rendezVous.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void connectToRendezVous(EndpointAddress addr, Object hint) {
        if (seedingManager instanceof URISeedingManager) {
            URISeedingManager uriseed = (URISeedingManager) seedingManager;

            if (hint instanceof RouteAdvertisement) {
                uriseed.addSeed((RouteAdvertisement) hint);
            } else {
                uriseed.addSeed(addr.toURI());
            }
        } else if (seedingManager instanceof PeerviewSeedingManager) {
            PeerviewSeedingManager pvseed = (PeerviewSeedingManager) seedingManager;

            if (hint instanceof RouteAdvertisement) {
                pvseed.addSeed((RouteAdvertisement) hint);
            }
        }
    }

    /**
     * {@inheritDoc}
     * @param peerid
     */
    @Override
    public void challengeRendezVous(ID peerid, long delay) {

        // If immediate failure is requested, just do it.
        // {@code disconnectFromRendezVous()} will at least get the peer
        // removed from the peerView, even if it is not currently a rendezvous
        // of ours. That permits to purge from the peerview rdvs that we try
        // and fail to connect to, faster than the background keep alive done
        // by PeerView itself.
        if (delay <= 0) {
            removeRdv(peerid, false);
            return;
        }

        RdvConnection pConn = rendezVous.get(peerid);

        if (null != pConn) {
            long adjusted_delay = Math.max(0, Math.min(TimeUtils.toRelativeTimeMillis(pConn.getLeaseEnd()), delay));

            pConn.setLease(adjusted_delay, adjusted_delay);
        }
    }

    /**
     * {@inheritDoc}
     * @param peerId
     */
    @Override
    public void disconnectFromRendezVous(ID peerId) {
        removeRdv(peerId, false);
    }

    /**
     * {@inheritDoc}
     * @throws java.io.IOException
     */
    @Override
    public void propagate(Message msg, String serviceName, String serviceParam, int initialTTL) throws IOException {

        msg = msg.clone();
        int useTTL = Math.min(initialTTL, MAX_TTL);

        Logging.logCheckedDebug(LOG, "Propagating ", msg, "(TTL=", useTTL, ") to :\n\tsvc name:", serviceName, "\tsvc params:", serviceParam);

        RendezVousPropagateMessage propHdr = updatePropHeader(msg, getPropHeader(msg), serviceName, serviceParam, useTTL);

        if (null != propHdr) {
            sendToEachConnection(msg, propHdr);
            sendToNetwork(msg, propHdr);

            if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousMeter != null)) {
                rendezvousMeter.propagateToGroup();
            }

        } else {
            Logging.logCheckedDebug(LOG, "Declining to propagate ", msg, " (No prop header)");
        }
    }

    /**
     * {@inheritDoc}
     * @param msg
     * @param serviceName
     * @param serviceParam
     * @param initialTTL
     * @throws java.io.IOException
     */
    @Override
    public void propagateInGroup(Message msg, String serviceName, String serviceParam, int initialTTL) throws IOException {

        msg = msg.clone();
        int useTTL = Math.min(initialTTL, MAX_TTL);

        Logging.logCheckedDebug(LOG, "Propagating ", msg, "(TTL=", useTTL, ") in group to :\n\tsvc name:", serviceName,
                "\tsvc params:", serviceParam);

        RendezVousPropagateMessage propHdr = updatePropHeader(msg, getPropHeader(msg), serviceName, serviceParam, useTTL);

        if (null != propHdr) {
            sendToEachConnection(msg, propHdr);

            if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousMeter != null)) {
                rendezvousMeter.propagateToGroup();
            }
        } else {
            Logging.logCheckedDebug(LOG, "Declining to propagate ", msg, " (No prop header)");
        }
    }

    /**
     * {@inheritDoc}
     * @throws java.io.IOException
     */
    @Override
    public void walk(Message msg, String serviceName, String serviceParam, int initialTTL) throws IOException {
        propagateInGroup(msg, serviceName, serviceParam, initialTTL);
    }

    /**
     * {@inheritDoc}
     * @throws java.io.IOException
     */
    @Override
    public void walk(Vector<? extends ID> destPeerIDs, Message msg, String serviceName, String serviceParam, int initialTTL) throws IOException {
        propagate(Collections.enumeration(destPeerIDs), msg, serviceName, serviceParam, initialTTL);
    }

    /**
    * 
    * @param peer
    * @return PeerConnection
    * */
    @Override
    public PeerConnection getPeerConnection(ID peer) {
        return rendezVous.get(peer);
    }

    /**
     * @return PeerConnection[]
     * */
    @Override
    protected PeerConnection[] getPeerConnections() {
        return rendezVous.values().toArray(new PeerConnection[0]);
    }

    private void disconnectFromAllRendezVous() {
        for (RdvConnection pConn : new ArrayList<>(rendezVous.values())) {
            try {
                disconnectFromRendezVous(pConn.getPeerID());
            } catch (Exception failed) {
                Logging.logCheckedWarning(LOG, "disconnectFromRendezVous failed for ", pConn, "\n", failed);
            }
        }
    }

    /**
     * Handle a disconnection request from a remote peer.
     *
     * @param msg Description of Parameter
     */
    private void processDisconnectRequest(Message msg) {
        try {
            MessageElement elem = msg.getMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, DisconnectRequest);

            if (null != elem) {
                XMLDocument asDoc = (XMLDocument) StructuredDocumentFactory.newStructuredDocument(elem);

                PeerAdvertisement adv = (PeerAdvertisement) AdvertisementFactory.newAdvertisement(asDoc);

                RdvConnection rdvConnection = rendezVous.get(adv.getPeerID());

                if (null != rdvConnection) {
                    rdvConnection.setConnected(false);
                    removeRdv(adv.getPeerID(), true);
                } else {
                    Logging.logCheckedDebug(LOG, "Ignoring disconnect request from ", adv.getPeerID());
                }
            }
        } catch (Exception failure) {
            Logging.logCheckedWarning(LOG, "Failure processing disconnect request\n", failure);
        }
    }

    /**
     * Add a rendezvous to our collection of rendezvous peers.
     *
     * @param padv  PeerAdvertisement for the rendezvous peer.
     * @param lease The duration of the lease in relative milliseconds.
     */
    private void addRendezVousPeer(PeerAdvertisement padv, long lease) {

        int eventType;

        RdvConnection rdvConnection;

        synchronized (rendezVous) {
            rdvConnection = rendezVous.get(padv.getPeerID());

            if (null == rdvConnection) {
                rdvConnection = new RdvConnection(peerGroup, rendezvousServiceImplementation, padv.getPeerID());
                rendezVous.put(padv.getPeerID(), rdvConnection);
                eventType = RendezvousEvent.RDVCONNECT;
            } else {
                eventType = RendezvousEvent.RDVRECONNECT;
            }
        }

        // Check if the peer is already registered.
        if (RendezvousEvent.RDVRECONNECT == eventType) {

            Logging.logCheckedInfo(LOG, "Renewed RDV lease from ", rdvConnection);

            // Already connected, just upgrade the lease

            if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousServiceMonitor != null)) {
                RendezvousConnectionMeter rendezvousConnectionMeter = rendezvousServiceMonitor.getRendezvousConnectionMeter(
                        padv.getPeerID());

                rendezvousConnectionMeter.leaseRenewed(lease);
            }
        } else {
            Logging.logCheckedInfo(LOG, "New RDV lease from ", rdvConnection);

            if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousServiceMonitor != null)) {
                RendezvousConnectionMeter rendezvousConnectionMeter = rendezvousServiceMonitor.getRendezvousConnectionMeter(
                        padv.getPeerID());

                rendezvousConnectionMeter.connectionEstablished(lease);
            }
        }

        rdvConnection.connect(padv, lease, Math.min(LEASE_MARGIN, (lease / 2)));
        rendezvousServiceImplementation.generateEvent(eventType, padv.getPeerID());
    }

    /**
     * Remove the specified rendezvous from our collection of rendezvous.
     *
     * @param rdvid the id of the rendezvous to remove.
     * @param requested if true, indicates a requested operation
     */
    private void removeRdv(ID rdvid, boolean requested) {

        Logging.logCheckedInfo(LOG, "Disconnect from RDV ", rdvid);

        PeerConnection rdvConnection;

        synchronized (rendezVous) {
            rdvConnection = rendezVous.remove(rdvid);
        }

        if (null != rdvConnection) {
            if (rdvConnection.isConnected()) {
                rdvConnection.setConnected(false);
                sendDisconnect(rdvConnection);
            }
        }

        rendezvousServiceImplementation.generateEvent(requested ? RendezvousEvent.RDVDISCONNECT : RendezvousEvent.RDVFAILED, rdvid);

        if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousServiceMonitor != null)) {
            RendezvousConnectionMeter rendezvousConnectionMeter = rendezvousServiceMonitor.getRendezvousConnectionMeter(
                    (PeerID) rdvid);

            rendezvousConnectionMeter.connectionDisconnected();
        }
    }

    /**
     *  Send lease request to the specified peer.
     *
     *  @param pConn The peer to which the message should be sent.
     *  @throws IOException Thrown for errors sending the lease request.
     */
    private void sendLeaseRequest(RdvConnection pConn) throws IOException {

        Logging.logCheckedDebug(LOG, "Sending Lease request to ", pConn);

        RendezvousConnectionMeter rendezvousConnectionMeter = null;

        if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousServiceMonitor != null)) {
            rendezvousConnectionMeter = rendezvousServiceMonitor.getRendezvousConnectionMeter(pConn.getPeerID().toString());
        }

        Message msg = new Message();

        // The request simply includes the local peer advertisement.
        msg.replaceMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, new TextDocumentMessageElement(ConnectRequest, getPeerAdvertisementDoc(), null));

        pConn.sendMessage(msg, pName, pParam);

        if (RendezvousMeterBuildSettings.RENDEZVOUS_METERING && (rendezvousConnectionMeter != null)) {
            rendezvousConnectionMeter.beginConnection();
        }
    }

    /**
     * Processes peer connection reply from the rendezvous peer service
     *
     * @param msg Description of Parameter
     */
    private void processConnectedReply(Message msg) {
        // Get the Peer Advertisement of the RDV.
        MessageElement peerElem = msg.getMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, ConnectedRdvAdvReply);

        if (null == peerElem) {
            Logging.logCheckedDebug(LOG, "Missing rendezvous peer advertisement");
            return;
        }

        long lease;

        try {
            MessageElement el = msg.getMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, ConnectedLeaseReply);

            if (el == null) {
                Logging.logCheckedDebug(LOG, "missing lease");
                return;
            }

            lease = Long.parseLong(el.toString());
        } catch (Exception e) {
            Logging.logCheckedDebug(LOG, "Parse lease failed with\n", e);
            return;
        }

        ID pId;
        MessageElement el = msg.getMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, ConnectedPeerReply);

        if (el == null) {
            Logging.logCheckedDebug(LOG, "missing rdv peer");
            return;
        }

        try {
            pId = IDFactory.fromURI(new URI(el.toString()));
        } catch (URISyntaxException badID) {
            Logging.logCheckedDebug(LOG, "Bad RDV peer ID");
            return;
        }

        if (lease <= 0) {
            removeRdv(pId, false);
        } else {
            if (rendezVous.containsKey(pId) || (rendezVous.size() < MAX_RDV_CONNECTIONS)) {
                PeerAdvertisement padv = null;

                try {

                    XMLDocument asDoc = (XMLDocument) StructuredDocumentFactory.newStructuredDocument(peerElem);
                    padv = (PeerAdvertisement) AdvertisementFactory.newAdvertisement(asDoc);

                } catch (Exception failed) {
                    Logging.logCheckedWarning(LOG, "Failed processing peer advertisement");
                }

                if (null == padv) {
                    Logging.logCheckedDebug(LOG, "Missing rendezvous peer advertisement");
                    return;
                }

                if (!seedingManager.isAcceptablePeer(padv)) {
                    Logging.logCheckedDebug(LOG, "Rejecting lease offer from unacceptable peer : ", padv.getPeerID());

                    // XXX bondolo 20061123 perhaps we should send a disconnect here.
                    return;
                }

                addRendezVousPeer(padv, lease);

                try {
                    DiscoveryService discovery = peerGroup.getDiscoveryService();

                    if (null != discovery) {
                        // This is not our own peer adv so we choose not to share it and keep it for only a short time.
                        discovery.publish(padv, lease * 2, 0);
                    }
                } catch (IOException e) {
                    Logging.logCheckedDebug(LOG, "failed to publish Rendezvous Advertisement\n", e);
                }

                String rdvName = padv.getName();

                if (null == padv.getName()) {
                    rdvName = pId.toString();
                }

                Logging.logCheckedDebug(LOG, "RDV Connect Response : peer=", rdvName, " lease=", lease, "ms");
            } else {
                Logging.logCheckedDebug(LOG, "Ignoring lease offer from ", pId);
                // XXX bondolo 20040423 perhaps we should send a disconnect here.
            }
        }
    }

    /**
     * A timer task for monitoring our active rendezvous connections.
     * <p/>
     * Checks leases, initiates lease renewals, starts new lease requests.
     */
    private class MonitorTask extends SelfCancellingTask {

        /**
         * @inheritDoc
         */
        @Override
        public void execute() {
            try {

                Logging.logCheckedDebug(LOG, "[", peerGroup, "] Periodic rendezvous check");

                if (closed) {
                    return;
                }

                if (!PeerGroupID.worldPeerGroupID.equals(peerGroup.getPeerGroupID())) {
                    MessageTransport router = rendezvousServiceImplementation.endpoint.getEndpointRouter();

                    if (null == router) {

                        Logging.logCheckedWarning(LOG, "Rendezvous connection stalled until router is started!");

                        // Reschedule another run very soon.
                        this.cancel();
                        scheduleMonitor(2 * TimeUtils.ASECOND);
                        return;
                    }
                }

                List<RdvConnection> currentRdvs = new ArrayList<>(rendezVous.values());

                for (RdvConnection pConn : currentRdvs) {
                    try {

                        if (!pConn.isConnected()) {
                            Logging.logCheckedInfo(LOG, "[", peerGroup.getPeerGroupID(), "] Lease expired. Disconnected from ", pConn);
                            removeRdv(pConn.getPeerID(), false);
                            continue;
                        }

                        if (TimeUtils.toRelativeTimeMillis(pConn.getRenewal()) <= 0) {
                            Logging.logCheckedDebug(LOG, "[", peerGroup.getPeerGroupID(), "] Attempting lease renewal for ", pConn);
                            sendLeaseRequest(pConn);
                        }

                    } catch (Exception e) {
                        Logging.logCheckedWarning(LOG, "[", peerGroup.getPeerGroupID(), "] Failure while checking ", pConn, e);
                    }
                }

                // Not enough Rdvs? Try finding more.
                if (rendezVous.size() < MAX_RDV_CONNECTIONS) {
                    if (seeds.isEmpty()) {
                        seeds.addAll(Arrays.asList(EdgePeerRdvServiceClient.this.seedingManager.getActiveSeedRoutes()));
                    }

                    int sentLeaseRequests = 0;

                    while (!seeds.isEmpty() && (sentLeaseRequests < 3)) {
                        RouteAdvertisement aSeed = seeds.remove(0);

                        Message msg = new Message();

                        // The lease request simply includes the local peer advertisement.
                        msg.addMessageElement(RendezVousServiceProvider.RDV_MSG_NAMESPACE_NAME, new TextDocumentMessageElement(ConnectRequest, getPeerAdvertisementDoc(), null));

                        Messenger msgr = null;

                        if (null == aSeed.getDestPeerID()) {
                            // It is an incomplete route advertisement. We are going to assume that it is only a wrapper for a single ea.
                            List<String> seed_eas = aSeed.getDest().getVectorEndpointAddresses();

                            if (!seed_eas.isEmpty()) {
                                EndpointAddress aSeedHost = new EndpointAddress(seed_eas.get(0));

                                msgr = rendezvousServiceImplementation.endpoint.getMessengerImmediate(aSeedHost, null);
                            }
                        } else {
                            // We have a full route, send it to the virtual address of the route!
                            EndpointAddress aSeedHost = new EndpointAddress(aSeed.getDestPeerID(), null, null);

                            msgr = rendezvousServiceImplementation.endpoint.getMessengerImmediate(aSeedHost, aSeed);
                        }

                        if (null != msgr) {
                            try {
                                msgr.sendMessageN(msg, pName, pParam);
                                sentLeaseRequests++;
                            } catch (Exception failed) {
                                // ignored
                            }
                        }
                    }
                } else {
                    // We don't need any of the current seeds. Get new ones when we need them.
                    seeds.clear();
                }

            } catch (Throwable t) {
                Logging.logCheckedWarning(LOG, "Uncaught throwable in thread :", Thread.currentThread().getName(), "\n", t);
            }
        }
    }
}