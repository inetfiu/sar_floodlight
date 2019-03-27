package net.floodlightcontroller.tosr;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import java.util.StringTokenizer;
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
import org.openflow.util.U8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.ImmutablePort;

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

public class TOSR implements IOFMessageListener, IFloodlightModule {
	public static final String m = "00:00:00:00:00:";
	public static int fail = 0;
//	public static final int num_mb = 4;  //update based on the middlebox settings
	//public static final int NUM_SW = 6; //the total number of switches, change based on topology
//	public static final double LINK_CAPACITY = 10000.0; //Link capacity, change based on test(unit: kbps)
//	public static final double INF = 10000.0;
	public static final double BAND = 10000;  //in kbps 10Mbps
	public static Set<Integer> uniqueflows = new HashSet<> ();
	public static Set<Long> backgroundsw = new HashSet<> ();

	public static final int MAX_LINK_WEIGHT = 9000; //used for shortest path
    public static final double MAX_PATH_WEIGHT = Double.MAX_VALUE;
    
	protected static Map<Integer, Map<String, String>> fid_ip = new HashMap<> (); // todo: add port for 
//	protected static Map<Integer, ArrayList<Double>> fid_factors = new HashMap<> ();  //each flow has a chain of middleboxes
	//each flow has ingress and egress traffic rate
	protected static Map<Integer,Map<Double,Double>> fid_tran = new HashMap<> (); 
	protected static int uniqueFlow = 0;
	protected static BufferedReader reader;
	
	//types of mb: 4; number of switches: 11
	protected static double mb_capacity[][] = new double[11][4];
	//ratio = factor + 1
	protected static Map<Double, Integer> factor_type = new HashMap<>();
	static
    {
        factor_type.put(0.2, 0);
        factor_type.put(-0.3, 1);
        factor_type.put(0.1, 2);
        factor_type.put(-0.2, 3);
    }
	
	protected static Map<Integer, Double> type_factor = new HashMap<>();
	static
    {
		type_factor.put(0, 0.2);
		type_factor.put(1, -0.3);
		type_factor.put(2, 0.1);
		type_factor.put(3, -0.2);
    }
	
	//use linked list (hashmap) to represent a Directed graph (mb relations)
	protected static HashMap<Integer, HashMap<Integer, ArrayList<Integer>>> fid_dags = 
			new HashMap<Integer, HashMap<Integer, ArrayList<Integer> >>();

	

    protected static Logger log = LoggerFactory.getLogger(TOSR.class);
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

