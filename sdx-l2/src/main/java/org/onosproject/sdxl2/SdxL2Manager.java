/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.sdxl2;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP6;
import org.onlab.packet.IPv6;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.onlab.packet.Ethernet.TYPE_ARP;
import static org.onlab.packet.Ethernet.TYPE_IPV6;
import static org.onlab.packet.ICMP6.NEIGHBOR_ADVERTISEMENT;
import static org.onlab.packet.ICMP6.NEIGHBOR_SOLICITATION;
import static org.onlab.packet.IPv6.PROTOCOL_ICMP6;
import static org.onosproject.net.packet.PacketPriority.CONTROL;


/**
 * Implements SdxL2Service.
 */
@Component(immediate = true)
@Service
public class SdxL2Manager implements SdxL2Service {

    private static final String SDXL2_APP = "org.onosproject.sdxl2";
    private static final String ERROR_ADD_VC_VLANS =
            "Cannot create VC when CPs have different number of VLANs";
    private static final String ERROR_ADD_VC_VLANS_CLI =
            "\u001B[0;31mError executing command: " + ERROR_ADD_VC_VLANS + "\u001B[0;49m";
    private static final String VC_0 = "MAC";
    private static Logger log = LoggerFactory.getLogger(SdxL2Manager.class);
    private static final String ERROR_ADD_VC_CPS = "Unable to find %s and %s in sdxl2=%s";
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected SdxL2Store sdxL2Store;
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentService intentService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected EdgePortService edgePortService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;
    protected SdxL2Processor processor = new SdxL2Processor();
    protected ApplicationId appId;
    protected SdxL2MonitoringService monitoringManager;
    protected SdxL2ArpNdpHandler arpndpHandler;
    protected SdxL2VCService vcManager;


    /**
     * Activates the implementation of the SDX-L2 service.
     * @param context ComponentContext object
     */
    @Activate
    protected void activate(ComponentContext context) {
        appId = coreService.registerApplication(SDXL2_APP);
        monitoringManager = new SdxL2MonitoringManager(appId, intentService, edgePortService);
        SdxL2ArpNdpHandler.vcType = VC_0;
        vcManager = new SdxL2MacVCManager(appId, sdxL2Store, intentService);
        handleArpNdp();
        log.info("Started");
    }

    /**
     * Deactivates the implementation of the SDX-L2 service.
     */
    @Deactivate
    protected void deactivate() {
        this.cleanSdxL2();
        unhandleArpNdp();
        log.info("Stopped");
    }

    @Override
    public void createSdxL2(String sdxl2) {

        checkNotNull(sdxl2, "sdxl2 name cannot be null");
        checkState(!sdxl2.contains(","), "sdxl2 names cannot contain commas");
        checkState(!sdxl2.contains("|"), "sdxl2 names cannot contain pipe");
        checkState(!sdxl2.contains("-"), "sdxl2 names cannot contain dash");
        checkState(!sdxl2.contains(":"), "sdxl2 names cannot contain colon");

        try {
            this.sdxL2Store.putSdxL2(sdxl2);
        } catch (SdxL2Exception e) {
            log.info(e.getMessage());
        }
    }

    @Override
    public void deleteSdxL2(String sdxl2) {

        checkNotNull(sdxl2, "sdxl2 name cannot be null");

        try {
            this.sdxL2Store.removeSdxL2(sdxl2);
        } catch (SdxL2Exception e) {
            log.info(e.getMessage());
        }

    }

    @Override
    public Set<String> getSdxL2s() {
        return this.sdxL2Store.getSdxL2s();
    }

    @Override
    public void addSdxL2ConnectionPoint(String sdxl2, SdxL2ConnectionPoint sdxl2cp) {

        checkNotNull(sdxl2, "sdxl2 name cannot be null");
        checkNotNull(sdxl2cp, "SdxL2ConnectionPoint cannot be null");

        try {
            this.sdxL2Store.addSdxL2ConnectionPoint(sdxl2, sdxl2cp);
        } catch (SdxL2Exception e) {
            log.info(e.getMessage());
        }

    }

    @Override
    public Set<String> getSdxL2ConnectionPoints(Optional<String> sdxl2) {

        try {
            return this.sdxL2Store.getSdxL2ConnectionPoints(sdxl2);
        } catch (SdxL2Exception e) {
            log.info(e.getMessage());
        }

        return Collections.emptySet();
    }

