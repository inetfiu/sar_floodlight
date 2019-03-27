//Isaac A. Vawter, NSF REU FIU 2014

package net.floodlightcontroller.ruleplacer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.StringTokenizer;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.Wildcards;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Link;

//The TopologyRoutes class is used to create objects that generate shortest path routes based on the current network
//topology that is detected by the floodlight controller.
public class TopologyRoutes {
	
	protected IFloodlightProviderService flp;
	protected IDeviceService netDevices;
	protected ILinkDiscoveryService links;
	protected Map<Long, String> devAddresses;
	protected Map<Integer, SwitchPort> devOnSwitch;//IP, Switch, Port
	protected Map<Long, IOFSwitch> vertices;
	protected Map<List<Long>, Short[]> edges;
	protected Map<Long, ArrayList<PreMod>> routes;
	protected short[][] dist;
	protected int[][] midPoints;
	protected short[][] outPorts;

	public TopologyRoutes(FloodlightModuleContext context){
		flp = context.getServiceImpl(IFloodlightProviderService.class);
		netDevices = context.getServiceImpl(IDeviceService.class);
		links = context.getServiceImpl(ILinkDiscoveryService.class);
		devAddresses = new HashMap<Long, String>();
		devOnSwitch = new HashMap<Integer, SwitchPort>();
		vertices = flp.getAllSwitchMap();
		edges = new HashMap<List<Long>, Short[]>();
		routes = new HashMap<Long, ArrayList<PreMod>>();
		int switches = flp.getAllSwitchDpids().size();
		dist = new short[switches][switches]; 
		midPoints = new int[switches][switches];
		outPorts = new short[switches][switches];
		init();
	}
	
