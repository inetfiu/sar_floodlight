//Isaac A. Vawter, NSF REU FIU 2014

package net.floodlightcontroller.topostats;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.io.*;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.ruleplacer.PreMod;
import net.floodlightcontroller.ruleplacer.RulePlacer;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//Floodlight module that writes switch statistics to text file
public class TopoStats implements IOFMessageListener, IFloodlightModule {
	
	/*Instance variables that include floodlight services that provide the network topology and 
	 * identifiers for specific network devices. The variables lastUpdate, nextStats, and switches
	 * are used to pull statistics from each switch in the network.
	 */
	protected IFloodlightProviderService flp; //Used to get switch information
	protected IDeviceService netDevices; //Used to get host information
	protected ILinkDiscoveryService links; //Used to observe all physical connections
	protected ICounterStoreService cStore;
	protected RulePlacer rules;
	protected static Logger logger;
	protected Map<Long, Long> lastUpdate; //Used to prevent too many statistic requests.
	protected ArrayList<IOFSwitch> switches; //Used to keep track of observed switches.
	protected Map<Long, Map<Short, Long>> swLinks;//Map of a links from a src switch to dest switch and src port
	protected long startTime; //Used to track the relative time of each update.
	//protected Map<Long, ArrayList<PreMod>> switchRules;


	@Override
	public String getName() {
		return TopoStats.class.getSimpleName();
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

	/* This method is automatically called when floodlight initializes its modules. All services and instance
	 * variables are initialized.
	 */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		flp = context.getServiceImpl(IFloodlightProviderService.class);
		netDevices = context.getServiceImpl(IDeviceService.class);
		links = context.getServiceImpl(ILinkDiscoveryService.class);
		cStore = context.getServiceImpl(ICounterStoreService.class);
	    logger = LoggerFactory.getLogger(TopoStats.class);
	    lastUpdate = new HashMap<Long, Long>();
	    switches = new ArrayList<IOFSwitch>();
	    swLinks = new HashMap<Long, Map<Short, Long>>();
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		flp.addOFMessageListener(OFType.PACKET_IN, this);
	}

	/*This method is called every time the controller receives an OFMessage from a switch. This method
	 * creates text files that report the topology and statistics of the network.
	 */
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		/*if(switchRules == null || !switchRules.equals(RulePlacer.getRoutes())){
			switchRules = RulePlacer.getRoutes();
		}*/
		if(startTime == 0){
			startTime = (System.currentTimeMillis()/1000);
		}
		//A switch previously unknown to this module is added to an ArrayList. A StatRequester
		//object is created to periodically request statistics from the switch.
		if(!switches.contains(sw)){
			switches.add(sw);
			swLinks.put(sw.getId(), new HashMap<Short, Long>());
			StatRequester nextStats = new StatRequester(sw);
	        nextStats.start();
		}
        
