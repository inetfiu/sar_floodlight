package net.floodlightcontroller.diss;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionDataLayerSource;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.routing.RouteId;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;

import net.floodlightcontroller.util.MACAddress;

public class DISS implements IOFMessageListener, IFloodlightModule {
	protected static int uniqueFlow = 0;
	public static final String m = "00:00:00:00:00:";
    protected static Logger log = LoggerFactory.getLogger(DISS.class);
	protected IFloodlightProviderService floodlightProvider;
    protected IStaticFlowEntryPusherService sfpService;
    protected ITopologyService topologyService;
	protected ILinkDiscoveryService lds;
	protected IDeviceService deviceManagerService;
	
    //for link load computation
	protected Timer timer = new Timer();
	protected BandwidthUpdateTask but = new BandwidthUpdateTask();	
	protected Map<Long, IOFSwitch> switches;
	protected Map<Long, Map<Short, LinkBandwidthInfo>> switchesInfo= new HashMap<Long, Map<Short, LinkBandwidthInfo>>();
	
	

	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return DISS.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return (type.equals(OFType.PACKET_IN) && 
				(name.equals("topology") || name.equals("devicemanager")));
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// TODO Auto-generated method stub
	    Collection<Class<? extends IFloodlightService>> l =
		        new ArrayList<Class<? extends IFloodlightService>>();
	    l.add(IFloodlightProviderService.class);
		l.add(IDeviceService.class);
		l.add(ILinkDiscoveryService.class);
		l.add(IStaticFlowEntryPusherService.class);
		l.add(ITopologyService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
	    floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class); 
	    lds = context.getServiceImpl(ILinkDiscoveryService.class);
	    sfpService = context.getServiceImpl(IStaticFlowEntryPusherService.class);
	    topologyService = context.getServiceImpl(ITopologyService.class);
	    deviceManagerService = context.getServiceImpl(IDeviceService.class);
	    log = LoggerFactory.getLogger(DISS.class);

	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
        log.trace("Starting");
       //switches = floodlightProvider.getAllSwitchMap();

        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);      
        timer.schedule(but,0, 4000);//set to run every 5 seconds task t   

	}
	
	class LinkBandwidthInfo {
		long transmitBytes;
		double trafficIntensity;
		long lastCheckTime;
		
		LinkBandwidthInfo() {
			
			transmitBytes = 0;
			lastCheckTime = 0;
		}
	}
	
    class BandwidthUpdateTask extends TimerTask {
        public void run() {
        	bandwidthQuery();
        }
    }
    
    
    
    public void bandwidthQuery() {
	    switches = floodlightProvider.getAllSwitchMap();
	    
    	//Sending OFStatisticsRequest to all switches
    	//Map<Long, Future<List<OFStatistics>>> switchReplies = new HashMap<Long, Future<List<OFStatistics>>>();
    	
    	for(Entry<Long, IOFSwitch> se: switches.entrySet()){
			Map<Short, LinkBandwidthInfo> linksInfo = switchesInfo.get(se.getValue().getId());
   	    	if (linksInfo == null) {
   	    		linksInfo = new HashMap<Short, LinkBandwidthInfo>();
   	    		switchesInfo.put(se.getValue().getId(), linksInfo);
   	    	}
   	    		
    		for (ImmutablePort port: se.getValue().getPorts()) {
    			LinkBandwidthInfo linkInfo = linksInfo.get(port.getPortNumber());
    			if (linkInfo == null) {
    				linkInfo = new LinkBandwidthInfo();
	    			linksInfo.put(port.getPortNumber(), linkInfo);
    				linkInfo.trafficIntensity = 0.0;
    			}
    		}
   	    	
    		
    		//System.out.println( "Request sent to "+ se); 
    		OFStatisticsRequest sr = new OFStatisticsRequest();
    		sr.setStatisticType(OFStatisticsType.PORT);
    		
    		OFPortStatisticsRequest psr = new OFPortStatisticsRequest();
    		psr.setPortNumber(OFPort.OFPP_NONE.getValue());
    		
    		List<OFStatistics> rl = new ArrayList<OFStatistics>();
    		rl.add(psr);
    		sr.setStatistics(rl);
    		sr.setLengthU(sr.getLengthU() + psr.getLength());
    		
    		Future<List<OFStatistics>> future;
    		List<OFStatistics> statsList = null;
    		
    		try {

    			IOFSwitch sw = se.getValue();
    			future = sw.queryStatistics(sr);
    			statsList = future.get(10, TimeUnit.SECONDS);

    			//Map<Short, LinkBandwidthInfo> linksInfo = switchesInfo.get(sw.getId());
    	    	if(statsList != null && !statsList.isEmpty()){

	    	    	// Receive signal from switches
	    	    	for(OFStatistics stats: statsList) { //each port
	    	    		OFPortStatisticsReply portStats = (OFPortStatisticsReply)stats;
	    	    		short portNum = portStats.getPortNumber();
	    	    		if(portNum < 0) continue;
	    	    		LinkBandwidthInfo linkInfo = linksInfo.get(portNum);
	    	    		if (linkInfo == null) {
	    	    			linkInfo = new LinkBandwidthInfo();
	    	    			linksInfo.put(portNum, linkInfo);
	    	    		}
	    	    		
	    	    		long lastlastCheckTime = linkInfo.lastCheckTime;
	    	    		linkInfo.lastCheckTime = System.currentTimeMillis();
	    	    		long lastTransmitBytes = linkInfo.transmitBytes;
	 
	    	    		
	    	    		linkInfo.transmitBytes = portStats.getTransmitBytes();
	    	    		
	    	    		if (lastlastCheckTime != 0) { // not the first reply
		    	    		long interval = linkInfo.lastCheckTime - lastlastCheckTime;
		    	    		if (interval != 0) {
			    	    		long sentBytes = linkInfo.transmitBytes - lastTransmitBytes;
			    	    		//double alpha = 0.25; unit kbps
			    	    		linkInfo.trafficIntensity = ((sentBytes * 8.0)/1024.0)/ (interval / 1024.0);
			    	    		if (linkInfo.trafficIntensity > 10.0) {
				    	    		System.out.println("sw="+sw.getId()+",port="+portNum+",\t"+linkInfo.trafficIntensity);					    	    			
			    	    		}	

		    	    		}
	    	    		}
	    		    }
    	    	}   			
    		} catch (Exception e) {
    			log.error("Failure sending request", e);
    			e.printStackTrace();
    		}
    	}
    }
    
    
    
	
	//Handle ARP Request and reply it with destination MAC address

	private void handleARPRequest(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, String ip, String mac) {
		
		//logger.debug("Handle ARP request");
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
				IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		if (! (eth.getPayload() instanceof ARP))
			return;
		ARP arpRequest = (ARP) eth.getPayload();
	
//		System.out.println("ARP IP " + ip + " MAC " +mac);
		
		// generate ARP reply
		IPacket arpReply = new Ethernet()
			.setSourceMACAddress(mac)
			.setDestinationMACAddress(eth.getSourceMACAddress())
			.setEtherType(Ethernet.TYPE_ARP)
			.setPriorityCode(eth.getPriorityCode())
			.setPayload(
				new ARP()
				.setHardwareType(ARP.HW_TYPE_ETHERNET)
				.setProtocolType(ARP.PROTO_TYPE_IP)
				.setHardwareAddressLength((byte) 6)
				.setProtocolAddressLength((byte) 4)
				.setOpCode(ARP.OP_REPLY)
				.setSenderHardwareAddress(MACAddress.valueOf(mac).toBytes())
				.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(ip))
				.setTargetHardwareAddress(arpRequest.getSenderHardwareAddress())
				.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress()));
		
		sendARPReply(arpReply, sw, OFPort.OFPP_NONE.getValue(), pi.getInPort());
	}
	
	
	// Sends ARP reply out to the switch
	private void sendARPReply(IPacket packet, IOFSwitch sw, short inPort, short outPort) {
		
		
		// Initialize a packet out
		OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory()
				.getMessage(OFType.PACKET_OUT);
		po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
		po.setInPort(inPort);
		
		// Set output actions
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(new OFActionOutput(outPort, (short) 0xffff));
		po.setActions(actions);
		po.setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);
		
		// Set packet data and length
		byte[] packetData = packet.serialize();
		po.setPacketData(packetData);
		po.setLength((short) (OFPacketOut.MINIMUM_LENGTH + po.getActionsLength() + packetData.length));
		
		// Send packet
		try {
			sw.write(po, null);
			sw.flush();
		} catch (IOException e) {
			// logger.error("Failure writing packet out", e);
		}
	}
	

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext context) {
		// TODO Auto-generated method stub
    	switch (msg.getType()) {
    	case PACKET_IN:
            return processPacketIn(sw, (OFPacketIn)msg, context);
        default:
            break;    	
    	}

        log.warn("Received unexpected message {}", msg);
        //won't call the back modules such as "forwarding" module, specified in isCallbackorderpostreq
		return Command.STOP;   	
	}
	
    private net.floodlightcontroller.core.IListener.Command processPacketIn(IOFSwitch sw, OFPacketIn pi, FloodlightContext context) {
        Ethernet eth = IFloodlightProviderService.bcStore.get(context, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
        IPacket pkt = eth.getPayload(); 
	    OFMatch match = new OFMatch();		
	    match.loadFromPacket(pi.getPacketData(), pi.getInPort());
	    
	    

        if (pkt instanceof ARP) {
        	
        	String dHostIp = IPv4.fromIPv4Address(match.getNetworkDestination());
        	String [] str = dHostIp.split("\\.");
        	String temp = "";
        	if (str[3].length() == 1) {
        		temp = "0"+str[3];
        	} else {
        		temp = str[3];
        	}
        	String dHostMac = m+temp;
//        	System.out.println("ARP dstMAC "+dHostMac);
        	handleARPRequest(sw, pi, context, dHostIp, dHostMac);
        	
        	return Command.CONTINUE;
        } else if (pkt instanceof IPv4) {
        	String dHostIp = IPv4.fromIPv4Address(match.getNetworkDestination());
        	String [] str = dHostIp.split("\\.");
        	long dstsw = Long.parseLong(str[2]);
        	short dstport = 1;  //fixed
        	
        	SwitchPort[] srcAP = 
        			IDeviceService.fcStore.get(context, IDeviceService.CONTEXT_SRC_DEVICE).getAttachmentPoints();
            NodePortTuple head = new NodePortTuple(srcAP[0].getSwitchDPID(), srcAP[0].getPort());
            NodePortTuple tail = new NodePortTuple(dstsw,dstport);
            
            //compute shortest path
        	List<NodePortTuple> path = new ArrayList<> ();
        	path = shortestRoute(head.getNodeId(),tail.getNodeId());
        	path.add(0, head);
        	path.add(tail);  
        	
        	if(((IPv4) pkt).getProtocol() == 0x1) {
        		//if protocol is ICMP (ping), routing path without going through mb
        		placeRouting(path, match);
        		return Command.CONTINUE;
        		
        		
        	} else if (((IPv4) pkt).getProtocol() == 0x11) {
        		//if protocol is UDP (traffic),  routing through mb, if use pcap_mb also need to
        		//reset the source mac address
            	//create OFMatch src to dst and dst to src
            	OFMatch stod = new OFMatch();
            	OFMatch dtos= new OFMatch();
            	stod.setNetworkProtocol(IPv4.PROTOCOL_UDP).setDataLayerType(Ethernet.TYPE_IPv4).setNetworkSource(match.getNetworkSource())
        				 .setNetworkDestination(match.getNetworkDestination());

            	dtos.setDataLayerType(Ethernet.TYPE_IPv4).setNetworkSource(match.getNetworkDestination())
        				 .setNetworkDestination(match.getNetworkSource());
        		firstPlace(path, stod, dtos, eth);
        		
        	}
        	
        } else {
        	return Command.CONTINUE;
        }
       return Command.CONTINUE;    	
    }
    
    
    protected class NodeDist implements Comparable<NodeDist> {
        private final Long node;
        private final int dist;
        public Long getNode() {
            return node;
        }
       
        public int getDist() {
            return dist;
        }

        public NodeDist(Long node, int dist) {
            this.node = node;
            this.dist = dist;
        }

        @Override
        public int compareTo(NodeDist o) {
            if (o.dist == this.dist) {
                return (int)(this.node - o.node);
            }
            return this.dist - o.dist;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            NodeDist other = (NodeDist) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            if (node == null) {
                if (other.node != null)
                    return false;
            } else if (!node.equals(other.node))
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            assert false : "hashCode not designed";
            return 42;
        }

        private DISS getOuterType() {
            return DISS.this;
        }
    }
    
    
    
    List<NodePortTuple> shortestRoute(long srcSwitchId, long dstSwitchId) {
    	switches = floodlightProvider.getAllSwitchMap();
		//assert(switches != null);
		
    	//used to trace the flow path
		HashMap<Long, Link> nexthoplinks = new HashMap<Long, Link>();
        HashMap<Long, Integer> cost = new HashMap<Long, Integer>();
     
        int w;

        for (Long node: switches.keySet()) {
            nexthoplinks.put(node, null);
            //nexthopnodes.put(node, null);
            cost.put(node, Integer.MAX_VALUE);
        }

        HashMap<Long, Boolean> seen = new HashMap<Long, Boolean>();
        PriorityQueue<NodeDist> nodeq = new PriorityQueue<NodeDist>();
        nodeq.add(new NodeDist(srcSwitchId, 0));
        cost.put(srcSwitchId, 0);
        

        while (nodeq.peek() != null) {
        	
            NodeDist n = nodeq.poll();
            Long cnode = n.getNode();
            int cdist = n.getDist();
            if (cdist >= Integer.MAX_VALUE) break;
            if (seen.containsKey(cnode)) continue;
            seen.put(cnode, true);

           
    		for (Link link : lds.getSwitchLinks().get(cnode)) {
    			//if (link.getDst() != cnode) 
    				Long neighbor = link.getDst(); // skip links with cnode as dst
    				
    			
    			// links directed toward cnode will result in this condition
    			if (neighbor.equals(cnode))
    				continue;

    			if (seen.containsKey(neighbor))
    				continue;

    			w = 1;
                
                int ndist = cdist + w; // the weight of the link, always 1 in current version of floodlight.
                if (ndist < cost.get(neighbor)) {
                    cost.put(neighbor, ndist);
                    nexthoplinks.put(neighbor, link);
                    //nexthopnodes.put(neighbor, cnode);
                    nodeq.remove(new NodeDist(neighbor, cost.get(neighbor)));
                    NodeDist ndTemp = new NodeDist(neighbor, ndist);
                    // Remove an object that's already in there.
                    // Note that the comparison is based on only the node id,
                    // and not node id and distance.
                    nodeq.remove(ndTemp);
                    // add the current object to the queue.
                    nodeq.add(ndTemp);
                }
                
            }            
    }
        
    	LinkedList<NodePortTuple> switchPorts = new LinkedList<NodePortTuple>();
    	
        //dstSwitchId will be changed in the following loop, use odstSwitchId to store the original data  
    	Long odstSwitchId = dstSwitchId;
    	if ((nexthoplinks!=null) && (nexthoplinks.get(dstSwitchId)!=null)) {
            while (dstSwitchId!= srcSwitchId ) {
                Link l = nexthoplinks.get(dstSwitchId);
                switchPorts.addFirst(new NodePortTuple(l.getDst(), l.getDstPort()));
                switchPorts.addFirst(new NodePortTuple(l.getSrc(), l.getSrcPort()));
                dstSwitchId = nexthoplinks.get(dstSwitchId).getSrc();
            }
        }
    	
    	Route result = null;
        if (switchPorts != null && !switchPorts.isEmpty())
            result = new Route( new RouteId(srcSwitchId, odstSwitchId), switchPorts);
        List<NodePortTuple> path = new ArrayList<> ();
        path = result.getPath();
       // System.out.println("Route: s="+srcSwitchId+",d="+dstSwitchId+" r="+ result);
        return path;
    }
    
    //generate and place routing rules
    private void placeRouting(List<NodePortTuple> temp, OFMatch match){
    	OFMatch stoTMatch = new OFMatch();
    	OFMatch dtoSMatch = new OFMatch();
    	
    	stoTMatch.setNetworkProtocol(IPv4.PROTOCOL_ICMP).setDataLayerType(Ethernet.TYPE_IPv4).setNetworkSource(match.getNetworkSource())
				 .setNetworkDestination(match.getNetworkDestination());

    	dtoSMatch.setNetworkProtocol(IPv4.PROTOCOL_ICMP).setDataLayerType(Ethernet.TYPE_IPv4).setNetworkSource(match.getNetworkDestination())
				 .setNetworkDestination(match.getNetworkSource());
	
    	//System.out.println("?????The size of path is "+pathNode.size());
    	for(int indx = 0; indx<=temp.size()-1; indx+=2){
    		//define actions (output port)
    		short inport = temp.get(indx).getPortId();
    		stoTMatch.setInputPort(inport);
			List<OFAction> actions = new ArrayList<OFAction>();
			OFActionOutput outport = new OFActionOutput(temp.get(indx+1).getPortId()); //set the output port based on the path info
			actions.add(outport);
			
			OFFlowMod stoDst = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
			stoDst.setCommand(OFFlowMod.OFPFC_ADD)
						.setIdleTimeout((short) 0)
						.setHardTimeout((short) 0) //infinite
						.setMatch(stoTMatch)   // (srcIP,dstIp) with mask
						.setPriority((short) 200)
						.setActions(actions)
						.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
			sfpService.addFlow("routeFlow"+uniqueFlow, stoDst, HexString.toHexString(temp.get(indx).getNodeId()));
			uniqueFlow++;	
    	}
    	
    	for(int indx = temp.size()-1; indx > 0; indx-=2){
    		//define actions (output port)
    		short inport = temp.get(indx).getPortId();
    		dtoSMatch.setInputPort(inport);
    		
			List<OFAction> actions = new ArrayList<OFAction>();
			OFActionOutput outport = new OFActionOutput(temp.get(indx-1).getPortId()); //set the output port based on the path info
			actions.add(outport);
			
			OFFlowMod dtoSrc = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
			dtoSrc.setCommand(OFFlowMod.OFPFC_ADD)
						.setIdleTimeout((short) 0)
						.setHardTimeout((short) 0) //infinite
						.setMatch(dtoSMatch)   // (srcIP,dstIp) with mask
						.setPriority((short) 200)
						.setActions(actions)
						.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
			sfpService.addFlow("routeFlow"+uniqueFlow, dtoSrc, HexString.toHexString(temp.get(indx).getNodeId()));
			uniqueFlow++;	
    	}  	
    }
    
    
    //used for tosr and the heuristic to place routing rules and mbs
    public void firstPlace(List<NodePortTuple> path, OFMatch stod , OFMatch dtos, 
    		Ethernet eth) {
    	// put all switches on the path in order
    	
    	ArrayList<Long> sws = new ArrayList<> ();
    	for (int i = 0;  i <= path.size()-2 ; i += 2) {    		
    		sws.add(path.get(i).getNodeId());
    		System.out.print(path.get(i).getNodeId() + " ");
    	}
    	
    	//record if the sw has mb rules to install, init to be false
    	Map<Long, Boolean> flag = new HashMap<> ();
    	for (int i = 0; i < sws.size(); i++) {
    		if (i == 0)
    			flag.put(sws.get(i), true);  //mb connects to the 1st switch
    		else
    			flag.put(sws.get(i),false);
    	}
    	
    	ArrayList<Double> f = new ArrayList<> ();
    	
    	//System.out.println("111sw factors: "+sw_factor);
    	for (int i = 0; i< path.size(); i += 2){
    		long swId = path.get(i).getNodeId();
			short inport = path.get(i).getPortId();
			short outport = path.get(i+1).getPortId();
			
    		if (flag.get(swId)) {

				List<Short> ports = new ArrayList<> (); 
				ports.add((short) 2);

				//System.out.println("Ports: "+ ports+" swid "+swId);
				ports.add(0, inport);
				ports.add(outport);
/*				
				for(short port : ports) {
					System.out.println("port_num: "+port);
				}*/
				
				for (int k = 0; k < ports.size()-1; k++) {
					short in = ports.get(k);
					short out = ports.get(k+1);
					stod.setInputPort(in);
					if (out == ports.get(ports.size()-1)){
						
    	    			List<OFAction> actions = new ArrayList<> ();
    	                OFActionDataLayerSource setsrc = new OFActionDataLayerSource();
    	        		setsrc.setDataLayerAddress(eth.getSourceMACAddress());
    	        		actions.add(setsrc);
    	    			OFActionOutput output = new OFActionOutput(out);
    	    			actions.add(output);
    	    			
    	    			OFFlowMod sw_flow = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
    	    			sw_flow.setCommand(OFFlowMod.OFPFC_ADD)
    	    			.setIdleTimeout((short) 0)
    	    			.setHardTimeout((short) 0)
    	    			.setMatch(stod)
    	    			.setPriority((short) 200)
    	    			.setActions(actions)
    	    			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH + OFActionDataLayerSource.MINIMUM_LENGTH ));
    	    		    sfpService.addFlow("route" + uniqueFlow, sw_flow, HexString.toHexString(swId));  
    	    		    uniqueFlow ++;   	
						
						
					} else {
		    			List<OFAction> actions = new ArrayList<> ();
		    			OFActionOutput output = new OFActionOutput(out);
		    			actions.add(output);
		    			
		    			OFFlowMod sw_flow = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
		    			sw_flow.setCommand(OFFlowMod.OFPFC_ADD)
		    			.setIdleTimeout((short) 0)
		    			.setHardTimeout((short) 0)
		    			.setMatch(stod)
		    			.setPriority((short) 200)
		    			.setActions(actions)
		    			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
		    		    sfpService.addFlow("route" + uniqueFlow, sw_flow, HexString.toHexString(swId));  
		    		    uniqueFlow ++;    							
					}					
				}      			
    		} else {
    			//if the sw has no mb to install		
    			stod.setInputPort(inport);
    			List<OFAction> actions = new ArrayList<> ();
    			OFActionOutput output = new OFActionOutput(outport);
    			actions.add(output);
    			
    			OFFlowMod sw_flow = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
    			sw_flow.setCommand(OFFlowMod.OFPFC_ADD)
    			.setIdleTimeout((short) 0)
    			.setHardTimeout((short) 0)
    			.setMatch(stod)
    			.setPriority((short) 200)
    			.setActions(actions)
    			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
    		    sfpService.addFlow("route" + uniqueFlow, sw_flow, HexString.toHexString(path.get(i).getNodeId()));  
    		    uniqueFlow ++;    			
    		}
    		//place routing rules in reverse direction
        	dtos.setInputPort(outport);
			List<OFAction> actions = new ArrayList<> ();
			OFActionOutput output = new OFActionOutput(inport);
			actions.add(output);
			
			OFFlowMod sw_flow = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
			sw_flow.setCommand(OFFlowMod.OFPFC_ADD)
			.setIdleTimeout((short) 0)
			.setHardTimeout((short) 0)
			.setMatch(dtos)
			.setPriority((short) 200)
			.setActions(actions)
			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
		    sfpService.addFlow("route" + uniqueFlow, sw_flow, HexString.toHexString(path.get(i).getNodeId()));  
		    uniqueFlow ++;  
    	}
    }


}