        private TOSR getOuterType() {
            return TOSR.this;
        }
    }
	

	@Override
	public String getName() {
		return TOSR.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		//devicemanager is called before TOSR
		return (type.equals(OFType.PACKET_IN) && (name.equals("topology") || name.equals("devicemanager")));
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return  false;
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
	    log = LoggerFactory.getLogger(TOSR.class);
	    
	    //generate random mb capacity
	    Random random = new Random(2);
	    double min = 0.6;
	    double max = 0.9;
	    int swmin = 0;
	    int swmax = 10;
	    System.out.println("mb_capacity: ");
	    //only 0.73 sws has mb ,each mb capacity range from [0.6, 0.9] * BAND
	    for(int i = 0; i < 11; i++){
//	    	int randomsw = random.nextInt((swmax - swmin) + 1) + swmin;
	    	for(int j = 0; j < 4; j++) {
	    		if (i == 0 ) {
	    			mb_capacity[i][j] = 0.0;
	    		} else {

		    		
	    			if (i == 1 || i == 4) {
	    				if (j == 0 ) {
				    		double scaled = random.nextDouble() * (max - min);
				    		double shifted = min + scaled;
				    		mb_capacity[i][j] = shifted * BAND;
	    				} else {
	    					mb_capacity[i][j] = 0.0;
	    				}
	    			} else {
			    		double scaled = random.nextDouble() * (max - min);
			    		double shifted = min + scaled;
			    		mb_capacity[i][j] = shifted * BAND;
	    			}
	    		}

	    		System.out.println(mb_capacity[i][j] + " ");
	    	}
	    	 System.out.println();
	    }

	    
		//read flowId and sorted list of mb's factors into Map<Integer, ArrayList<Double>> flow_factors
//		try {
//			reader = new BufferedReader(new FileReader("/home/nu/Desktop/mininet_test_topo/tosr/fid_factors.txt"));
//	        String line;
//	        while((line = reader.readLine()) != null && !line.isEmpty())
//	        {
//	        	String[] tokens = line.split(" ");
//	            Integer i;
//	            ArrayList<Double> list = new ArrayList<>();
//	            if(tokens[0].length() > 0) {
//		            i = Integer.valueOf(tokens[0].trim());
//
//		            for(int j = 1; j < tokens.length; j++)
//		            	list.add(Double.valueOf(tokens[j]));
//
//		            fid_factors.put(i, list);
//	            	
//	            }
//	            System.out.println("fid_factors: " + fid_factors);
//	        }
//		} catch (IOException ex) {
//			ex.printStackTrace();
//		} finally {
//			if (reader != null)
//				try {
//					reader.close();
//				} catch (IOException ex) {
//					ex.printStackTrace();
//				}
//		}
		
		//input mb DAG for each flow
		try {
			reader = new BufferedReader(new FileReader("/home/wma006/Desktop/mininet_test_topo/tosr/icnp/fid_dags.txt"));
	        String line;
	        HashMap<Integer, ArrayList<Integer>> fid_dag = new  HashMap<Integer, ArrayList<Integer>> ();
	        int flowid = 0;
	        while((line = reader.readLine()) != null && !line.isEmpty())
	        {
	        	String[] tokens = line.split(" ");
	            Integer i;
	            ArrayList<Integer> list = new ArrayList<>();
	         
	            if(tokens[0].length() > 0) {
		            i = Integer.valueOf(tokens[0].trim());
		            for(int j = 1; j < tokens.length; j++)
		            	list.add(Integer.valueOf(tokens[j]));

		            fid_dag.put(i, list);
		            if (i == 3) {
		            	flowid++;
		            	System.out.println("flowid : " + flowid + " fid_dag: " + fid_dag);
		            	fid_dags.put(flowid, new HashMap<Integer, ArrayList<Integer>> (fid_dag));
		            	
		            	fid_dag.clear();
		            }
	            	
	            }
//	            System.out.println("fid_dags: " + fid_dags);
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
		
		for(int i = 0; i < fid_dags.size();i++) {
			System.out.println("key:" + (i+1) + " value map:" + fid_dags.get(i+1)); 
		}
		
		//Init fid_tran
		try {
			reader = new BufferedReader(new FileReader("/home/wma006/Desktop/mininet_test_topo/tosr/icnp/fid_ip.txt"));
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
			reader = new BufferedReader(new FileReader("/home/wma006/Desktop/mininet_test_topo/tosr/icnp/fid_tran.txt"));
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
        timer.schedule(but,0, 1000);//set to run every 5 seconds task t      
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
	
	    	    		if(portNum >=1 && portNum <= 4)
	    	    			continue;
	    	    		
//	    	    		
//	    	    		if(sw.getId() == 8 || sw.getId() == 2) {
//	    	    			if(portNum==5 || portNum==6)
//		    	    			continue;
//	    	    		}
//    			
	    	    		
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
	
	
	//new for tosr place set 4 middleboxes
    public List<Short> getPorts(ArrayList<Double> swf_list) {
    	List<Short> ports = new ArrayList<> ();
		for (double temp : swf_list) {
			Double t = temp * 10;
			
			int c = t.intValue();
//			System.out.println("port case : " + c);
			switch(c) {
			case 2:
				ports.add((short) 1);
				break;
			case -3:
				ports.add((short) 2);
				break;
			case 1:
				ports.add((short) 3);
				break;
			case -2:
				ports.add((short) 4);
				break;
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

    public  List<NodePortTuple> icnp_getpath(List<Long> swchain){
    	LinkedList<NodePortTuple> path = new LinkedList<> ();
    	
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
	
    public void icnp_placerule (List<NodePortTuple> path, ArrayList<Double> factor_arr, Map<Long, ArrayList<Double> > sw_factor, OFMatch stod , OFMatch dtos, 
    		Ethernet eth,  int fid) {
    	    	//System.out.println("111sw factors: "+sw_factor);
    	for (int i = 0; i< path.size(); i += 2){
    		long swId = path.get(i).getNodeId();
			short inport = path.get(i).getPortId();
			short outport = path.get(i+1).getPortId();
			
    		if (sw_factor.get(swId).size() > 0) {

    			
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
    
    
    //used for tosr and the heuristic to place routing rules and mbs
    public void firstPlace(List<NodePortTuple> path, ArrayList<Double> factor_arr, OFMatch stod , OFMatch dtos, 
    		Ethernet eth, ArrayList<Long> sw_mbs, int fid) {
    	// put all switches on the path in order
    	System.out.println("in place sw_mbs size: " + sw_mbs.size()+" path is: ");
    	ArrayList<Long> sws = new ArrayList<> ();
    	for (int i = 0;  i <= path.size()-2 ; i += 2) {    		
    		sws.add(path.get(i).getNodeId());
    		System.out.print(path.get(i).getNodeId() + " ");
    	}
    	System.out.print("\n");
    	//copy factor array to the list factors
    	ArrayList<Double> factors = new ArrayList<> (factor_arr);
    	
    	//record if the sw has mb rules to install, init to be false
    	Map<Long, Boolean> flag = new HashMap<> ();
    	for (int i = 0; i < sws.size(); i++) {
    		flag.put(sws.get(i), false);
    	}
    	
    	
    	//record the middleboxes installed on the switch
    	Map<Long, ArrayList<Double>> sw_factor = new HashMap<> ();
    	for (int i = 0; i < sw_mbs.size(); i++)
    		sw_factor.put(sw_mbs.get(i), new ArrayList<Double>());
    	
    	ArrayList<Double> f = new ArrayList<> ();
    	
    	//update mb capacity
    	double rate = getInittran(fid).t_s;
    	for (int i = 0; i < sw_mbs.size(); i++) {
    		Boolean value = flag.get(sw_mbs.get(i));
    		System.out.println("mb sw: " + sw_mbs.get(i));
    		if (value != false) {
//    			f = sw_factor.get(sw_mbs.get(i));
    			f.add(factors.get(0));
     			int mb_type = factor_type.get(factors.get(0));
     			double temp = mb_capacity[sw_mbs.get(i).intValue() - 1][mb_type];
    			mb_capacity[sw_mbs.get(i).intValue() - 1][mb_type] = temp - rate;
    			rate = rate * (1 + factors.get(0));
    			factors.remove(0);
    		
    		} else {
    			flag.put(sw_mbs.get(i), true);
    			f.add(factors.get(0));
     			int mb_type = factor_type.get(factors.get(0));
     			double temp = mb_capacity[sw_mbs.get(i).intValue() - 1][mb_type];
    			mb_capacity[sw_mbs.get(i).intValue() - 1][mb_type] = temp - rate;
    			rate = rate * (1 + factors.get(0));
    			factors.remove(0);
    			
    		}
    		System.out.println("factor added: " + f.get(0));
    		sw_factor.get(sw_mbs.get(i)).addAll(f);
    		f.clear();
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

    
    //get path between switches, without src host port and dst host port
    List<NodePortTuple> tosrpath(Snode solution) {
    	LinkedList<NodePortTuple> path = new LinkedList<> ();
    	List<Long> swchain = solution.path;
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
//        	System.out.println("paketin seen at sw "+sw.getId());
        	String dHostIp = IPv4.fromIPv4Address(match.getNetworkDestination());
        	String [] str = dHostIp.split("\\.");
        	long dstsw = Long.parseLong(str[2]);
        	int temp = Integer.parseInt(str[3]);
        	//int t = temp - 5 * (Integer.parseInt(str[2]) - 1);
//        	System.out.println("src IP " + IPv4.fromIPv4Address(match.getNetworkSource()) + " dst ip " + dHostIp);
        	short dstport = 0;
        	if (dstsw == 5){
        		int t = temp - 24;  //the last hostid of sw 4 is 24
        		dstport = (short) t;
        	} else if (dstsw == 3) {
        		int t = temp - 12;  //the last hostid of sw 10 is 60
        		dstport = (short) t;
        	} else if (dstsw == 2) {
        		int t = temp - 6;  //the last hostid of sw 1 is 6
        		dstport = (short) t;
        	} else if (dstsw == 8) {
        		int t = temp - 42;  //the last hostid of sw 7 is 42
        		dstport = (short) t;
        	} else if (dstsw == 7) {
        		int t = temp - 36;  //the last hostid of sw 7 is 42
        		dstport = (short) t;
        	}
        	
//        	System.out.println("IP dstsw "+dstsw + " dstport "+dstport);
            SwitchPort[] srcAP = IDeviceService.fcStore.get(context, IDeviceService.CONTEXT_SRC_DEVICE).getAttachmentPoints();

//          System.out.println("1 srcswid and dstswid " + srcAP[0].getSwitchDPID() + " ---- " +dstAP[0].getSwitchDPID());

            NodePortTuple head = new NodePortTuple(srcAP[0].getSwitchDPID(), srcAP[0].getPort());
            NodePortTuple tail = new NodePortTuple(dstsw,dstport);
//            System.out.println("Head: "+head+" Tail: "+tail);
            
        	
        	int fid = getflowid((IPv4) pkt);
//        	System.out.println("Flowid is: " + fid);
        	if (fid == 0)
        			return Command.CONTINUE;
        	ArrayList<Double> factor_arr = new ArrayList<> ();
//        	factor_arr = fid_factors.get(fid);
        	if(uniqueflows.contains(fid)) {
        	//	System.out.println("Has seen the flow"+ fid);
        		return Command.CONTINUE;
        	} else
        		uniqueflows.add(fid);
        	System.out.println("Flow id seen " + fid);
        	
        	if(fid > 2) {        		//shortest path
            	List<NodePortTuple> path = new ArrayList<> ();
            	path = shortestRoute(head.getNodeId(),tail.getNodeId());
            	path.add(0, head);
            	path.add(tail);   
            	System.out.println("fid: "+ fid+  " The back ground path is "+path);
        		//place routing rules
            	placeRouting(path, match);
        		return Command.CONTINUE;
        	}
        	
        	
        	/*
        	if (fid == 1) {
        		//8M return else comment this;
        		//return Command.STOP;
        	    
				List<Long> swchain = new ArrayList<>(Arrays.asList((long) 1, (long) 2,(long) 3, (long) 7,(long) 8));
				ArrayList<Double> factor_arry = new ArrayList<Double>(Arrays.asList(0.2, -0.2, 0.1, -0.3));
				Map<Long, ArrayList<Double>> sw_factor = new HashMap<> ();
				for (int i = 0; i < swchain.size(); i++) {
					if (swchain.get(i) == 3) {
						sw_factor.put(swchain.get(i), new ArrayList<Double>(Arrays.asList(0.2, -0.2, 0.1, -0.3)));
					} else {
						sw_factor.put(swchain.get(i), new ArrayList<Double>());
					}
				}
				
				List<NodePortTuple> path = new ArrayList<> ();
				path = icnp_getpath(swchain);
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
    				 
    			//place rules routing and middlebox
    			icnp_placerule(path, factor_arry, sw_factor, stod, dtos, eth, fid);
				
			} else if (fid == 2) {
				List<Long> swchain = new ArrayList<>(Arrays.asList((long) 9, (long) 8, (long) 7, (long) 3,(long) 2));
				ArrayList<Double> factor_arry = new ArrayList<Double>(Arrays.asList(-0.3, -0.2, 0.1, 0.2));
				Map<Long, ArrayList<Double>> sw_factor = new HashMap<> ();
				for (int i = 0; i < swchain.size(); i++) {
					if (swchain.get(i) == 9) {
						sw_factor.put(swchain.get(i), new ArrayList<Double>(Arrays.asList(-0.3, -0.2)));
					} else if (swchain.get(i) == 3){
						sw_factor.put(swchain.get(i), new ArrayList<Double>(Arrays.asList(0.1)));						
					} else if (swchain.get(i) == 2){
						sw_factor.put(swchain.get(i), new ArrayList<Double>(Arrays.asList(0.2)));
						
					} else {
						sw_factor.put(swchain.get(i), new ArrayList<Double>());
					}
				}
				
				List<NodePortTuple> path = new ArrayList<> ();
				path = icnp_getpath(swchain);
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
    				 
    			//place rules routing and middlebox
    			icnp_placerule(path, factor_arry, sw_factor, stod, dtos, eth, fid);
				
			} else {
				System.out.println("flow id are not recognized");
				return Command.CONTINUE;
			} */
        	 

        	
        	      	Snode solution = tosr(head.getNodeId(),tail.getNodeId(), fid, factor_arr);
        	      	     //     	Snode solution = heuristic(head.getNodeId(),tail.getNodeId(), fid, factor_arr);
        	if (solution == null) {
        		fail++;
        		System.out.println("Flow:" + fid + " failed and total fail is : " + fail);
        		return Command.STOP; 
        	}
        	
        	System.out.println("outside :" + factor_arr);
        	
        	System.out.println("ProcessPacketIN # of sws on the path:" + solution.path.size());
        	for(int t = 0; t < solution.path.size(); t++) {
        		System.out.println(solution.path.get(t));
        	}
        	List<NodePortTuple> path = new ArrayList<> ();
        	path = tosrpath(solution);
        	//compute path between switches
        	//path = widestRoute(head.getNodeId(),tail.getNodeId());
//        	path = lfgl(sw, context, (IPv4) pkt, srcAP[0].getSwitchDPID(), dstsw);
//        	path = shortestRoute(head.getNodeId(),tail.getNodeId(), fid);
//        	path = heuristic(head.getNodeId(),tail.getNodeId());
        	
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
        	
        	//install routing and mb rules over the path
        	firstPlace(path,factor_arr,stod,dtos,eth, solution.sw_mb, fid);
        } else {
        	return Command.CONTINUE;
        }
       return Command.CONTINUE;    	
    }
   
    // get current load of link(src_sw, dst_sw)
    public Double getLoad(Long src_sw, Long dst_sw) {
    	Set<Link> links = lds.getSwitchLinks().get(src_sw);

    	short port = 0;

    	for(Link link : links) {
    		
    		if (link.getSrc() == src_sw && link.getDst() == dst_sw) {
    			port = link.getSrcPort();
    		} 
    	}
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
        
        TOSR getOuterType() {
            return TOSR.this;
        }
    }
    
    //get sw ids with enough mb capacity, the rate enters sw, the mb_type on the sw
    ArrayList<Long> getmbNodes(double rate, int mb_type) {
    	ArrayList<Long> nodes = new ArrayList<Long> ();
    	for(int i = 0; i < 11; i++)
    		for(int j = 0; j < 4; j++)
    		{
    			if(j == mb_type && mb_capacity[i][j] >= rate)
    				//sw id starts from 1
    				nodes.add(Long.valueOf(i+1));
    		}
    	
    	return nodes;
    }
    
    //get mb types without dependency from the DAG 
    ArrayList<Integer> getNodependencyMbtype(HashMap<Integer, Integer> in_degree) {
    	ArrayList<Integer> mb_types = new ArrayList<> ();
    	for(Map.Entry<Integer, Integer> entry : in_degree.entrySet()) {
    		int key = entry.getKey();
    		int degree = entry.getValue();
    		if (degree == 0)
    			mb_types.add(key);
    	}
    	return mb_types;
    }
    
    //justify if two paths have duplicate nodes 
    boolean no_loop (List<Long> pre_path, List<Long> tail_path) {
    	boolean flag = false;  //false: no duplicate nodes, true: has duplicate nodes
    	tail_path.remove(0);
    	for(int i = 0; i < pre_path.size(); i++) {
    		for (int j = 0; j < tail_path.size(); j++) {
    			if(pre_path.get(i) == tail_path.get(j)) {
    				flag = true;
    				break;
    			}
    		}
    	}
    	return flag;
    }
    
    
    protected class NodeWeight implements Comparable<NodeWeight> {
        private final Long node;
        private final double weight;

        public Long getNode() {
            return node;
        }
       
        public double getWeight() {
            return weight;
        }
        


        public NodeWeight(Long node, double weight) {
            this.node = node;
            this.weight = weight;

            
        }

        @Override
        public int compareTo(NodeWeight o) {
            if (o.weight < this.weight) {
                return 1;
            } else if (o.weight > this.weight) {
            	return -1;
            } else
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
            NodeWeight other = (NodeWeight) obj;
            if (obj instanceof NodeWeight) {
            	if (((NodeWeight) obj).getNode() == this.node)
            		 return true;
            }
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

        private TOSR getOuterType() {
            return TOSR.this;
        }
    }
    
    
    protected static String ipToString(int ip) {
        return Integer.toString(U8.f((byte) ((ip & 0xff000000) >> 24)))
               + "." + Integer.toString((ip & 0x00ff0000) >> 16) + "."
               + Integer.toString((ip & 0x0000ff00) >> 8) + "."
               + Integer.toString(ip & 0x000000ff);
    }
    
    class Key {
    	public int layer;  //the layer of this sw
    	public long swid;  //the sw id of this sw
    	Key(int l, long s) {
    		layer = l;
    		swid = s;
    	}
    }
    
    class Snode {
    	public int layer;
    	public long swid, parent;
    	public double weight;  //sumweight from src to swid
    	public ArrayList<Long> path = new ArrayList<Long> ();; //sumpath from src to swid
    	public ArrayList<Long> sw_mb = new ArrayList<Long> (); //sw has mb pushed in order on the sumpath
    	
    	Snode(int l) {
    		this.layer = l;
    		this.path = new ArrayList<Long> ();
    		this.sw_mb = new ArrayList<Long> ();
    	}

    	
        public boolean equals(Object obj) {

            if (obj instanceof Snode) {
            	Snode e = (Snode) obj;
            	if(e.layer == this.layer && e.swid == this.swid 
            			&& e.parent == this.parent && e.weight == this.weight)
            		return true;
            	else 
            		return false;
            }
            
            if (this == obj)
                return true;
            if (obj == null)
                return false;
			return false;
        }

        @Override
        public int hashCode() {
            assert false : "hashCode not designed";
            return (int) (this.layer + this.parent + this.swid);
        }
    }
    
    public Snode heuristic(long srcSwitchId, long dstSwitchId, int flowid, ArrayList<Double> factor_arr) {
    	//layer, list of snodes
    	HashMap<Integer, ArrayList<Integer> > fid_dag = fid_dags.get(flowid);
    	
    	//in_degree init each mb type to 0
    	HashMap<Integer, Integer> in_degree = new HashMap<> ();
    	for(int i = 0; i < 4; i ++)
    		in_degree.put(i, 0);
    	
    	for(Map.Entry<Integer, ArrayList<Integer> > entry : fid_dag.entrySet()) {
    		for(int v : entry.getValue()) {
    			in_degree.put(v, in_degree.get(v)+1);
    		}
    	}
    	
    	Map<Integer, ArrayList<Snode> > solutions = new HashMap<> ();
    	for(int i = 0; i <= 4; i++) {
    		solutions.put(i, new ArrayList<Snode> ());
    	}
    	double rate = getInittran(flowid).t_s; //the initial traffic rate of the flow
    	Map<Integer, Boolean> type_used = new HashMap<> ();
    	for (int t = 0; t < 4; t++) 
    		type_used.put(t, false);
    	
    	for (int i = 0; i <=4 ; i++) {
    		ArrayList<Integer> mb_types = new ArrayList<Integer> ();
    		ArrayList<Long> sws_mb = new ArrayList<> (); //sws in layer i with available mbs
    		
    		if (i == 4) {
    			sws_mb.add(dstSwitchId);
    		} else {
    			mb_types = getNodependencyMbtype(in_degree);
        		for(int type : mb_types) {
        			sws_mb.addAll(getmbNodes(rate, type));
        		}
    		}

    		
    		if(sws_mb.size() == 0) {
    			System.out.println("flow: " + flowid + " has no mb capacity at layer: " + i);
    			return null;
    		}
    		
    		if (i ==0 ) {
    			Snode layer_node = heuristic_sub_short(srcSwitchId, sws_mb, i, solutions);
    			if (layer_node == null)
    				return null;
    			solutions.get(i).add(layer_node);
    			int erase_mb_type = 0;
    			for(int col = 0; col < 4; col++) {
    				if(mb_capacity[(int) layer_node.swid - 1][col] >= rate && mb_types.contains(col)  && type_used.get(col) == false) {
    					erase_mb_type = col;
    					type_used.put(col, true);
    					break;
    				}
    			}
    			
    			rate = rate * (1 + type_factor.get(erase_mb_type));
    			factor_arr.add(type_factor.get(erase_mb_type));
    			in_degree.remove(erase_mb_type);
//    			System.out.println("erase mb type： " + erase_mb_type +" layer: " + i);
    			//delete pre node, update post node's degree
    			for (Map.Entry<Integer, ArrayList<Integer>> entry : fid_dag.entrySet()) {
    				int key = entry.getKey();
    				if(key == erase_mb_type) {
    					for (Integer post : entry.getValue()) 
    						in_degree.put(post, in_degree.get(post)-1);
    				}
    			}
    		} else {
    			long newsrc = solutions.get(i-1).get(0).swid;
    			Snode layer_node = heuristic_sub_short(newsrc, sws_mb, i, solutions);
    			if (layer_node == null)
    				return null;
    			solutions.get(i).add(layer_node);
    			
    			if (i != 4) {
        			int erase_mb_type = 0;
        			for(int col = 0; col < 4; col++) {
        				if(mb_capacity[(int) layer_node.swid - 1][col] >= rate && mb_types.contains(col) && type_used.get(col) == false) {
        					erase_mb_type = col;
        					type_used.put(col, true);
        					break;
        				}
        			}
        			
        			rate = rate * (1 + type_factor.get(erase_mb_type));
        			factor_arr.add(type_factor.get(erase_mb_type));
        			in_degree.remove(erase_mb_type);
        			//delete pre node, update post node's degree
//        			System.out.println("erase mb type： " + erase_mb_type +" layer: " + i);
        			
        			for (Map.Entry<Integer, ArrayList<Integer>> entry : fid_dag.entrySet()) {
        				int key = entry.getKey();
        				if(key == erase_mb_type) {
        					for (Integer post : entry.getValue()) {
        						if(post != null)
        							in_degree.put(post, in_degree.get(post)-1);
        					}
        						
        				}
        			}
    			}
    		}
    	}
    	
    	ArrayList<Snode> final_solution = solutions.get(4);
    	if (final_solution.size() == 0) {
    		System.out.println("no solution node");
    		return null;
    	} else if (final_solution.size() > 1) {
    		System.out.println("Erro , multiple nodes at layer 4");
    		return null;
    	} else {
    		return final_solution.get(0);
    	}
    }
    
    public Snode heuristic_sub_short(long src, ArrayList<Long> dsts, int layer, 
    		Map<Integer, ArrayList<Snode>> solutions) {
//    	System.out.println("In SUBSHORT");
    	//initialization layer, swid and parent
    	Snode layer_node = new Snode(layer);
    	if (layer == 0) 
    		layer_node.parent = -1;
    	else
    		layer_node.parent = src;
    	
		ArrayList<Long> parent_path = new ArrayList<> ();
		if (layer > 0) {
			//layer == 0, parent_path is null
			for (Snode node : solutions.get(layer - 1)) {
				if (node.swid == src) {
					parent_path = node.path;
					break;
				}
			}
		}
		

    	switches = floodlightProvider.getAllSwitchMap();
		HashMap<Long, Link> nexthoplinks = new HashMap<Long, Link>();
        HashMap<Long, Double> cost = new HashMap<Long, Double>();
     

        //initialize
        for (Long node: switches.keySet()) {
            nexthoplinks.put(node, null);
            //nexthopnodes.put(node, null);
            cost.put(node, Double.MAX_VALUE);
        }

        HashMap<Long, Boolean> seen = new HashMap<Long, Boolean>();
        PriorityQueue<NodeWeight> nodeq = new PriorityQueue<NodeWeight>();
        nodeq.add(new NodeWeight(src, 0.0));
        cost.put(src, 0.0);
        double dstWeight = 0.0;
        long nearest_dst = 0;

        while (nodeq.peek() != null) {
        	
            NodeWeight n = nodeq.poll();
            Long cnode = n.getNode();
            //if dstswitch encountered hop out
            if(dsts.contains(cnode)) {
            	dstWeight = n.getWeight();
            	nearest_dst = cnode;
            	break;
            }
            double cdist = n.getWeight();
            if (cdist >= MAX_PATH_WEIGHT) break;
            if (seen.containsKey(cnode)) continue;
            seen.put(cnode, true);
            if (cnode != src && parent_path.contains(cnode)) continue; //avoid path loop 
            
           
    		for (Link link : lds.getSwitchLinks().get(cnode)) {
    			Long neighbor = link.getDst(); // skip links with cnode as dst
    			
    			// links directed toward cnode will result in this condition
    			if (neighbor.equals(cnode))
    				continue;

    			if (seen.containsKey(neighbor))
    				continue;

    		
    			// EIGRP standard
                double ndist = cdist + 10/(256 - getLoad(cnode, neighbor)/1000); 
                if (getLoad(cnode, neighbor)/1000 > 10)
                	continue;
                if (ndist < cost.get(neighbor)) {
                    cost.put(neighbor, ndist);
                    nexthoplinks.put(neighbor, link);
                    //nexthopnodes.put(neighbor, cnode);
                    NodeWeight ndTemp = new NodeWeight(neighbor, ndist);
                    // Remove an object that's already in there.
                    // Note that the comparison is based on only the node id,
                    // and not node id and distance.
                    nodeq.remove(ndTemp);
                    // add the current object to the queue.
                    nodeq.add(ndTemp);
                }
            }            
    }
    	
    	
		if (src == nearest_dst) {
			if(layer == 0) {
	    		layer_node.path.add(src);
	    		layer_node.sw_mb.add(src);
	    		layer_node.weight = 0.0;
	    		layer_node.swid = nearest_dst;
	    		return layer_node;
			}  else {
				for (Snode node : solutions.get(layer - 1)) {
					if (node.swid == src) {
						double weight = node.weight;
						layer_node.weight = weight;
						layer_node.path.addAll(node.path);
						layer_node.swid = nearest_dst;
						if (layer < 4) {
							layer_node.sw_mb.addAll(node.sw_mb);
							layer_node.sw_mb.add(nearest_dst);
						} else {
							layer_node.sw_mb.addAll(node.sw_mb);
						}

						break;
					}
					
				}
				return layer_node;
			}

		} else {
        	LinkedList<NodePortTuple> switchPorts = new LinkedList<NodePortTuple>();
        	
            //dstSwitchId will be changed in the following loop, use odstSwitchId to store the original data  
        	long dst = nearest_dst;
        	ArrayList<Long> subpath = new ArrayList<Long> (); 
        	boolean next = false;
        	if ((nexthoplinks!=null) && (nexthoplinks.get(dst)!=null)) {
        		next = true;
                while (dst!= src ) {
                	subpath.add(0, dst); //does not contain src
//                	System.out.println("subpath added:" + dst);
                    Link l = nexthoplinks.get(dst);
                    switchPorts.addFirst(new NodePortTuple(l.getDst(), l.getDstPort()));
                    switchPorts.addFirst(new NodePortTuple(l.getSrc(), l.getSrcPort()));
                    dst = l.getSrc();
                }
            }
        	if (next == false) {
        		layer_node = null;
        		return layer_node;
        	}
        	if (layer == 0) {
        		subpath.add(0, src);
            	layer_node.weight = dstWeight;
            	layer_node.path.addAll(subpath);
            	layer_node.sw_mb.add(nearest_dst);
            	layer_node.swid = nearest_dst;
//            	for (int t = 0; t < subpath.size(); t++) 
//            		System.out.println("intact path for layer 0 src: " + src + " dst: " 
//            	+ nearest_dst + " " + subpath.get(t));
            	return layer_node;
        	} else {
				
        		//may be deleted , flag used to justify if subpath has duplicate nodes with parent path
				for (Snode node : solutions.get(layer - 1)) {
					if (node.swid == src) {

						boolean flag = true;
						for(int t = 0; t < node.path.size() && flag; t++) {
							for(int l = 0; l < subpath.size(); l++)
							{
								if (node.path.get(t) == subpath.get(l))
								{
									flag = false;
									break;
								}
							}
						}
						
						if (flag) {
							layer_node.weight = node.weight + dstWeight;
							layer_node.path.addAll(node.path);
							layer_node.path.addAll(subpath);
							layer_node.swid = nearest_dst;
//							ArrayList<Long> temppath = node.path;
//							System.out.println("path of parent: " + node.swid + " current id: " + nearest_dst);
//							for(int m = 0; m < node.path.size(); m++)
//								System.out.println(node.path.get(m));
							if(layer !=4) {
								layer_node.sw_mb.addAll(node.sw_mb);
								layer_node.sw_mb.add(nearest_dst);
							} else {
								layer_node.sw_mb.addAll(node.sw_mb);
							}
//								till_sw_mb.add(orignal_dst);
//			            	for (int t = 0; t < layer_node.path.size(); t++) 
//			            		System.out.println("intact path for layer " + layer+ " src: " + src + " dst: " 
//			            	+nearest_dst + " " + layer_node.path.get(t));
//			            	System.out.println();
			            	return layer_node;
						} else {
							//if has duplicate nodes in the till path
							layer_node = null;
							return layer_node;
						}
					}
				}
				
        	}
        	
		} 
    	return layer_node;
    }  
    
    public Snode tosr_sub_short(long src, long dst, int layer, Map<Integer, ArrayList<Snode>> solutions) {
//    	System.out.println("In SUBSHORT");
    	long orignal_dst = dst;
    	//initialization layer, swid and parent
    	Snode layer_node = new Snode(layer);
    	if (layer == 0) 
    		layer_node.parent = -1;
    	else
    		layer_node.parent = src;
    	layer_node.swid = dst;
    	
		if (src == dst) {
			if(layer == 0) {
	    		layer_node.path.add(src);
	    		layer_node.sw_mb.add(src);
	    		layer_node.weight = 0.0;
	    		return layer_node;
			}  else {
				for (Snode node : solutions.get(layer - 1)) {
					if (node.swid == src) {
						double weight = node.weight;
						layer_node.weight = weight;
						layer_node.path.addAll(node.path);
						if (layer < 4) {
							layer_node.sw_mb.addAll(node.sw_mb);
							layer_node.sw_mb.add(dst);
						} else {
							layer_node.sw_mb.addAll(node.sw_mb);
						}

						break;
					}
					
				}
				return layer_node;
			}

		} else {
			ArrayList<Long> parent_path = new ArrayList<> ();
			if (layer > 0) {
				//layer == 0, parent_path is null
				for (Snode node : solutions.get(layer - 1)) {
					if (node.swid == src) {
						parent_path = node.path;
						break;
					}
				}
			}

        	switches = floodlightProvider.getAllSwitchMap();
    		HashMap<Long, Link> nexthoplinks = new HashMap<Long, Link>();
            HashMap<Long, Double> cost = new HashMap<Long, Double>();
         

            //initialize
            for (Long node: switches.keySet()) {
                nexthoplinks.put(node, null);
                //nexthopnodes.put(node, null);
                cost.put(node, Double.MAX_VALUE);
            }

            HashMap<Long, Boolean> seen = new HashMap<Long, Boolean>();
            PriorityQueue<NodeWeight> nodeq = new PriorityQueue<NodeWeight>();
            nodeq.add(new NodeWeight(src, 0.0));
            cost.put(src, 0.0);
            double dstWeight = 0.0;

            while (nodeq.peek() != null) {
            	
                NodeWeight n = nodeq.poll();
                Long cnode = n.getNode();
                //if dstswitch encountered hop out
                if(cnode == dst) {
                	dstWeight = n.getWeight();
                	break;
                }
                double cdist = n.getWeight();
                if (cdist >= MAX_PATH_WEIGHT) break;
                if (seen.containsKey(cnode)) continue;
                seen.put(cnode, true);
                if (cnode != src && parent_path.contains(cnode)) continue; //avoid path loop 
                
               
        		for (Link link : lds.getSwitchLinks().get(cnode)) {
        			Long neighbor = link.getDst(); // skip links with cnode as dst
        			
        			// links directed toward cnode will result in this condition
        			if (neighbor.equals(cnode))
        				continue;

        			if (seen.containsKey(neighbor))
        				continue;

        		
        			// EIGRP standard
                    double ndist = cdist + 10/(256 - getLoad(cnode, neighbor)/1000); 
                    //       if (getLoad(cnode, neighbor)/1000 > 10)
                    //        	continue;
                    if (ndist < cost.get(neighbor)) {
                        cost.put(neighbor, ndist);
                        nexthoplinks.put(neighbor, link);
                        //nexthopnodes.put(neighbor, cnode);
                        NodeWeight ndTemp = new NodeWeight(neighbor, ndist);
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
        	dst = orignal_dst;
        	ArrayList<Long> subpath = new ArrayList<Long> (); 
        	boolean next = false;
        	if ((nexthoplinks!=null) && (nexthoplinks.get(dst)!=null)) {
        		next = true;
                while (dst!= src ) {
                	subpath.add(0, dst);
//                	System.out.println("subpath added:" + dst);
                    Link l = nexthoplinks.get(dst);
                    switchPorts.addFirst(new NodePortTuple(l.getDst(), l.getDstPort()));
                    switchPorts.addFirst(new NodePortTuple(l.getSrc(), l.getSrcPort()));
                    dst = l.getSrc();
                }
            }
        	if (next == false) {
        		layer_node = null;
        		return layer_node;
        	}
        	if (layer == 0) {
        		subpath.add(0, src);
            	layer_node.weight = dstWeight;
            	layer_node.path.addAll(subpath);
            	layer_node.sw_mb.add(orignal_dst);
//            	for (int t = 0; t < subpath.size(); t++) 
//            		System.out.println("intact path for layer 0 src: " + src + " dst: " 
//            	+orignal_dst + " " + subpath.get(t));
            	return layer_node;
        	} else {
				
				for (Snode node : solutions.get(layer - 1)) {
					if (node.swid == src) {

						boolean flag = true;
						for(int t = 0; t < node.path.size() && flag; t++) {
							for(int l = 0; l < subpath.size(); l++)
							{
								if (node.path.get(t) == subpath.get(l))
								{
									flag = false;
									break;
								}
							}
						}
						
						if (flag) {
							layer_node.weight = node.weight + dstWeight;
							layer_node.path.addAll(node.path);
							layer_node.path.addAll(subpath);
//							ArrayList<Long> temppath = node.path;
//							System.out.println("path of parent: " + node.swid + " current id: " + orignal_dst);
//							for(int m = 0; m < node.path.size(); m++)
//								System.out.println(node.path.get(m));
							if(layer !=4) {
								layer_node.sw_mb.addAll(node.sw_mb);
								layer_node.sw_mb.add(orignal_dst);
							} else {
								layer_node.sw_mb.addAll(node.sw_mb);
							}
//								till_sw_mb.add(orignal_dst);
//			            	for (int t = 0; t < layer_node.path.size(); t++) 
//			            		System.out.println("intact path for layer " + layer+ " src: " + src + " dst: " 
//			            	+orignal_dst + " " + layer_node.path.get(t));
//			            	System.out.println();
			            	return layer_node;
						} else {
							//if has duplicate nodes in the till path
							layer_node = null;
							return layer_node;
						}
					}
				}
				
        	}
        	
		} 
    	return layer_node;
    }
    
    //lookahead1 order the mb based on the factor from small to great
    public ArrayList<Double> lookahead1(int fid) {
    	ArrayList<Double> factor_arr = new ArrayList<Double> ();
    	HashMap<Integer, ArrayList<Integer>> dag = fid_dags.get(fid);
    	HashMap<Double, ArrayList<Double>> factor_dag = new HashMap<Double, ArrayList<Double>> ();
    	HashMap<Double, Integer> in_degree = new HashMap<> ();
    	for(int i = 0; i < 4; i++) {
    		in_degree.put(type_factor.get(i), 0);
    	}
    	
    	for (Map.Entry<Integer, ArrayList<Integer>> entry : dag.entrySet()){
    		int node_key = entry.getKey();
     		double fnode_key = type_factor.get(node_key);
     		
    		ArrayList<Integer> node_adj = entry.getValue();
    		ArrayList<Double> temp = new ArrayList<Double> ();
    		for(Integer i : node_adj) {
    			temp.add(type_factor.get(i.intValue()));
    		}
    		factor_dag.put(fnode_key, new ArrayList<Double> (temp));
    		for(Double t : temp) {
    			in_degree.put(t, in_degree.get(t)+1);
    		}
    	}
    	
   
    	int loop = 4;
    	while (loop > 0) {
    		double key = 0.0;
    	 	double temp = Double.MAX_VALUE;
        	for (Map.Entry<Double, Integer> entry : in_degree.entrySet()) {
        		key = entry.getKey();
        		int degree = entry.getValue();
        		if(degree == 0 && key < temp) {
        			temp = key;
        		}
        	}
        	
        	for(Double t : factor_dag.get(temp)) {
        		in_degree.put(t, in_degree.get(t)-1);
        	}
        	in_degree.remove(temp);
        	factor_arr.add(temp);
        	
        	loop--;
    	}

    	return factor_arr;
    }
    public Snode tosr(long srcSwitchId, long dstSwitchId, int flowid, ArrayList<Double> factor_arr) {
    	//layer, list of snodes
    	Map<Integer, ArrayList<Snode> > solutions = new HashMap<> ();
    	for(int i = 0; i <= 4; i++) {
    		solutions.put(i, new ArrayList<Snode> ());
    	}
    	
    	//look ahead 1, sort mb into total order based on their ratios
    	factor_arr.addAll(lookahead1(flowid));
    	
    	ArrayList<Double> f_mbs = new ArrayList<Double> (factor_arr);
    	System.out.println("f_mbs:" + f_mbs);
    	double rate = getInittran(flowid).t_s; //the initial traffic rate of the flow
    	//layer 0,1,2,3,4 ; 4 should be the dst
    	for (int i = 0; i <=4; i++) {

    		if(i == 0) {
        		double mb_ratio = f_mbs.get(i);
        		int mb_type = factor_type.get(mb_ratio);
        		System.out.println("layer:" + i + " mb ratio: "+ mb_ratio + " mb_type: "+mb_type + " rate: "+ rate);
        		//get available sw with enough capacity for the mb_type
        		ArrayList<Long> sws_mb = getmbNodes(rate, mb_type);
        		if(sws_mb.size() == 0) {
        			System.out.println("flow: " + flowid + " has no mb capacity at layer: " + i);
        			return null;
        		}
        		ArrayList<Snode> snodes = new ArrayList<> ();
    			for (int j = 0; j < sws_mb.size(); j++) {
    				//get path and record mbs
//    				System.out.println("layer: " + i + " src :"+ srcSwitchId + " dst: " +
//    				sws_mb.get(j));
    				Snode llayer_node;
    				llayer_node = tosr_sub_short(srcSwitchId, sws_mb.get(j), i, solutions);
    				
    				if(llayer_node != null) {
    					snodes.add(llayer_node);
//    					System.out.println("layer: " + i + " src :"+ srcSwitchId + " dst: " +
//    		    				sws_mb.get(j)+" sub short path: ");
//    					for (long node : llayer_node.path) {
//    						System.out.print(node + " ");
//    					}
//    					System.out.print("\n");
//    					System.out.println("sub short mb sequence: ");
//    					for (long node : llayer_node.sw_mb) {
//    						System.out.print(node + " ");
//    					}
    				}
    				System.out.println();
    				//if weight is smaller, keep the current one into solutions
    			
    			}
    			if (snodes != null) 
    				solutions.get(i).addAll(snodes);
    			rate = rate * (1 + mb_ratio);
    			//print solution each layer
//    			for (Integer key : solutions.keySet()) {
//    				if(key == i) {
//    					
//    					for (Snode node : solutions.get(key)) {
//    						System.out.println("layer: "+ i + " src: " + node.parent+ " nodeid: " 
//    					+ node.swid + " verify path:") ;
//    						for (long sw : node.path) {
//    							System.out.print(sw + " ");
//    						}
//    						System.out.println();
//    						System.out.println("verify mb sequence: ");
//    						for (long sw : node.sw_mb) {
//    							System.out.println(sw);
//    						}
//    					} 
//    					break;
//    				}
//    			}
    		} else {

    			ArrayList<Long> parents = new ArrayList<> ();
    			for(Integer key : solutions.keySet()) {
    				//parent layer is : (i-1)
    				if (key == i-1) {
    					for (Snode node : solutions.get(key)) {
    						parents.add(node.swid);
    					}
    					break;
    				}
    			}
    			

        		//get available sw with enough capacity for the mb_type
        		ArrayList<Long> sws_mb = new ArrayList<> ();
    			//sws_mb contains all the sws with enough capacity in this layer i
//    			sws_mb.clear();
    			if (i == 4) {
    				sws_mb.add(dstSwitchId);
    			} else {
    				sws_mb = getmbNodes(rate, factor_type.get(f_mbs.get(i))); 
    			}
    			
        		if(sws_mb.size() == 0) {
        			System.out.println("flow: " + flowid + " has no mb capacity at layer: " + i);
        			return null;
        		}
        		

    			
    			ArrayList<Snode> slnodes = new ArrayList<> ();
    			for(int j = 0; j < sws_mb.size(); j++) {
    				Snode layer_node = null;
    				double tempweight  = Double.MAX_VALUE;
    				for (int k = 0; k < parents.size(); k++) {

   					if (i == 4 && parents.get(k) == dstSwitchId)
    						continue;
//    					System.out.println("tosr src :"+ parents.get(k) + " dst: " +sws_mb.get(j)+ " layer: " + i);
    					Snode temp_node;
    					temp_node = tosr_sub_short(parents.get(k), sws_mb.get(j), i, solutions);
//        				if(temp_node != null) {
//        					System.out.println("temp layer: " + i + " src :"+ parents.get(k) + " dst: " +
//        							sws_mb.get(j) + " sub short path:");
//        					for (long node : temp_node.path) {
//        						System.out.print(node + " ");
//        					}
//        					System.out.print("\n");
//        					System.out.println("sub short mb sequence: ");
//        					for (long node : temp_node.sw_mb) {
//        						System.out.print(node + " ");
//        					}
//        					System.out.print("\n" + "weight: " + temp_node.weight + "\n");
//        				}

    					//one node has k parents, just pick the one with minmal weight, presubpath has no effect 
    					//on the back path from the current node to the dst switch
    					if (temp_node != null && temp_node.weight < tempweight) {
    						tempweight = temp_node.weight;
    						layer_node = temp_node;
//    						System.out.println("parent: " + 
//    	    						temp_node.parent + " tempnode: " + temp_node.swid  + " sum weight: " + temp_node.weight);
    					}
    					
    				}
    				if (layer_node != null) {
//    					System.out.println("nodid:" + layer_node.swid+ " final parent: "+layer_node.parent + 
//    							" final weight: " + layer_node.weight);
    					slnodes.add(layer_node);
    				}
    					
    			}
    			if (slnodes.size() != 0)
    				solutions.get(i).addAll(slnodes);
    			if (i < 4) {
        			rate = rate * (1 + f_mbs.get(i));
    			}
//    			for (Integer key : solutions.keySet()) {
//    				if(key == i) {
//    					
//    					for (Snode node : solutions.get(key)) {
//    						System.out.println("layer: "+ i + " nodeid: " + node.swid + " parent: " + node.parent) ;
//    						for (long sw : node.path) {
//    							System.out.print(sw + " ");
//    						}
//    						System.out.println();
//    						System.out.println("mb sequence: ");
//    						for (long sw : node.sw_mb) {
//    							System.out.println(sw);
//    						}
//    					} 
//    					break;
//    				}
//    			}
    		}
    		

    	}
    	
    	//for layer 4, should has only one snode
    	ArrayList<Snode> final_solution = solutions.get(4);
//    	System.out.println("final snodes size: " + final_solution.size());
    	if (final_solution.size() == 0) {
    		System.out.println("no solution node");
    		return null;
    	} else if (final_solution.size() > 1) {
    		System.out.println("Error , multiple nodes at layer 4");
    		return null;
    	} else {
    		
    		return final_solution.get(0);
    	}
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

        private TOSR getOuterType() {
            return TOSR.this;
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
    	
    	stoTMatch.setDataLayerType(Ethernet.TYPE_IPv4).setNetworkSource(match.getNetworkSource())
				 .setNetworkDestination(match.getNetworkDestination());

    	dtoSMatch.setDataLayerType(Ethernet.TYPE_IPv4).setNetworkSource(match.getNetworkDestination())
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
}