		//This section of the method pulls topology data from the IFloodlightProviderService and the 
		//IDeviceService and writes them to a specified text file.
        Iterator<Link> allLinks = links.getLinks().keySet().iterator();
        Iterator<IOFSwitch> allSwitchPorts = flp.getAllSwitchMap().values().iterator();
        ArrayList<String> switchNames = new ArrayList<String>();
        Iterator<? extends IDevice> devices = netDevices.getAllDevices().iterator();
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter("/home/ivawt001/Desktop/file5.txt"));
            while(allSwitchPorts.hasNext()){
            	IOFSwitch currSwitch = allSwitchPorts.next();
            	switchNames.add(currSwitch.getStringId());
            	out.write("Switch " + HexString.toHexString(currSwitch.getId()) + "\n" + "Enabled Ports: ");
            	Iterator<Short> ports = currSwitch.getEnabledPortNumbers().iterator();
            	while(ports.hasNext()){
            		out.write(ports.next() + " ");
            	}
            	out.write("\n" + "\n");
            }
            out.write("\n" + "\n");
            while(allLinks.hasNext()){
            	Link currLink = allLinks.next();
            	out.write(currLink.toString() + "\n");
            }
            out.write("\n" + "\n");
            while(devices.hasNext()){
            	out.write(devices.next() + "\n");
            }
            out.close(); 
        } catch (IOException e) {e.printStackTrace();}
        
        return Command.CONTINUE;
	}
	
	/* The StatRequester class is used to create threads that periodically send statistic requests to 
	 * each switch on the network. Uses Future objects to allow for delay when waiting for responses
	 * from the switches. Records information from statistic replies in a specified text file. 
	 */
	protected class StatRequester extends Thread {
		
		protected IOFSwitch sw; //The switch this StatRequester object corresponds with
		protected Map<Integer, Long> flowBytes; //Used to estimate individual flow traffic rate
		protected Map<Short, Long> portBytes;
		protected Map<Integer, Double> bitRates; // Individual flow traffic rates, recorded in Mbps
		protected Map<Short, Double> linkLoad; //The link load over the links leading to this switch, recorded in Mbps
		protected Long dPID; //Switch ID/MAC address
		
		//Constructor method that also specifies the text file that switch statistics will be written to.
		//Text file is appended, not rewritten.
		public StatRequester(IOFSwitch sw){
			super();
			this.sw = sw;
			dPID = sw.getId();
			flowBytes = new HashMap<Integer, Long>();
			portBytes = new HashMap<Short, Long>(); //Number of bytes a port has sent
			linkLoad = new HashMap<Short, Double>(); //Peak link load measured for a port number
			bitRates = new HashMap<Integer, Double>(); //Size is equal to the number of flows that are having their traffic rate measured
    		try{
    			BufferedWriter flowData = new BufferedWriter(new FileWriter("/home/ivawt001/Desktop/S" + 
        				dPID.intValue() + "FlowData.txt", false)); //Change path to the file data is to be printed to, overwrites previous file
    			flowData.write("S" + dPID.intValue() + "\n");
    			flowData.close();
    			BufferedWriter loadData = new BufferedWriter(new FileWriter("/home/ivawt001/Desktop/S" + 
        				dPID.intValue() + "LoadData.txt", false)); //Change path to the file data is to be printed to, overwrites previous file
    			loadData.write("S" + dPID.intValue() + "\n");
    			loadData.close();
    		}catch(IOException e){e.printStackTrace();}

		}
		
		public void run(){
			getFlows();
		}
		
		//Method that gathers flow data from switches by periodically sending OFStatisticsRequests
		public void getFlows() {
			
			//Adds switch lastUpdate map
			if (!lastUpdate.containsKey(dPID)){
				lastUpdate.put(dPID, System.currentTimeMillis());
				getFlows();
			}
			
			//Create objects for gathering flow statistics from switch
			List<OFStatistics> flowValues = null;
			Future<List<OFStatistics>> flowFuture = null;
			OFStatisticsRequest flowReq = new OFStatisticsRequest();  // Change to your statistics type here, and modify the following variables(eg. remove outPort since that's flow stat specific)
			flowReq.setStatisticType(OFStatisticsType.FLOW); 
			flowReq.setXid(sw.getNextTransactionId());
			int flowReqLength = flowReq.getLengthU();
			
			//Create request objects
			OFFlowStatisticsRequest specificFlowReq = new OFFlowStatisticsRequest();
			specificFlowReq.setMatch(new OFMatch().setWildcards(0xffffffff));
			specificFlowReq.setOutPort(OFPort.OFPP_NONE.getValue());
			specificFlowReq.setTableId((byte) 0xff);
			
			flowReq.setStatistics(Collections.singletonList((OFStatistics)specificFlowReq));
			flowReqLength += specificFlowReq.getLength();
			flowReq.setLengthU(flowReqLength);   
			

			//Send request to switch and wait for response, write switch name to text file
			try {
				flowFuture = sw.queryStatistics(flowReq);
				flowValues = flowFuture.get(10, TimeUnit.SECONDS);
			    if(flowValues != null && !flowValues.isEmpty()){
			    	BufferedWriter bw = new BufferedWriter(new FileWriter("/home/ivawt001/Desktop/S" + 
		    				dPID.intValue() + "FlowData.txt", true)); //Change path to target the flow data file, the file will be appended
		    		printStats(bw, flowValues);
			   }
			} catch (Exception e) {
				System.out.println("Failure retrieving flow statistics from switch " + sw.getStringId());
				e.printStackTrace();
			} 
			
			//Repeat similar steps as before to create a port statistics request and record link Mbps
			List<OFStatistics> portValues = null;
			Future<List<OFStatistics>> portFuture = null;
			OFStatisticsRequest portReq = new OFStatisticsRequest();
			portReq.setStatisticType(OFStatisticsType.PORT); // Change to your statistics type here, and modify the following variables(eg. remove outPort since that's flow stat specific)
			portReq.setXid(sw.getNextTransactionId());
			int portReqLength = portReq.getLengthU();
			
			OFPortStatisticsRequest specificPortReq = new OFPortStatisticsRequest();
			specificPortReq.setPortNumber(OFPort.OFPP_NONE.getValue());
			
			portReq.setStatistics(Collections.singletonList((OFStatistics)specificPortReq));
			portReqLength += specificPortReq.getLength();
			portReq.setLengthU(portReqLength);  
				
			try {
				portFuture = sw.queryStatistics(portReq);
				portValues = portFuture.get(10, TimeUnit.SECONDS);
			    if(portValues != null && !portValues.isEmpty()){
			    	Iterator<OFStatistics> portStats = portValues.iterator();
			        while(portStats.hasNext()){
			        	OFPortStatisticsReply currStats = (OFPortStatisticsReply) portStats.next();
			        	if(!portBytes.keySet().contains(currStats.getPortNumber())){
			        		portBytes.put(currStats.getPortNumber(), (long) 0); 
			        		linkLoad.put(currStats.getPortNumber(), (double) 0); 
			        	}
			        	//Stores load data in maps
			        	double currLinkLoad = ((currStats.getTransmitBytes() - portBytes.get(currStats.getPortNumber()))*8/((double) 1000000));
			        	linkLoad.put(currStats.getPortNumber(), currLinkLoad);
			        	portBytes.put(currStats.getPortNumber(), currStats.getTransmitBytes());
			        }
			        printLoadData(); //Prints data to load data file
			   }
			} catch (Exception e) {
				System.out.println("Failure retrieving port statistics from switch " + sw.getStringId() + e.getMessage());
			} 
				
			//Suspend operation for a set period to avoid sending too many statistics requests
			try{
				Thread.sleep(1000);
			   }catch (InterruptedException e) {System.out.println( "awakened prematurely" );}
			
			if (System.currentTimeMillis() > (lastUpdate.get(dPID) + 1000)){
	        	lastUpdate.put(dPID, System.currentTimeMillis());
	        }
			if(flp.getAllSwitchDpids().contains(sw.getId())){
				getFlows();
			}
		}
		
		//Method that formats data and specifies which flow statistics are recorded
		public void printStats(BufferedWriter bw, List<OFStatistics> values){
			try {
		           bw.write("Time;" + ((System.currentTimeMillis()/1000)-startTime)); //Prints seconds since the module was initialized
		           Iterator<OFStatistics> newStats = values.iterator();
		           while(newStats.hasNext()){
		        	   OFFlowStatisticsReply currStats = (OFFlowStatisticsReply) newStats.next();
		        	   OFMatch currMatch = currStats.getMatch();
		        	   Iterator<PreMod> swRules = RulePlacer.getRoutes().get(dPID).iterator();
		        	   while(swRules.hasNext()){
		        		   PreMod currRule = swRules.next();
		        		   int currRuleIP = currRule.getMatch().getNetworkDestination();
		        		   if(currMatch.getNetworkDestination() == currRuleIP){ //Records flow data if it matches a traffic handling rule
			        		   bw.write(";Flow;IP Traffic to " + currRule.getMatch().getNetworkDestinationCIDR()); //Prints the flow destination
			        		   updateStats(currMatch.getNetworkDestination(), currStats, bw); //Prints additional statistics
			        	   }
		        		   
		        	   }
		           }
		           bw.write("\n");
		           bw.close();
		       } catch (IOException e) {e.printStackTrace();}
		}
		
		//Method that formats the statistics being printed to the text file, updates recorded data,
		//writes byte count, packet count, and bits per second received by switch to the text file
		protected void updateStats(Integer flow, OFFlowStatisticsReply currStats, BufferedWriter bw){
			if(!flowBytes.containsKey(flow)){
				flowBytes.put(flow, (long) 0);
			}
			bitRates.put(flow, (currStats.getByteCount() - flowBytes.get(flow))*8/((double) 1000000));
			flowBytes.put(flow, currStats.getByteCount());
			try{
				bw.write(",Bytes," + currStats.getByteCount() +
			        	 ",Packets," + currStats.getPacketCount() +
			        	 ",Current Mbps," + String.format("%.3f", bitRates.get(flow)));
			} catch (IOException e) {e.printStackTrace();}
		}
		
		//Method that prints load data after each port statistics request, prints destination switch, output port, total bytes
		//transmitted and link load since the last port statistics request:
		protected void printLoadData(){
			try{
				BufferedWriter bw = new BufferedWriter(new FileWriter("/home/ivawt001/Desktop/S" + 
						dPID.intValue() + "LoadData.txt", true)); //Change so the path targets the load data file, the file will be appended
				bw.write("Time;" + ((System.currentTimeMillis()/1000)-startTime)); //Prints seconds since the module was initialized
				Iterator<Short> ports = portBytes.keySet().iterator();
				while(ports.hasNext()){ //Iterates through every active port on switch
					Short port = ports.next();
					Iterator<Link> swLinks = links.getSwitchLinks().get(dPID).iterator();
					while(swLinks.hasNext()){ //Iterates through all the links to find the destination switch
						Link currLink = swLinks.next();
						if(currLink.getSrcPort() == port){
							bw.write(";Dest-Switch;" + currLink.getDst() + "," + 
									 "Port," + port + "," +
									 "Bytes," + portBytes.get(port) + "," +
								     "Current Link Load," + String.format("%.3f", linkLoad.get(port)));
						}
					}
				}
				bw.write("\n");
				bw.close();
			} catch (IOException e) {e.printStackTrace();}
		}

	}
	 
}