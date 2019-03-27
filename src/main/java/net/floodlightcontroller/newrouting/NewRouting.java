package net.floodlightcontroller.newrouting;
 
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.Map.Entry;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Collection;



import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.routing.RouteId;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;

import net.floodlightcontroller.util.MACAddress;



//import net.floodlightcontroller.util.MACAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;


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
import org.openflow.util.U8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewRouting implements IOFMessageListener, IFloodlightModule {
	public static final String m = "00:00:00:00:00:";
	public static int fail = 0;
	public static final int SPACE = 2;   //total number of rule space of each switch, changed accordingly
	//public static final int NUM_SW = 6; //the total number of switches, change based on topology
	public static final double LINK_CAPACITY = 10000.0; //Link capacity, change based on test(unit: kbps)
	public static Set<Integer> uniqueflows = new HashSet<> ();

	public static final int MAX_LINK_WEIGHT = 9000; //used for shortest path
    public static final int MAX_PATH_WEIGHT = Integer.MAX_VALUE - MAX_LINK_WEIGHT - 1;
    
	protected static Map<Integer, Map<String, String>> fid_ip = new HashMap<> (); // todo: add port for 
	protected static Map<Integer, ArrayList<Double>> fid_factors = new HashMap<> ();  //each flow has a chain of middleboxes
	//each flow has ingress and egress traffic rate
	protected static Map<Integer,Map<Double,Double>> fid_tran = new HashMap<> (); 
	protected static int uniqueFlow = 0;
	protected static BufferedReader reader;
	protected static Map<Long, Integer> sw_space = new HashMap<>(); //each swId--rule space
	static
    {
        
        sw_space.put((long) 1, SPACE);
        sw_space.put((long) 2, SPACE);
        sw_space.put((long) 3, SPACE);
        sw_space.put((long) 4, SPACE);
        sw_space.put((long) 5, SPACE);
        sw_space.put((long) 6, SPACE);
    }
	
	protected static long[] p_one = {3, 1, 2, 6};
	protected static long[] p_two = {3, 5, 1, 6};
	protected static long[] p_three = {4, 1, 5};
	protected static long[] p_four = {4, 6, 2, 5};

    protected static Logger log = LoggerFactory.getLogger(NewRouting.class);
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
	
	
    protected class MaxLinkLoad implements Comparable<MaxLinkLoad> {
        private final Long node;
        private final double mll;
        
        public Long getNode() {
            return node;
        }

        public double getMll() {
            return mll;
        }

        public MaxLinkLoad(Long node, double mll) {
            this.node = node;
            this.mll = mll;
        }

        @Override
        public int compareTo(MaxLinkLoad o) {

            return Double.compare(this.mll, o.mll);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            MaxLinkLoad other = (MaxLinkLoad) obj;
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

        private NewRouting getOuterType() {
            return NewRouting.this;
        }
    }
	

	@Override
	public String getName() {
		return NewRouting.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		//devicemanager is called before NewRouting
		return (type.equals(OFType.PACKET_IN) && (name.equals("topology") || name.equals("devicemanager")));
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return type.equals(OFType.PACKET_IN) && name.equals("forwarding");
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
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
	    floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class); 
	    lds = context.getServiceImpl(ILinkDiscoveryService.class);
	    sfpService = context.getServiceImpl(IStaticFlowEntryPusherService.class);
	    topologyService = context.getServiceImpl(ITopologyService.class);
	    deviceManagerService = context.getServiceImpl(IDeviceService.class);
	    log = LoggerFactory.getLogger(NewRouting.class);
	    

	    
		//read flowId and sorted list of mb's factors into Map<Integer, ArrayList<Double>> flow_factors
		try {
			reader = new BufferedReader(new FileReader("/home/wma006/Desktop/test_topo2/graph2_input/fid_factors.txt"));
	        String line;
	        while((line = reader.readLine()) != null && !line.isEmpty())
	        {
	        	String[] tokens = line.split(" ");
	            Integer i;
	            ArrayList<Double> list = new ArrayList<>();
	            if(tokens[0].length() > 0) {
		            i = Integer.valueOf(tokens[0].trim());

		            for(int j = 1; j < tokens.length; j++)
		            	list.add(Double.valueOf(tokens[j]));

		            fid_factors.put(i, list);
	            	
	            }
	            System.out.println("fid_factors: " + fid_factors);
	        }
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			if (reader != null)
				try {
					reader.close();
				} catch (IOException ex) {
					ex.printStackTrace();
				}
		}
		
		
		//Init fid_tran
		try {
			reader = new BufferedReader(new FileReader("/home/wma006/Desktop/test_topo2/graph2_input/fid_ip.txt"));
		    String line;
		    while((line = reader.readLine()) != null && !line.isEmpty())
		    {
		    	String[] tokens = line.split(" ");
		        Integer i;
		        String src;
		        String dst;
		        Map<String, String> ipPair = new HashMap<>();
		        
		        i = Integer.valueOf(tokens[0]);
		        src = tokens[1];
		        dst = tokens[2];
		        
		        ipPair.put(src, dst);
		        fid_ip.put(i, ipPair);
		        	            	
		    }
		    System.out.println("fid_ip: " + fid_ip);
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			if (reader != null)
				try {
					reader.close();
				} catch (IOException ex) {
					ex.printStackTrace();
				}
		}			
			
		
		//Init fid_tran, reduce the computation work of controller. (t_f = t_0 * k1*k2...)		
		try {
			reader = new BufferedReader(new FileReader("/home/wma006/Desktop/test_topo2/graph2_input/fid_tran.txt"));
	        String line;
	        while((line = reader.readLine()) != null && !line.isEmpty())
	        {
	        	String[] tokens = line.split(" ");
	            Integer i;
	            Double inTran;
	            Double eTran;
	            Map<Double, Double> tran = new HashMap<>();
          
	            i = Integer.valueOf(tokens[0]);
	            inTran = Double.valueOf(tokens[1]);
	            eTran = Double.valueOf(tokens[2]);
	            
	            tran.put(inTran, eTran);
	            fid_tran.put(i, tran);
	           

	        }
	        System.out.println("fid_tran: " + fid_tran);
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			if (reader != null)
				try {
					reader.close();
				} catch (IOException ex) {
					ex.printStackTrace();
				}
		}
    
	}
	
	
	@Override
	public void startUp(FloodlightModuleContext context) {
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
	
	    	    		if(portNum==1 || portNum==2)
	    	    			continue;
	    	    		
	    	    		if(sw.getId() == 5 || sw.getId() == 6) {
	    	    			if(portNum==3 || portNum==4)
		    	    			continue;
	    	    		}
	    	    			
	    	    	
	    	    			
	    	    		
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
    
    public Long getSwDpid(int is){  
    	String s =  ipToString(is);
		StringTokenizer st = new StringTokenizer(s,".");
		String des = new String();
		ArrayList<String> a = new ArrayList<String>();
			while(st.hasMoreElements()){
			des = (String) st.nextToken();
			a.add(des);
			}
    	return Long.valueOf(a.get(2));
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
	
    public List<Short> getPorts(ArrayList<Double> swf_list) {
    	List<Short> ports = new ArrayList<> ();
		for (double temp : swf_list) {
			if (temp == -0.2) {
				ports.add((short) 1);	
				continue;				
			}
								
			if (temp == 0.2) {
				ports.add((short) 2);	
				continue;				
			}
		}    	
    	return ports;
    }
	
    //get link from src to next neighbor (direct)
    public List<Long> getNextNei(Long src) {
    	Map<Long, Set<Link>> sw_links = lds.getSwitchLinks();
    	List<Long> nei = new ArrayList<> ();
    	Set<Link> sw_link = sw_links.get(src);
    	for(Link link : sw_link) {
    		if (link.getSrc() == src) {
    			nei.add(link.getDst());
    		}
    	}    	
    	return nei;    	
    }
    
    //get link from dst to previous neighbor (reverse)
    public List<Long> getPreNei(Long dst) {
    	Map<Long, Set<Link>> sw_links = lds.getSwitchLinks();
    	List<Long> nei = new ArrayList<> ();
    	Set<Link> sw_link = sw_links.get(dst);
    	for(Link link : sw_link) {
    		if (link.getDst() == dst) {
    			nei.add(link.getSrc());
    		}
    	}    	
    	return nei;    	
    }
    
    public int getflowid(IPv4 pkt) {
        /*get flow Id based on src_host ip and dst_host ip*/            	
    	String srcIP = IPv4.fromIPv4Address((pkt).getSourceAddress());  //src_host ip
    	String dstIP = IPv4.fromIPv4Address((pkt).getDestinationAddress());  //dst_host ip
    	Map<String, String> ip_pair = new HashMap<> ();  //src_host, dst_host ip pair
    //	System.out.println("srcIp "+srcIP+" dstIP "+dstIP);
    	
    	ip_pair.put(srcIP, dstIP);
    	Integer flow_id = null;
    	
    	Iterator<Map.Entry<Integer, Map<String, String>>> iter = fid_ip.entrySet().iterator();
    	while (iter.hasNext()) {
    		Map.Entry<Integer, Map<String, String>> entry = iter.next();
    		if (entry.getValue().equals(ip_pair)) {
    			flow_id = entry.getKey();        			
    		}
    	}  
    	if (flow_id == null) {
    		return 0;
    	} else 
    	//	System.out.println("flow id "+flow_id);
    		return flow_id;
    }
    
    class Traffic {
    	Double t_s;  //init traffic rate
    	Double t_d;  //predicated traffic rate when leaves the network
		public Traffic(Double t_s, Double t_d) {
			this.t_s = t_s;
			this.t_d = t_d;
		}
		public Double getT_s() {
			return t_s;
		}
		public Double getT_d() {
			return t_d;
		}
    }
    
    public Traffic getInittran (int flow_id) {    	
    	Traffic tran = null;
    	Iterator<Map.Entry<Integer, Map<Double, Double>>> iter1 = fid_tran.entrySet().iterator();
    	while (iter1.hasNext()) {
    		Map.Entry<Integer, Map<Double, Double>> entry = iter1.next();
    		if (entry.getKey().equals(flow_id)) {
    			Map.Entry<Double, Double> tranEntry = entry.getValue().entrySet().iterator().next();
    			tran = new Traffic(tranEntry.getKey(),tranEntry.getValue());
    		}         		
    	}    	
    	return tran;
    }
    
    //write a stand alone function for easy-reading
    public List<NodePortTuple> lfgl(IOFSwitch sw, FloodlightContext context, IPv4 pkt, Long srcSwId, Long dstSwId) {
    	//copy sw_space map into tsw_space avoid modify sw_space, due to sometimes the path computation fails
    	Map<Long, Integer> tsw_space = new HashMap<> (sw_space);
    	Set<Long> sawR = new HashSet<>(); 
    	Set<Long> saw = new HashSet<>();
    	Set<Long> jun = new HashSet<>();
    	
        int flow_id = getflowid(pkt);
    	
    	//get list of factors(sorted) of middlboxes belong to flow f and traffic rate
    	ArrayList<Double> factor_arr = fid_factors.get(flow_id); 
		double t_s0 = getInittran (flow_id).getT_s(); // the traffic rate when the flow enters srcSw
		double t_d0 = getInittran (flow_id).getT_d(); // the traffic rate when the flow enters dstSw
    	System.out.println("flowid:" +flow_id+" t_s0 " + t_s0 + " t_d0 " + t_d0);

        // get all sws of the topology 
		Set<Long> sws = floodlightProvider.getAllSwitchDpids();
		
		// swid--middlebox index 
		Map<Long, Integer> indexR = new HashMap<>();
		// swId----post swId
		Map<Long, Long> post = new HashMap<>();
		// record each node's maximum link load from node until the destination
		Map<Long, Double> mllR = new HashMap<>(); 
		Map<Long, Double> tranR = new HashMap<>(); // record the traffic rate when the flow enters the node
		PriorityQueue<MaxLinkLoad> m_queR = new PriorityQueue<>(); // poll node with min_mll

		/* init steps for dst sw firstly */
		int arr_size = factor_arr.size();
		tranR.put((long) -1, t_d0);
		indexR.put((long) -1, arr_size);
		post.put(dstSwId, (long) -1); // undefined

		// indexR.put(dstSwId,factor_arr.size() ); // undefined ???????
		mllR.put(dstSwId, Double.valueOf(0)); // mll(src) = 0
		m_queR.add(new MaxLinkLoad(dstSwId, Double.valueOf(0))); // dst is the first

		

		// swid--middlebox index 
		Map<Long, Integer> index = new HashMap<>();
		// swId----post swId
		Map<Long, Long> pre = new HashMap<>();
		// record each node's maximum link load from node until the destination
		Map<Long, Double> mll = new HashMap<>(); 
		Map<Long, Double> tran = new HashMap<>(); // record the traffic rate when the flow enters the node
		PriorityQueue<MaxLinkLoad> m_que = new PriorityQueue<>(); // poll node with min_mll

		tran.put((long) -1, t_s0);
		index.put((long) -1, -1);
		pre.put(srcSwId, (long) -1); // undefined

		// indexR.put(dstSwId,factor_arr.size() ); // undefined ???????
		mll.put(srcSwId, Double.valueOf(0)); // mll(src) = 0
		m_que.add(new MaxLinkLoad(srcSwId, Double.valueOf(0))); // dst is the first
		
		for(long node : sws) {
			if (node != srcSwId)
				mll.put(node, Double.MAX_VALUE);
			if (node != dstSwId)
				mllR.put(node, Double.MAX_VALUE);
		}

    	//all mb factors are positive, return a path. exit the function
		if (factor_arr.get(0) > 0) {

			while (!m_queR.isEmpty()) {
				Long node = null;
				if (m_queR.peek() != null) {
					node = m_queR.poll().getNode();
					//System.out.println("The poll node is " + node + " flow id "+ flow_id);
				}

				/* judge if the node == srcswid,
				*如果是的话，并且middlebox已经装完，或者node剩余空间足够，则返回path*/
				if (node == srcSwId) {
					if (tsw_space.get(node) >= indexR.get(post.get(node))
							|| indexR.get(post.get(node)) == 0) {
						List<NodePortTuple> path = new ArrayList<>();
						NodePortTuple npt = null;
						Long nextNode = post.get(node);
						Set<Link> l = new HashSet<>();

						do {
							l = lds.getSwitchLinks().get(node);
							for (Link link : l) {
								if (link.getSrc() != node)
									continue;
								if (link.getDst() == nextNode) {
									npt = new NodePortTuple(link.getSrc(),link.getSrcPort());
									path.add(npt);
									npt = new NodePortTuple(link.getDst(),link.getDstPort());
									path.add(npt);
								}
							}
							node = nextNode;
							nextNode = post.get(node);
						} while (nextNode != -1);
						return path; // write the function
					} else {
						fail++;
						System.out.println("Thers's no enough space fail flow id "+flow_id+ " total times for all flows "+ fail);						
						return null; // no available path
					}
				}

				long postsw = post.get(node);
				double t = tranR.get(postsw);
				int i = indexR.get(postsw) - 1; // the next mb index should be installed
				indexR.put(node, indexR.get(postsw));
				while (tsw_space.get(node) > 0 && i >= 0) {
					indexR.put(node, i);
					tsw_space.put(node, tsw_space.get(node) - 1);
					t = t / (1 + factor_arr.get(i));
					i--;
				}
				tranR.put(node, t);

				// update neighbors
				for (Long nei : getPreNei(node)) {
					//predicated link load of nei->node ----->dst
					double demand = tranR.get(node) + getLoad(nei, node); 
					if (mllR.get(nei) > Math.max(mllR.get(node), demand)) {
						double nei_mll = Math.max(mllR.get(node), demand);
						mllR.put(nei, nei_mll);
						post.put(nei, node);
						m_queR.add(new MaxLinkLoad(nei, nei_mll));
					}
				}
			}
		}
    	
    	
    	//all mb factors are negative, return a path. exit the function
    	if (factor_arr.get(factor_arr.size()-1) < 0) {
			while (!m_que.isEmpty()) {
				Long node = null;
				if (m_que.peek() != null) {
					node = m_que.poll().getNode();
					//System.out.println("The poll node is " + node + " flow id "+ flow_id);
				}

				/* judge if the node == srcswid,
				*如果是的话，并且middlebox已经装完，或者node剩余空间足够，则返回path*/
				if (node == dstSwId) {
					if (tsw_space.get(node) >= (arr_size - index.get(pre.get(node))-1)
							|| index.get(pre.get(node)) == (arr_size-1)) {
						List<NodePortTuple> path = new ArrayList<>();
						NodePortTuple npt = null;
						Long nextNode = pre.get(node);
						Set<Link> l = new HashSet<>();

						do {
							l = lds.getSwitchLinks().get(node);
							for (Link link : l) {
								if (link.getDst() != node)
									continue;
								if (link.getSrc() == nextNode) {
									npt = new NodePortTuple(link.getDst(),link.getDstPort());
									path.add(0,npt);
									npt = new NodePortTuple(link.getSrc(),link.getSrcPort());
									path.add(0,npt);
								}
							}
							node = nextNode;
							nextNode = pre.get(node);
						} while (nextNode != -1);
						return path; // write the function
					} else {
						fail++;
						System.out.println("no enough space flow "+flow_id + " fail "+ " total "+fail);
						return null; // no available path
					}
				}

				long presw = pre.get(node);
				double t = tran.get(presw);
				int i = index.get(presw) + 1; // the next mb index should be installed
				index.put(node, index.get(presw));
				while (tsw_space.get(node) > 0 && i <= arr_size-1) {
					index.put(node, i);
					tsw_space.put(node, tsw_space.get(node) - 1);
					t = t * (1 + factor_arr.get(i));
					i++;
				}
				tran.put(node, t);

				// update neighbors
				for (Long nei : getNextNei(node)) {
					//predicated link load of src-------->node--->nei
					double demand = tran.get(node) + getLoad(node, nei); 
					if (mll.get(nei) > Math.max(mll.get(node), demand)) {
						double nei_mll = Math.max(mll.get(node), demand);
						mll.put(nei, nei_mll);
						pre.put(nei, node);
						m_que.add(new MaxLinkLoad(nei, nei_mll));
					}
				}
			}
    	}
    					
        //from the first negative mb
		while (!m_que.isEmpty()) {

			while (!m_que.isEmpty()) {
				Long node = null;
				if (m_que.peek() != null) {
					node = m_que.poll().getNode();
					//System.out.println("1The poll node is  " + node + " flow id "+ flow_id);
				}

				long presw = pre.get(node);
				double t = tran.get(presw);
				int i = index.get(presw) + 1; // the next mb index should be installed
				index.put(node, index.get(presw));
				while (tsw_space.get(node) > 0 && factor_arr.get(i) < 0 && i <= arr_size-1) {
					index.put(node, i);
					tsw_space.put(node, tsw_space.get(node) - 1);
					t = t * (1 + factor_arr.get(i));
					i++;
				}
				tran.put(node, t);
				saw.add(node);

				// update neighbors
				for (Long nei : getNextNei(node)) {
					//predicated link load of src-------->node--->nei
					double demand = tran.get(node) + getLoad(node, nei); 
					if (mll.get(nei) > Math.max(mll.get(node), demand)) {
						double nei_mll = Math.max(mll.get(node), demand);
						mll.put(nei, nei_mll);
						pre.put(nei, node);
						m_que.add(new MaxLinkLoad(nei, nei_mll));
					}
				}
			}    	
		}
		
		//start from the last positive mb
		while (!m_queR.isEmpty()) {
			Long node = null;
			if (m_queR.peek() != null) {
				node = m_queR.poll().getNode();				
			}
			//System.out.println("2The poll node is :"+node);
			long postsw = post.get(node);
			double t = tranR.get(postsw);
			int i = indexR.get(postsw) - 1; // the next mb index should be installed
			indexR.put(node, indexR.get(postsw));
			while (tsw_space.get(node) > 0 && factor_arr.get(i) > 0 && i >= 0) {
				indexR.put(node, i);
				tsw_space.put(node, tsw_space.get(node) - 1);
				t = t / (1 + factor_arr.get(i));
				i--;
			}
			tranR.put(node, t);
			sawR.add(node);
			if(saw.contains(node) && i <= index.get(node)) {
				double m = Math.max(mll.get(node), mllR.get(node));
				mll.put(node, m);
				jun.add(node);
				continue;
			}

			// update neighbors
			for (Long nei : getPreNei(node)) {
				//predicated link load of nei->node ----->dst
				double demand = tranR.get(node) + getLoad(nei, node); 
				if (mllR.get(nei) > Math.max(mllR.get(node), demand)) {
					double nei_mll = Math.max(mllR.get(node), demand);
					mllR.put(nei, nei_mll);
					post.put(nei, node);
					m_queR.add(new MaxLinkLoad(nei, nei_mll));
				}
			}
		} 
		
		double m = Double.MAX_VALUE;
		long num = 0 ;  //index of node u
		if(!jun.isEmpty()) {
			for (long node : jun) {
				if (mll.get(node) < m) {
					m = mll.get(node);
					num = node;
					System.out.println("junction node is "+num + " pre node "+pre.get(num)+ " "+pre.get(pre.get(num)));
				}				
			}
		} else {
			System.out.println("There is no available junction node!");
			return null;			
		}		

		/* trace the path from num to src sw and from num to dst sw, two directions*/
		List<NodePortTuple> preList = new ArrayList<> ();
		List<NodePortTuple> posList = new ArrayList<> ();

		
		Long node = num;
	
		//path from src--->node
		NodePortTuple npt = null;
		Long nextNode = pre.get(node);
		Set<Link> l = new HashSet<>();

		if(node !=srcSwId) {
			do {
				l = lds.getSwitchLinks().get(node);
				for (Link link : l) {
					if (link.getDst() != node)
						continue;
					if (link.getSrc() == nextNode) {
						npt = new NodePortTuple(link.getDst(),link.getDstPort());
						preList.add(0,npt);
						npt = new NodePortTuple(link.getSrc(),link.getSrcPort());
						preList.add(0,npt);
					}
				}
				node = nextNode;
				nextNode = pre.get(node);
			} while (nextNode != -1);			
		}


		node = num;
		//path from node--->dst
		if (node != dstSwId) {
			nextNode = post.get(node);
			do {
				l = lds.getSwitchLinks().get(node);
				for (Link link : l) {
					if (link.getSrc() != node)
						continue;
					if (link.getDst() == nextNode) {
						npt = new NodePortTuple(link.getSrc(),link.getSrcPort());
						posList.add(npt);
						npt = new NodePortTuple(link.getDst(),link.getDstPort());
						posList.add(npt);
					}
				}
				node = nextNode;
				nextNode = post.get(node);
			} while (nextNode != -1);		
		}

		preList.addAll(posList);
		return preList;		// return NodePortTuple from srcSw to dstSw
    } 
    
    public void placeRules(List<NodePortTuple> path, List<Double> factor_arr, OFMatch stod , OFMatch dtos, Ethernet eth, int flowid){
    	//general rule placement
    	// put all switches on the path in order
    	List<Long> sws = new ArrayList<> ();
    	for (int i = 0;  i <= path.size()-2 ; i += 2) {
    		sws.add(path.get(i).getNodeId());
    	}
    	System.out.println("flowid " +flowid + " sws " + sws);
    	
    	
    	int total = factor_arr.size();
    	
    	//separate the factors into two list - & +, and keep the order
    	List<Double> negative = new ArrayList<> ();
    	List<Double> positive = new ArrayList<> ();  	
    	for (int i = 0; i< factor_arr.size() ; i++) {
    		if (factor_arr.get(i) < 0){
    			negative.add(factor_arr.get(i));
    			System.out.println("fid " + flowid +" negative "+factor_arr.get(i));
    		} else {
    			positive.add(factor_arr.get(i)); 
    			System.out.println("fid " + flowid +" positive "+factor_arr.get(i));		
    		}
    	}
    	
/*    	if(!negative.isEmpty())
    		System.out.println("Negative list: "+negative);
    	
    	if(!positive.isEmpty())
    		System.out.println("Positive list: "+positive);*/
    	
    	
    	
    	Map<Long, List<Double>> sw_factor = new HashMap<> ();
    	//List<Double> factors = new ArrayList<>(factor_arr);
    	
    	Map<Long, Boolean> flag = new HashMap<> ();
    	Map<Long, Boolean> flagR = new HashMap<> ();
    	for (int i = 0; i < sws.size(); i++) {
    		flag.put(sws.get(i), false);
    		flagR.put(sws.get(i), false);
    	}
    	
    	//compute location of negative middlebox, from path head to path tail
    	if (!negative.isEmpty()) {
        	for (int i = 0; i < sws.size(); i++){
        		int rule_num = 0;
        		if (sw_space.get(sws.get(i)) > 0 && !negative.isEmpty() ){
        			rule_num = Math.min(sw_space.get(sws.get(i)), negative.size());
        			sw_space.put(sws.get(i), sw_space.get(sws.get(i))-rule_num);
        			flag.put(sws.get(i), true);
        			
        			List<Double> f = new ArrayList<> ();
            		for (int j = 0; j < rule_num; j++) {	
            			f.add(negative.get(0));  
            			negative.remove(0);   
            		}
            		sw_factor.put(sws.get(i), f);   
        		}
        	}   		
    	}


    	
    	//compute location of positive middlebox, from path tail to path head
    	if (!positive.isEmpty()) {
        	for (int i = sws.size()-1 ; i >= 0; i--) {
        		int rule_num = 0;
        		if (sw_space.get(sws.get(i)) > 0 && !positive.isEmpty()) {
        			rule_num = Math.min(sw_space.get(sws.get(i)), positive.size());
        			sw_space.put(sws.get(i), sw_space.get(sws.get(i))-rule_num);
        			flagR.put(sws.get(i), true);
        			
        			List<Double> f = new ArrayList<> ();
            		for (int j = rule_num - 1; j >= 0; j--) {	
            			f.add(0,positive.get(positive.size()-1));
            			positive.remove(positive.size() - 1);
            		}
            		
            		
            		if (flag.get(sws.get(i)) == true) {
            			sw_factor.get(sws.get(i)).addAll(f); //add
            			sw_factor.put(sws.get(i), sw_factor.get(sws.get(i))); 
            		} else {
            			sw_factor.put(sws.get(i), f);
            		}
        		}
        	}    		
    	}

    	
    	System.out.println("flowid "+flowid + " sw factors: "+sw_factor);
    	
    	for (int i = 0; i< path.size(); i += 2){
    		long swId = path.get(i).getNodeId();
			short inport = path.get(i).getPortId();
			short outport = path.get(i+1).getPortId();
			
			//if there is middlebox on the sw
    		if (flag.get(swId) || flagR.get(swId)) {    			
    			int last_index = sw_factor.get(swId).size() - 1;
    			ArrayList<Double> swf_list = new ArrayList<> (sw_factor.get(swId));
    			
				List<Short> ports = new ArrayList<> (getPorts(swf_list)); 
				ports.add(0, inport);
				ports.add(outport);
/*				for(short port : ports) {
					//System.out.println("port_num: "+port +" sw "+swId + " srcIp "
				//+ipToString(stod.getNetworkSource())+" " + ipToString(stod.getNetworkDestination()));
				}*/
				
				for (int k = 0; k < ports.size()-1; k++) {
					short in = ports.get(k);
					short out = ports.get(k+1);
					
					stod.setInputPort(in);
	    			List<OFAction> actions = new ArrayList<> ();
	    			OFFlowMod sw_flow = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
	    			sw_flow.setCommand(OFFlowMod.OFPFC_ADD)
	    			.setIdleTimeout((short) 0)
	    			.setHardTimeout((short) 0)
	    			.setMatch(stod)
	    			.setPriority((short) 200);
					if (out == ports.get(ports.size()-1) && swf_list.get(last_index) == factor_arr.get(total-1)){
						
						
    	                OFActionDataLayerSource setsrc = new OFActionDataLayerSource();
    	        		setsrc.setDataLayerAddress(eth.getSourceMACAddress());
    	        		actions.add(setsrc);
    	    			OFActionOutput output = new OFActionOutput(out);
    	    			actions.add(output);

    	    			sw_flow.setActions(actions)
    	    			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH + OFActionDataLayerSource.MINIMUM_LENGTH ));
    	    		    sfpService.addFlow("route" + uniqueFlow, sw_flow, HexString.toHexString(swId));  
    	    		    uniqueFlow ++;   	
						
						
					} else {
		    			OFActionOutput output = new OFActionOutput(out);
		    			actions.add(output);
		    			
		    			
		    			sw_flow.setActions(actions)
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
    	//reverse(path,dtos);
    }
   
    public void placeRoutingRules(List<NodePortTuple> path, List<Double> factor_arr, 
    		OFMatch stod , OFMatch dtos, Ethernet eth, int flowid){
    	for (int i = 0; i< path.size(); i += 2){
			
			stod.setInputPort(path.get(i).getPortId());
			List<OFAction> actions = new ArrayList<> ();
			OFActionOutput output = new OFActionOutput(path.get(i+1).getPortId());
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
    	
    	for(int i = 0; i <= path.size() - 1; i += 2) {
    		short inport = path.get(i).getPortId();
    		dtos.setInputPort(path.get(i+1).getPortId());
    		List<OFAction> actionsR = new ArrayList<OFAction> ();
    		OFActionOutput outportR = new OFActionOutput(inport);
    		actionsR.add(outportR);
    		
    		OFFlowMod dtosF = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
    		dtosF.setCommand(OFFlowMod.OFPFC_ADD)
    			.setIdleTimeout((short) 0)
    			.setHardTimeout((short) 0)
    			.setMatch(dtos)
    			.setPriority((short) 200)
    			.setActions(actionsR)
    			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
    		sfpService.addFlow("route" + uniqueFlow, dtosF, HexString.toHexString(path.get(i).getNodeId()));  
    		uniqueFlow ++;
    		
    	}   	    	
    }
    
    public void reverse(List<NodePortTuple> path, OFMatch dtos) {
    	for(int i = 0; i <= path.size() - 1; i += 2) {
    		short inport = path.get(i).getPortId();
    		dtos.setInputPort(path.get(i+1).getPortId());
    		List<OFAction> actionsR = new ArrayList<OFAction> ();
    		OFActionOutput outportR = new OFActionOutput(inport);
    		actionsR.add(outportR);
    		
    		OFFlowMod dtosF = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
    		dtosF.setCommand(OFFlowMod.OFPFC_ADD)
    			.setIdleTimeout((short) 0)
    			.setHardTimeout((short) 0)
    			.setMatch(dtos)
    			.setPriority((short) 200)
    			.setActions(actionsR)
    			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
    		sfpService.addFlow("route" + uniqueFlow, dtosF, HexString.toHexString(path.get(i).getNodeId()));  
    		uniqueFlow ++;
    		
    	}   	    	
    }
    //randomly arrange the factors 
    public void randomPlace(List<NodePortTuple> path, List<Double> factor_arr, OFMatch stod , OFMatch dtos, Ethernet eth) {
    	
    	// the total number of mb of the flow
    	int total = factor_arr.size();
    	
    	// copy factor_arr to factors
    	List<Double> factors = new ArrayList<> (factor_arr);
    	Map<Long, List<Double>> sw_factor = new HashMap<> ();

    	
    	//record the switches on the path in order from source to dst
    	List<Long> sws = new ArrayList<> ();
    	for (int i = 0;  i <= path.size()-2 ; i += 2) {
    		sws.add(path.get(i).getNodeId());
    	}
    	
    	//swId -- boolean, true means install mb on the sw
    	Map<Long, Boolean> flag = new HashMap<> ();
    	for (int i = 0; i < sws.size() ; i++) {
    		flag.put(sws.get(i), false);
    	}

    	//record random swid
    	List<Long> seen = new ArrayList<> ();
    	//record random index of sw on the path
    	List<Integer> index_order = new ArrayList<> ();
    	int space = 0;
    	
    	//generate random index, util the total space of sws at the index >=factor_arr.size

    	while (space < total) {
        	Random r = new Random();
        	int index = r.nextInt(sws.size());
        	Long nodeId = sws.get(index);
        	if (!seen.contains(nodeId) && sw_space.get(nodeId) > 0) {

        		seen.add(nodeId);
        		flag.put(nodeId,true);
        		index_order.add(index);
        		space += sw_space.get(nodeId);
        	}    		
    	}
    	
    	//总的交换机space和可能大于所需要的space，所以要对sw进行排序，保证前面的sw的space耗尽　再用后面的
    	Collections.sort(index_order);
    	int size = index_order.size();
    	Long lastsw = sws.get(index_order.get(size-1));
    	
    	//记录每个被选中的交换机需要减去多少rule　space（n个）, 可以在安装flow时创建　n+1 条rule

    	for (int i = 0; i < seen.size(); i++) {
    		int pre_space = 0;
    		List<Double> f = new ArrayList<> ();
    		
			if(seen.size() == 1) {
				int rule_num = Math.min(sw_space.get(seen.get(i)), factors.size());
				sw_space.put(seen.get(i), sw_space.get(seen.get(i))-rule_num);
				sw_factor.put(seen.get(i), factors);
				
			} else {
    			if(seen.get(i) != lastsw) {
        			int remainder_space = sw_space.get(seen.get(i));
        			f = new ArrayList<Double>(factors.subList(0, sw_space.get(seen.get(i))));
        			for(int k = 0; k< remainder_space; k++) {
        				factors.remove(0);    				
        			}
        			sw_factor.put(seen.get(i), f);
        			pre_space += remainder_space;
        			sw_space.put(seen.get(i), 0);  
    				
    			} else {
    				//remainder number of middleboxes
        			int remainder = total - pre_space;
        			f = new ArrayList<Double>(factors);
        			sw_factor.put(seen.get(i), f);   				 
        			sw_space.put(seen.get(i), sw_space.get(seen.get(i))-remainder);
    				
    			}   				
			}

    	}
    	
    	
    	for (int i = 0; i< path.size(); i += 2){
    		long swId = path.get(i).getNodeId();
    		if (flag.get(swId)) {
    			short inport = path.get(i).getPortId();
    			short outport = path.get(i+1).getPortId();
    			
    			int last_index = sw_factor.get(swId).size() - 1;
    			ArrayList<Double> swf_list = new ArrayList<> (sw_factor.get(swId));
    			
				List<Short> ports = new ArrayList<> (getPorts(swf_list)); 
		
				ports.add(0, inport);
				ports.add(outport);
				for(short port : ports) {
					System.out.println("port_num: "+port);
				}
				
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
    			short inport = path.get(i).getPortId();
    			short outport = path.get(i+1).getPortId();
    			
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
    	}
  	
    	reverse(path, dtos);
    }
    
    public void firstPlace(List<NodePortTuple> path, List<Double> factor_arr, OFMatch stod , OFMatch dtos, Ethernet eth) {
    	// put all switches on the path in order
    	List<Long> sws = new ArrayList<> ();
    	for (int i = 0;  i <= path.size()-2 ; i += 2) {    		
    		sws.add(path.get(i).getNodeId());
    	}
    	    	
    	//copy factor array to the list factors
    	ArrayList<Double> factors = new ArrayList<> (factor_arr);
    	
    	//record if the sw has mb rules to install, init to be false
    	Map<Long, Boolean> flag = new HashMap<> ();
    	for (int i = 0; i < sws.size(); i++) {
    		flag.put(sws.get(i), false);
    	}
    	
    	
    	//record the middleboxes installed on the switch
    	Map<Long, List<Double>> sw_factor = new HashMap<> ();
    	for(int i = 0; i < sws.size(); i++) {
    		while(sw_space.get(sws.get(i)) > 0 && !factors.isEmpty()) { 
    			
    			flag.put(sws.get(i), true);
    			int rule_num = Math.min(sw_space.get(sws.get(i)), factors.size());
    			sw_space.put(sws.get(i), sw_space.get(sws.get(i))-rule_num);
    			//System.out.println("222sw "+sws.get(i)+ " space "+sw_space.get(sws.get(i)));
    			List<Double> f = new ArrayList<> ();
    			for(int j = 0; j < rule_num; j++){    				
    				f.add(factors.get(0));
    				System.out.println("dst MAC " + eth.getDestinationMAC().toString() + " src MAC " + eth.getSourceMAC().toString()+
    				" sw_id " + sws.get(i) + " mb " + factors.get(0));
    				factors.remove(0);
    			}
    			
    			sw_factor.put(sws.get(i), f);		
    		}
    	}
    	

    	
    	//System.out.println("111sw factors: "+sw_factor);
    	for (int i = 0; i< path.size(); i += 2){
    		long swId = path.get(i).getNodeId();
			short inport = path.get(i).getPortId();
			short outport = path.get(i+1).getPortId();
			
    		if (flag.get(swId)) {

    			
    			int last_index = sw_factor.get(swId).size() - 1;
    			ArrayList<Double> swf_list = new ArrayList<> (sw_factor.get(swId));
    			
				List<Short> ports = new ArrayList<> (getPorts(swf_list)); 

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
					if (out == ports.get(ports.size()-1) && swf_list.get(last_index) == factor_arr.get(factor_arr.size()-1)){
						
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
    
    //installed from the last sw to first sw, the middlebox order keep same
    public void lastPlace(List<NodePortTuple> path, List<Double> factor_arr, OFMatch stod , OFMatch dtos, Ethernet eth) {

    	// put all switches on the path in order
    	List<Long> sws = new ArrayList<> ();
    	for (int i = 0;  i <= path.size()-2 ; i += 2) {    		
    		sws.add(path.get(i).getNodeId());
    	}
    	    	
    	//copy factor array to the list factors
    	ArrayList<Double> factors = new ArrayList<> (factor_arr);
    	
    	//record if the sw has mb rules to install, init to be false
    	Map<Long, Boolean> flag = new HashMap<> ();
    	for (int i = 0; i < sws.size(); i++) {
    		flag.put(sws.get(i), false);
    	}
    	
    	
    	//record the middleboxes installed on the switch
    	Map<Long, List<Double>> sw_factor = new HashMap<> ();
    	for(int i = sws.size()-1; i >= 0; i--) {
    		while(sw_space.get(sws.get(i)) > 0 && !factors.isEmpty()) { 
    			
    			flag.put(sws.get(i), true);
    			int rule_num = Math.min(sw_space.get(sws.get(i)), factors.size());
    			sw_space.put(sws.get(i), sw_space.get(sws.get(i))-rule_num);
    			
    			List<Double> f = new ArrayList<> ();
    			//compute the middleboxes for each switch
    			for(int j = rule_num - 1; j >= 0; j--){    				
    				f.add(factors.get(0));
    				factors.remove(0);
    			}
    			sw_factor.put(sws.get(i), f);
    		}
    	}

    	for (int i = 0; i< path.size(); i += 2){
    		long swId = path.get(i).getNodeId();
			short inport = path.get(i).getPortId();
			short outport = path.get(i+1).getPortId();
			
    		if (flag.get(swId)) {
			
    			
    			ArrayList<Double> swf_list = new ArrayList<> (sw_factor.get(swId));
    			int last_index = swf_list.size() - 1;
    			
				List<Short> ports = new ArrayList<> (getPorts(swf_list)); 
								
				ports.add(0, inport);
				ports.add(outport);
				for(short port : ports) {
					// System.out.println("port_num: "+port +" "+swId);
				}
				

				
				for (int k = 0; k < ports.size()-1; k++) {
					List<OFAction> actions = new ArrayList<> ();
					short in = ports.get(k);
					short out = ports.get(k+1);

					stod.setInputPort(in);
					
					if (out == ports.get(ports.size()-1) && swf_list.get(last_index)==factor_arr.get(factor_arr.size()-1)){
						
    	    			
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
    		    sfpService.addFlow("route" + uniqueFlow, sw_flow, HexString.toHexString(swId));  
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
		    sfpService.addFlow("route" + uniqueFlow, sw_flow, HexString.toHexString(swId));  
		    uniqueFlow ++;  
    	}

    }
 
	  
    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext context) {
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
        	//System.out.println("ARP dstMAC "+dHostMac);
        	handleARPRequest(sw, pi, context, dHostIp, dHostMac);
        	
        	return Command.CONTINUE;
        } else if (pkt instanceof IPv4) {
//        	System.out.println("paketin seen at sw "+sw.getId());
        	String dHostIp = IPv4.fromIPv4Address(match.getNetworkDestination());
        	String [] str = dHostIp.split("\\.");
        	long dstsw = Long.parseLong(str[2]);
        	int temp = Integer.parseInt(str[3]);
        	//int t = temp - 5 * (Integer.parseInt(str[2]) - 1);
//        	System.out.println("src IP " + IPv4.fromIPv4Address(match.getNetworkSource()) + " dst ip " + dHostIp);
        	short dstport = 0;
        	if (dstsw == 5){
        		int t = temp - 12;  //the last hostid of sw 5 is 24
        		dstport = (short) t;
        		
        	} else if (dstsw == 6) {
        		int t = temp - 16;  //the last hostid of sw 6 is 30
        		dstport = (short) t;
        	}
        	//System.out.println("IP dstsw "+dstsw + " dstport "+dstport);
        
        	
        	int fid = getflowid((IPv4) pkt);
        	if (fid == 0)
        			return Command.CONTINUE;
        	List<Double> factor_arr = new ArrayList<> ();
        	factor_arr = fid_factors.get(fid);
        	if(uniqueflows.contains(fid)) {
        	//	System.out.println("Has seen the flow"+ fid);
        		return Command.CONTINUE;
        	} else
        	uniqueflows.add(fid);
        	System.out.println("Flow id seen " + fid);
        	List<NodePortTuple> path = new ArrayList<> ();
//        	switch(fid) {
//        		case 1:
//        			path = fixedpath(p_one);
//        			break;
//        		case 2:
//        			path = fixedpath(p_two);
//        			break;
//        		case 3:
//        			path = fixedpath(p_three);
//        			break;
//        		case 4:
//        			path = fixedpath(p_four);
//        			break;
//        	}
        	
            SwitchPort[] srcAP = IDeviceService.fcStore.get(context, IDeviceService.CONTEXT_SRC_DEVICE).getAttachmentPoints();

            //System.out.println("1 srcswid and dstswid " + srcAP[0].getSwitchDPID() + " ---- " +dstAP[0].getSwitchDPID());
         //   System.out.println("Switch ports size : "+srcAP.length);


        	NodePortTuple head = new NodePortTuple(srcAP[0].getSwitchDPID(), srcAP[0].getPort());
        	NodePortTuple tail = new NodePortTuple(dstsw,dstport);
        	//System.out.println("Head: "+head+" Tail: "+tail);
        	
        	
        	//compute path between switches
        	//path = widestRoute(head.getNodeId(),tail.getNodeId());
        	path = lfgl(sw, context, (IPv4) pkt, srcAP[0].getSwitchDPID(), dstsw);
//        	path = shortestRoute(head.getNodeId(),tail.getNodeId());
        	
        	//assemble the intact path, include the hosts' location
        	path.add(0, head);
        	path.add(tail);       	
        	System.out.println("The path is "+path);

        	//create OFMatch src to dst and dst to src
        	OFMatch stod = new OFMatch();
        	OFMatch dtos= new OFMatch();
        	stod.setDataLayerType(Ethernet.TYPE_IPv4).setNetworkSource(match.getNetworkSource())
    				 .setNetworkDestination(match.getNetworkDestination());

        	dtos.setDataLayerType(Ethernet.TYPE_IPv4).setNetworkSource(match.getNetworkDestination())
    				 .setNetworkDestination(match.getNetworkSource());
        	
        	//install rules over the path
          placeRules(path,factor_arr,stod,dtos,eth, fid);
//          placeRoutingRules(path,factor_arr,stod,dtos,eth, fid);
//        	firstPlace(path,factor_arr,stod,dtos,eth);
//          lastPlace(path,factor_arr,stod,dtos,eth);
//         	randomPlace(path,factor_arr,stod,dtos,eth);
             	
        } else {
        	return Command.CONTINUE;
        }
       return Command.CONTINUE;    	
    }
   
    // get remainder bandwidth of link(src_sw, dst_sw)
//    public Double getBc(Long src_sw, Long dst_sw) {
//    	Set<Link> links = lds.getSwitchLinks().get(src_sw);
//    	short port = 0;
//    	Double link_bc = null;
//    	for(Link link : links) {
//    		
//    		if (link.getSrc() == src_sw && link.getDst() == dst_sw) {
//    			port = link.getSrcPort();  
//    	    	link_bc = LINK_CAPACITY - switchesInfo.get(src_sw).get(port).trafficIntensity; 
//    		} else {
//    			link_bc = Double.MIN_VALUE; //there is no direct link between this two nodes;
//    		}    		
//    	}    	
//    	return link_bc;
//    }
//    
    
    // get current load of link(src_sw, dst_sw)
    public Double getLoad(Long src_sw, Long dst_sw) {
    	Set<Link> links = lds.getSwitchLinks().get(src_sw);
    	short port = 0;
    	for(Link link : links) {
    		
    		if (link.getSrc() == src_sw && link.getDst() == dst_sw) {
    			port = link.getSrcPort();
    		} 
    	}
    	//System.out.println("Get load "+src_sw + " port "+port);
    	return switchesInfo.get(src_sw).get(port).trafficIntensity; 
    }
   


    class NodeBw implements Comparable<NodeBw> {
        Long node;
        double bw;

        public NodeBw(Long node, double bw) {
            this.node = node;
            this.bw = bw;
        }

        @Override
        public int compareTo(NodeBw o) {
            if (o.bw == this.bw) {
                return (int)(this.node - o.node);
            }
            else if (this.bw < o.bw) 
            	return -1;
            else if (this.bw > o.bw) 
            	return 1;
            else 
            	return 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            NodeBw other = (NodeBw) obj;
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
        
        NewRouting getOuterType() {
            return NewRouting.this;
        }
    }
    
    
	List<NodePortTuple> widestRoute(long srcSwitchId, long dstSwitchId) {
		
		HashMap<Long, Link> nexthoplinks = new HashMap<Long, Link>();
		HashMap<Long, Double> bws = new HashMap<Long, Double>();

		for (Long node: switches.keySet()) {
			nexthoplinks.put(node, null);
			// nexthopnodes.put(node, null);
			bws.put(node, Double.MAX_VALUE);
		}

		HashMap<Long, Boolean> seen = new HashMap<Long, Boolean>();
		PriorityQueue<NodeBw> nodeq = new PriorityQueue<NodeBw>();
		nodeq.add(new NodeBw(dstSwitchId, 0));
		bws.put(dstSwitchId, 0.0);
		while (nodeq.peek() != null) {
			NodeBw n = nodeq.poll();
			Long cnode = n.node;
			double cbw = n.bw;
			if (cbw >= Double.MAX_VALUE) break;
			if (seen.containsKey(cnode)) continue;
			seen.put(cnode, true);

			
			for (Link link : lds.getSwitchLinks().get(cnode)) {
				if (link.getDst() != cnode) continue; // skip links with cnode as src
				Long neighbor = link.getSrc();
				
				// links directed toward cnode will result in this condition
				if (neighbor.equals(cnode))
					continue;

				if (seen.containsKey(neighbor))
					continue;

				double w; 
				if (switchesInfo.get(neighbor) != null && switchesInfo.get(neighbor).get(link.getSrcPort()) != null) {
					w = switchesInfo.get(neighbor).get(link.getSrcPort()).trafficIntensity;
				}
				else
					break;
				
				double nbw = cbw > w ? cbw : w; // greater traffic intensity, less available bandwidth 
				
				if (nbw < bws.get(neighbor)) {
					bws.put(neighbor, nbw);
					nexthoplinks.put(neighbor, link);
					// nexthopnodes.put(neighbor, cnode);
					NodeBw ndTemp = new NodeBw(neighbor, nbw);
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
		NodePortTuple npt;
		
		if ((nexthoplinks!=null) && (nexthoplinks.get(srcSwitchId)!=null)) {
            while (srcSwitchId != dstSwitchId) {
                Link l = nexthoplinks.get(srcSwitchId);
                npt = new NodePortTuple(l.getSrc(), l.getSrcPort());
                switchPorts.addLast(npt);
                npt = new NodePortTuple(l.getDst(), l.getDstPort());
                switchPorts.addLast(npt);
                srcSwitchId = nexthoplinks.get(srcSwitchId).getDst();
            }
        }
		
		Route result = null;
        if (switchPorts != null && !switchPorts.isEmpty())
            result = new Route( new RouteId(srcSwitchId, dstSwitchId), switchPorts);
        
        List<NodePortTuple> path = new ArrayList<> ();
        path = result.getPath();
       // System.out.println("Route: s="+osrcSwitchId+",d="+dstSwitchId+" r="+ result);
        return path;
	}
	
    protected class NodeDist implements Comparable<NodeDist> {
        private final Long node;
        public Long getNode() {
            return node;
        }

        private final int dist;
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

        private NewRouting getOuterType() {
            return NewRouting.this;
        }
    }
    
    
    List<NodePortTuple> shortestRoute(long srcSwitchId, long dstSwitchId) {
    	switches = floodlightProvider.getAllSwitchMap();
		//assert(switches != null);
		
		HashMap<Long, Link> nexthoplinks = new HashMap<Long, Link>();
        HashMap<Long, Integer> cost = new HashMap<Long, Integer>();
     
        int w;

        for (Long node: switches.keySet()) {
            nexthoplinks.put(node, null);
            //nexthopnodes.put(node, null);
            cost.put(node, MAX_PATH_WEIGHT);
        }

        HashMap<Long, Boolean> seen = new HashMap<Long, Boolean>();
        PriorityQueue<NodeDist> nodeq = new PriorityQueue<NodeDist>();
        nodeq.add(new NodeDist(srcSwitchId, 0));
        cost.put(srcSwitchId, 0);
        while (nodeq.peek() != null) {
        	
            NodeDist n = nodeq.poll();
            Long cnode = n.getNode();
            int cdist = n.getDist();
            if (cdist >= MAX_PATH_WEIGHT) break;
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
    
    //get path between switches, without src host port and dst host port
    List<NodePortTuple> fixedpath(long[] sws) {
    	LinkedList<NodePortTuple> path = new LinkedList<> ();
    	List<Long> swchain = new ArrayList<> ();
    	for (int i = 0 ; i < sws.length; i++) {
    		swchain.add(sws[i]);
    	}
    	
    	for (int i = 0 ; i < swchain.size() - 1; i++) {
    		long src = swchain.get(i);
    		long dst = swchain.get(i+1);
    		for (Link l : lds.getSwitchLinks().get(src)) {
    			if (l.getDst() == dst) {
    				path.add(new NodePortTuple(l.getSrc(), l.getSrcPort()));
    				path.add(new NodePortTuple(l.getDst(), l.getDstPort()));
    				break;
    			}
    		}
    	}
    	return path;
    }
 
    protected static String ipToString(int ip) {
        return Integer.toString(U8.f((byte) ((ip & 0xff000000) >> 24)))
               + "." + Integer.toString((ip & 0x00ff0000) >> 16) + "."
               + Integer.toString((ip & 0x0000ff00) >> 8) + "."
               + Integer.toString(ip & 0x000000ff);
    }
}