    @Override
    public void removeSdxL2ConnectionPoint(String sdxl2cp) {

        checkNotNull(sdxl2cp, "SdxL2ConnectionPoint name cannot be null");

        try {
            this.sdxL2Store.removeSdxL2ConnectionPoint(sdxl2cp);
        } catch (SdxL2Exception e) {
            log.info(e.getMessage());
        }

    }

    @Override
    public void addVC(String sdxl2, String sdxl2cplhs, String sdxl2cprhs) {
        SdxL2ConnectionPoint lhs = this.getSdxL2ConnectionPoint(sdxl2cplhs);
        SdxL2ConnectionPoint rhs = this.getSdxL2ConnectionPoint(sdxl2cprhs);

        Set<String> cps = this.getSdxL2ConnectionPoints(Optional.of(sdxl2))
                .parallelStream()
                .filter(cptemp -> (cptemp.equals(sdxl2cplhs) || cptemp.equals(sdxl2cprhs)))
                .collect(Collectors.toSet());

        checkState(cps.size() == 2, ERROR_ADD_VC_CPS, sdxl2cplhs, sdxl2cprhs, sdxl2);

        if ((lhs.vlanIds().size() != rhs.vlanIds().size()) &&
                (lhs.vlanIds().size() > 1 || rhs.vlanIds().size() > 1)) {
            // User can correct this issue in the CLI. Show in console and log
            System.err.println(ERROR_ADD_VC_VLANS_CLI);
            log.info(ERROR_ADD_VC_VLANS);
            return;
        }
        this.vcManager.addVC(sdxl2, lhs, rhs);
    }

    @Override
    public void removeVC(String vc) {
        checkNotNull(vc, "VC name cannot be null");
        String[] splitKeyCPs = vc.split(":");
        checkState(splitKeyCPs.length == 2, "Bad name format $sdx:$something");
        String[] cps = splitKeyCPs[1].split("-");
        checkState(cps.length == 2, "Bad name format $sdx:$lhs-$rhs");

        String lhsName = cps[0];
        String rhsName = cps[1];
        SdxL2ConnectionPoint lhs = this.getSdxL2ConnectionPoint(lhsName);
        SdxL2ConnectionPoint rhs = this.getSdxL2ConnectionPoint(rhsName);
        if (lhs == null || rhs == null) {
            return;
        }

        Set<String> cpsByVC = this.getSdxL2ConnectionPoints(Optional.of(splitKeyCPs[0]))
                .parallelStream()
                .filter(tempCP -> (tempCP.equals(lhs.name()) || tempCP.equals(rhs.name())))
                .collect(Collectors.toSet());

        if (cpsByVC.size() != 2) {
            return;
        }
        this.vcManager.removeVC(lhs, rhs);
    }

    @Override
    public SdxL2ConnectionPoint getSdxL2ConnectionPoint(String sdxl2cp) {
        checkNotNull(sdxl2cp, "SdxL2ConnectionPoint name cannot be null");
        try {
            return this.sdxL2Store.getSdxL2ConnectionPoint(sdxl2cp);
        } catch (SdxL2Exception e) {
            log.info(e.getMessage());
        }
        return null;
    }

    @Override
    public Set<String> getVirtualCircuits(Optional<String> sdxl2) {
        return this.vcManager.getVCs(sdxl2);
    }

    @Override
    public VirtualCircuit getVirtualCircuit(String sdxl2vc) {
        checkNotNull(sdxl2vc, "VC name cannot be null");
        String[] splitKeyCPs = sdxl2vc.split(":");
        checkState(splitKeyCPs.length == 2, "Bad name format $sdx:$something");
        String[] cps = splitKeyCPs[1].split("-");
        checkState(cps.length == 2, "Bad name format $sdx:$lhs-$rhs");

        SdxL2ConnectionPoint lhs = this.getSdxL2ConnectionPoint(cps[0]);
        SdxL2ConnectionPoint rhs = this.getSdxL2ConnectionPoint(cps[1]);
        VirtualCircuit vc = null;
        if (lhs == null || rhs == null) {
            return vc;
        }

        String result = this.vcManager.getVC(lhs, rhs);
        if (result != null) {
            vc = new VirtualCircuit(lhs, rhs);
        }
        return vc;
    }

