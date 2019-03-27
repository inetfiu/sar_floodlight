package net.floodlightcontroller.setroute;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.io.*;
import java.util.ArrayList;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.MacVlanPair;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.learningswitch.LearningSwitch;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.ruleplacer.PreMod;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;

import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import net.floodlightcontroller.packet.Ethernet;

import org.openflow.util.HexString;
import org.openflow.util.U8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SetRoute implements IOFMessageListener, IFloodlightModule {
	
	protected IFloodlightProviderService flp;
	protected IStaticFlowEntryPusherService flowPusher;
	protected IDeviceService netDevices;
	protected ILinkDiscoveryService links;
	protected ICounterStoreService cStore;
	protected static Logger logger;
	protected int nameChanger = 0;
	protected int xidChanger = 0;
	protected long[] lastUpdate = new long[5];
	protected long lastStats = 0;
	protected Map<Long, PolicyLists> hostFlows; //Map of Switches to the hosts they have rules for and their set of routing rules
	protected Map<String, Long> hosts;
	

	@Override
	public String getName() {
		return SetRoute.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
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
		return null;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		flp = context.getServiceImpl(IFloodlightProviderService.class);
		flowPusher = context.getServiceImpl(IStaticFlowEntryPusherService.class);
		netDevices = context.getServiceImpl(IDeviceService.class);
		links = context.getServiceImpl(ILinkDiscoveryService.class);
		cStore = context.getServiceImpl(ICounterStoreService.class);
	    logger = LoggerFactory.getLogger(SetRoute.class);
	    hostFlows = new TreeMap<Long, PolicyLists>();
	    hosts = new TreeMap<String, Long>();
	    hosts.put("10.0.0.1", (long) 1);
	    hosts.put("10.0.0.2", (long) 2);
	    hosts.put("10.0.0.5", (long) 3);
	    hosts.put("10.0.0.6", (long) 4);
	    hosts.put("10.0.0.9", (long) 5);
	    hosts.put("10.0.0.10", (long) 6);
	    hosts.put("10.0.0.13", (long) 7);
	    hosts.put("10.0.0.14", (long) 8);	    
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		flp.addOFMessageListener(OFType.PACKET_IN, this);
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		/*Long dPID = sw.getId();
		if(!hostFlows.keySet().contains(dPID)){
			hostFlows.put(dPID, new PolicyLists());
		}
		PolicyLists swLists = hostFlows.get(dPID);
		OFPacketIn oFPI = (OFPacketIn) msg;
		OFMatch match = new OFMatch();
		match.loadFromPacket(oFPI.getPacketData(), oFPI.getInPort());
		if(match.getNetworkDestination() != 0){
			int destIP = match.getNetworkDestination();
			int srcIP = match.getNetworkSource();
			long destMAC = hosts.get(ipToString(destIP));
			long srcMAC = hosts.get(ipToString(srcIP));
			if(!swLists.getSeenHosts().contains(srcMAC)){
				swLists.addHost(srcMAC);
				swLists.addRule(srcIP, oFPI.getInPort());
			}
			if(!swLists.getSeenHosts().contains(destMAC)){
			}
		}*/
		
        return Command.CONTINUE;
	}
	
	private ArrayList<Short> switchLinkPorts(long dpid){
		ArrayList<Short> sLPorts = new ArrayList<Short>();
		Iterator<Link> switchLinks = links.getSwitchLinks().get(dpid).iterator();
		while(switchLinks.hasNext()){
			Link currLink = switchLinks.next();
			if(currLink.getSrc() == dpid) sLPorts.add(currLink.getSrcPort());
		}
		return sLPorts;
	}
	
	private Map<Long, Short> dstSwLinks(long dpid){
		Map<Long, Short> linksToSwitch = new HashMap<Long, Short>();
		Iterator<Link> switchLinks = links.getSwitchLinks().get(dpid).iterator();
		while(switchLinks.hasNext()){
			Link currLink = switchLinks.next();
			if(currLink.getDst() == dpid) linksToSwitch.put(currLink.getSrc(), currLink.getSrcPort());
		}
		return linksToSwitch;
	}
	
	private SwitchPort hostOnSwitch(long mac){
		Iterator<? extends IDevice> hosts = netDevices.getAllDevices().iterator();
		while(hosts.hasNext()){
			IDevice currHost = hosts.next();
        	if(currHost.getMACAddress() == mac){
        		//System.out.println("Made It!");
        		return currHost.getAttachmentPoints()[0];
        	}
        }
		return null;
	}
	
	private OFMatch l3Match(OFMatch data){
		data.setDataLayerType((short) (data.getDataLayerType() - 6));
		data.setNetworkProtocol((byte) 0);
		data.setTransportDestination((short) 0);
		data.setTransportSource((short) 0);
		data.setDataLayerDestination(Ethernet.toByteArray(0));
		return data;
	}
	
	private OFMatch l2Match(OFMatch data){
		data.setDataLayerType((short) (0));
		data.setNetworkProtocol((byte) 0);
		data.setTransportDestination((short) 0);
		data.setTransportSource((short) 0);
		return data;
	}
	
	private void launchOFFMod(OFMatch data, Short outPort, IOFSwitch sw){	
		System.out.println(data.toStringUnmasked());
		OFActionOutput flowPath = new OFActionOutput(outPort, (short) -1);
		OFFlowMod staticRouteFlow = (OFFlowMod) flp.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
		staticRouteFlow.setCommand(OFFlowMod.OFPFC_ADD)
			.setIdleTimeout((short) 600)
			.setHardTimeout((short) 600)
			.setMatch(data)
			.setPriority((short) 101)
			.setActions(Collections.singletonList((OFAction)flowPath))
			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
		//flowPusher.deleteFlowsForSwitch(sw.getId());
		flowPusher.addFlow("myFlow"+nameChanger, staticRouteFlow, sw.getStringId());
		nameChanger++;
	}
	
	protected static String ipToString(int ip) {
        return Integer.toString(U8.f((byte) ((ip & 0xff000000) >> 24)))
               + "." + Integer.toString((ip & 0x00ff0000) >> 16) + "."
               + Integer.toString((ip & 0x0000ff00) >> 8) + "."
               + Integer.toString(ip & 0x000000ff);
    }
	
	//Helper method that converts a string IP address into the integer equivalent
		private int ipv4ToInt(String ip){
			StringTokenizer st = new StringTokenizer(ip, ".");
			int intIP = 0;
			int i = 24;
			while(st.hasMoreTokens()){
				Integer octetVal = Integer.parseInt(st.nextToken());
				Double ipVal = octetVal * Math.pow(2, i);
				intIP += ipVal.intValue();
				i -= 8;
			}
			return intIP;
		}
}