	private void init(){
		//Import addresses for end devices:
		try{
			Scanner reader = new Scanner(new BufferedReader(new FileReader("C:/Users/Isaac/Desktop/HostAddresses.txt")));
			while(reader.hasNextLine()){
				Long mac = (long) -1;
				String ip = "";
				String host = reader.nextLine();
				StringTokenizer addresses = new StringTokenizer(host, " ");
				while(addresses.hasMoreTokens()){
					String address = addresses.nextToken();
					if(address.equals("IPv4:")) ip = addresses.nextToken();
					if(address.equals("MAC:")) mac = Long.parseLong(addresses.nextToken());
				}
				devAddresses.put(mac, ip);
			}
			reader.close();
		} catch(Exception e){
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
		//Fill map of edges:
		Iterator<Link> linkIterator = links.getLinks().keySet().iterator();
		while(linkIterator.hasNext()){
			Link currLink = linkIterator.next();
			ArrayList<Long> ends = new ArrayList<Long>();
			ends.add(currLink.getSrc());
			ends.add(currLink.getDst());
			Short[] ports = {currLink.getSrcPort(), currLink.getDstPort()};
			edges.put(ends, ports);
		}
		//Fill map of end device addresses on switch port numbers:
		Iterator<? extends IDevice> endDevices = netDevices.getAllDevices().iterator();
		while(endDevices.hasNext()){
			IDevice currDev = endDevices.next();
			Integer ip = ipv4ToInt(devAddresses.get(currDev.getMACAddress()));
			if(currDev.getAttachmentPoints().length > 0){
				SwitchPort swPort = currDev.getAttachmentPoints()[0];
				devOnSwitch.put(ip, swPort);
			}
        }
		//Create route map entries for every switch:
		Iterator<Long> switches = flp.getAllSwitchDpids().iterator();
		while(switches.hasNext()){
			routes.put(switches.next(), new ArrayList<PreMod>());
		}
	}
	
	//Create a routing policy for the current topology:
	public Map<Long, ArrayList<PreMod>> routePolicy(){
		if(flp.getAllSwitchDpids().size() > 1){
			floydWarshall();
		}
		if(flp.getAllSwitchDpids().size() > 0){
			findOutPorts();
			generateRoutes();
			compressRules();
		}
		return routes;
	}
	
	//Floyd-Warshall Algorithm:
	private void floydWarshall(){
		//Initialize distance and midPoints array:
		for(int i = 0; i < dist.length; i++){
		for(int j = 0; j < dist.length; j++){
				midPoints[i][j] = -1;
				if(i == j){
					dist[i][j] = 0;
					outPorts[i][j] = -1;
				}
				else dist[i][j] = Short.MAX_VALUE;
			}
		}
		//Enter edge data into distance array:
		Iterator<List<Long>> edgeIterator = edges.keySet().iterator();
		while(edgeIterator.hasNext()){
			List<Long> currEnds = edgeIterator.next();
			dist[currEnds.get(0).intValue() - 1][currEnds.get(1).intValue() - 1] = (short) 1;
		}
		//Find shortest path distances and midpoints:
		for(int middle = 0; middle < dist.length; middle++){
			for(int src = 0; src < dist.length; src++){
				for(int dest = 0; dest < dist.length; dest++){
					if(dist[src][dest] > (dist[src][middle] + dist[middle][dest])){
						dist[src][dest] = (short) (dist[src][middle] + dist[middle][dest]);
						midPoints[src][dest] = middle;
					}
				}
			}
		}
	}
	
	//Fill outPorts array for each switch to reach each other switch:
	private void findOutPorts(){
		for(int src = 0; src < midPoints.length; src++){
			for(int dest = 0; dest < midPoints.length; dest++){
				if(src != dest){
					outPorts[src][dest] = getOutPort(src, dest);
				}
			}
		}
	}
	
	//Find output port for the src switch to reach the dest switch:
	private short getOutPort(int src, int dest){
		if(midPoints[src][dest] == -1){
			ArrayList<Long> neighbors = new ArrayList<Long>();
			neighbors.add((long) (src + 1));
			neighbors.add((long) (dest + 1));
			if(!edges.keySet().contains(neighbors)) return (short) -1;
			else return edges.get(neighbors)[0];
		}
		else return getOutPort(src, midPoints[src][dest]);
	}
	
	//Generate routes for the current topology:
	private void generateRoutes(){
		Iterator<Integer> ips = devOnSwitch.keySet().iterator();
		while(ips.hasNext()){
			Integer currIP = ips.next();
			routesForIP(currIP, devOnSwitch.get(currIP));
		}
	}
	
	//Method that condenses routing rules by creating subnet-based rules for each switch port:
	private void compressRules(){
		Map<Long, ArrayList<PreMod>> subRoutes = new HashMap<Long, ArrayList<PreMod>>();
		Iterator<Long> switches = routes.keySet().iterator();
		while(switches.hasNext()){
			Long dPID = switches.next();
			Map<Short, ArrayList<Integer>> ipsOnSwPort = new HashMap<Short, ArrayList<Integer>>();
			Iterator<PreMod> premods = routes.get(dPID).iterator();
			while(premods.hasNext()){
				PreMod currMod = premods.next();
				if(!ipsOnSwPort.containsKey(currMod.getOutPort())) ipsOnSwPort.put(currMod.getOutPort(), new ArrayList<Integer>());
				ipsOnSwPort.get(currMod.getOutPort()).add(currMod.getMatch().getNetworkDestination());
			}
			Map<Short, Integer[]> swPortSubRules= new HashMap<Short, Integer[]>();
			Iterator<Short> swPorts = ipsOnSwPort.keySet().iterator();
			while(swPorts.hasNext()){
				Short currPort = swPorts.next();
				//System.out.println("Switch " + dPID + " Port " + currPort + ":");
				swPortSubRules.put(currPort, subRule(ipsOnSwPort.get(currPort)));
			}
			if(!portRuleOverlap(swPortSubRules)){
				subRoutes.put(dPID, new ArrayList<PreMod>());
				Iterator<Short> ruleIterator = swPortSubRules.keySet().iterator();
				while(ruleIterator.hasNext()){
					Short currPort = ruleIterator.next();
					OFMatch rule = new OFMatch();
					rule.setDataLayerType((short) 0x0800);
					rule.setNetworkDestination(swPortSubRules.get(currPort)[0]);
					rule.setWildcards(Wildcards.FULL.withNwDstMask(swPortSubRules.get(currPort)[1])); //Set the CIDR to apply the rule to an entire subnet
					subRoutes.get(dPID).add(new PreMod(rule, currPort));
				}
			}
			else subRoutes.put(dPID, routes.get(dPID));
		}
		routes = subRoutes;
	}
	
	//Method that takes an ArrayList of ip addresses and finds their common subnet, returns and Integer array
	//that contains the subnet network address at index 0, and the subnet's CIDR at index 1:
	private Integer[] subRule(ArrayList<Integer> portIPs){
		Integer[] subAddr = new Integer[2];
		int cIDR = 32;
		for(int i = 0; i<cIDR; i++){
			Iterator<Integer> ips = portIPs.iterator();
			//Each IP address is bit shifted and stored in a new ArrayList:
			ArrayList<Integer> shifted = new ArrayList<Integer>();
			while(ips.hasNext()){
				Integer currIP = ips.next();
				shifted.add((currIP >> i));
			}
			//If all the bit shifted IP addresses are the same, they are contained in the same subnet and that subnet's
			//network address is returned along with it's network CIDR:
			Integer benchMark = shifted.get(0);
			if(Collections.frequency(shifted, benchMark) == shifted.size()){
				Double adjust = Math.pow((double) 2, (double) i); 
				subAddr[0] = benchMark * adjust.intValue();
				subAddr[1] = (cIDR - i);
				//System.out.println(IPv4.fromIPv4Address(subAddr[0]) + " / " + subAddr[1]);
				break;
			}
		}
		return subAddr;
	}
	
	//Method that determines if any two subnet rules for a switch overlap:
	private boolean portRuleOverlap(Map<Short, Integer[]> swRules){
		//Creates a list of all the subnets:
		ArrayList<Integer[]> subnets = new ArrayList<Integer[]>();
		Iterator<Short> rules = swRules.keySet().iterator();
		while(rules.hasNext()){
			subnets.add(swRules.get(rules.next()));
		}
		//Tests each pair of subnets for an overlap:
		boolean overlap = false;
		for(int i = (subnets.size()-1); i>=0; i--){
			if(!overlap){
				for(int j = (subnets.size()-1); j>=0; j--){
					if(i != j){
						//Tests for overlap by bit shifting both addresses to the right by the number of bits equal to
						//32 minus the number of significant bits in the subnet address with the least number of 
						//significant bits. If the two addresses are the same after the bitshift, they overlap.
						int smallCIDR;
						if(subnets.get(i)[1] < subnets.get(j)[1]) smallCIDR = (32 - subnets.get(i)[1]);
						else smallCIDR = (32 - subnets.get(j)[1]);
						int subIP1 = (subnets.get(i)[0] >> smallCIDR);
						int subIP2 = (subnets.get(j)[0] >> smallCIDR);
						if(subIP1 == subIP2){
							overlap = true;
							break;
						}
					}
				}
			}
		}
		//System.out.println("Overlap: " + overlap);
		return overlap;
	}
	
	//Create PreMod objects for each switch and place them into routing policy map:
	private void routesForIP(Integer ip, SwitchPort swPort){
		Long onSwitch = swPort.getSwitchDPID();
		Short outPort;
		for(int src = 0; src < outPorts.length; src++){
			if(src != (onSwitch.intValue() - 1)){
				 outPort = outPorts[src][onSwitch.intValue() - 1];
			}
			else outPort = (short) swPort.getPort();
			OFMatch rule = new OFMatch();
			rule.setDataLayerType((short) 0x0800); //Applies rule to IPv4 Packets
			rule.setNetworkDestination(ip);
			routes.get((long)(src + 1)).add(new PreMod(rule, outPort)); //Creates an object that stores the OFMatch and the port to forward the packet to. Can also specify rule priority.
		}
	}
	
	//Helper method that converts a string IP address into the integer equivalent:
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