    @Override
    public SdxL2State getIntentState(Key intentKey) {
        checkNotNull(intentKey, "Intent key cannot be null");
        return this.monitoringManager.getIntentState(intentKey);
    }

    @Override
    public SdxL2State getEdgePortState(ConnectPoint edgeport) {
        checkNotNull(edgeport, "Edge port cannot be null");
        return this.monitoringManager.getEdgePortState(edgeport);
    }

    /**
     * Cleans the state of the Application.
     */
    @Override
    public void cleanSdxL2() {
        this.monitoringManager.cleanup();
    }

    /**
     * Requests ARP and NDP packets to the PacketService
     * and registers the SDX-L2 PacketProcessor.
     */
    private void handleArpNdp() {
        SdxL2ArpNdpHandler.vcType = VC_0;
        arpndpHandler = new SdxL2ArpNdpHandler(intentService, packetService, appId);
        packetService.addProcessor(processor, PacketProcessor.director(1));

        // ARP packet
        TrafficSelector.Builder selectorBuilder =
                DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(TYPE_ARP);
        packetService.requestPackets(selectorBuilder.build(),
                                     CONTROL, appId, Optional.<DeviceId>empty());

        // IPv6 Neighbor Solicitation packet.
        selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(TYPE_IPV6);
        selectorBuilder.matchIPProtocol(PROTOCOL_ICMP6);
        selectorBuilder.matchIcmpv6Type(NEIGHBOR_SOLICITATION);
        packetService.requestPackets(selectorBuilder.build(),
                                     CONTROL, appId, Optional.<DeviceId>empty());

        // IPv6 Neighbor Advertisement packet.
        selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(TYPE_IPV6);
        selectorBuilder.matchIPProtocol(PROTOCOL_ICMP6);
        selectorBuilder.matchIcmpv6Type(NEIGHBOR_ADVERTISEMENT);
        packetService.requestPackets(selectorBuilder.build(),
                                     CONTROL, appId, Optional.<DeviceId>empty());
    }

    /**
     * Withdraws the requests for ARP/NDP packets and
     * unregisters the SDX-L2 PacketProcessor.
     */
    private void unhandleArpNdp() {
        arpndpHandler = null;
        packetService.removeProcessor(processor);
        processor = null;

        TrafficSelector.Builder selectorBuilder =
                DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(TYPE_ARP);
        packetService.cancelPackets(selectorBuilder.build(),
                                    CONTROL, appId, Optional.<DeviceId>empty());

        selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(TYPE_IPV6);
        selectorBuilder.matchIPProtocol(PROTOCOL_ICMP6);
        selectorBuilder.matchIcmpv6Type(NEIGHBOR_SOLICITATION);
        packetService.cancelPackets(selectorBuilder.build(),
                                    CONTROL, appId, Optional.<DeviceId>empty());

        selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(TYPE_IPV6);
        selectorBuilder.matchIPProtocol(PROTOCOL_ICMP6);
        selectorBuilder.matchIcmpv6Type(NEIGHBOR_ADVERTISEMENT);
        packetService.cancelPackets(selectorBuilder.build(),
                                    CONTROL, appId, Optional.<DeviceId>empty());
    }

    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
    private class SdxL2Processor implements PacketProcessor {

        /**
         * Processes the inbound packet as specified in the given context.
         *
         * @param context packet processing context
         */
        @Override
        public void process(PacketContext context) {

            /** Stop processing if the packet has been handled, since we
             * can't do any more to it
             */
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null) {
                return;
            }

            boolean handled = false;
            if (ethPkt.getEtherType() == TYPE_ARP) {
                //handle the arp packet.
                handled = arpndpHandler.handlePacket(context);
            } else if (ethPkt.getEtherType() == TYPE_IPV6) {
                IPv6 ipv6Pkt = (IPv6) ethPkt.getPayload();
                if (ipv6Pkt.getNextHeader() == IPv6.PROTOCOL_ICMP6) {
                    ICMP6 icmp6Pkt = (ICMP6) ipv6Pkt.getPayload();
                    if (icmp6Pkt.getIcmpType() == NEIGHBOR_SOLICITATION ||
                            icmp6Pkt.getIcmpType() == NEIGHBOR_ADVERTISEMENT) {
                        // handle ICMPv6 solicitations and advertisements
                        handled = arpndpHandler.handlePacket(context);
                    }
                }
            }

            if (handled) {
                context.block();
            }

        }
    }
}
