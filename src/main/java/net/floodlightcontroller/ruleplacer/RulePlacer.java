//Isaac A. Vawter, NSF REU FIU 2014

package net.floodlightcontroller.ruleplacer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.Wildcards;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;
import net.floodlightcontroller.topology.ITopologyService;

//Floodlight module that writes flow rules to switches
public class RulePlacer implements IOFMessageListener, IFloodlightModule {
	
	protected IFloodlightProviderService flp; //Used to get switch information
	protected ITopologyService topo; //Used to get topology updates
	protected IStaticFlowEntryPusherService flowPusher; //Used to a flow to a switch
	protected ICounterStoreService cStore;
	protected static Logger logger;
	protected static Map<Long, ArrayList<PreMod>> switchRules; //Map of rules for each switch
	protected int nameChanger = 0; //Used to generate unique names for each flowmod
	protected Set<IOFSwitch> ruledSwitches = new HashSet<IOFSwitch>(); //Set of all switches that rules are being sent to
	protected NetPolicy policy;
	protected AtomicBoolean[] update;
	
	
	@Override
	public String getName() {
		return RulePlacer.class.getSimpleName();
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

	//Initializes module, fills switchRules map with hard-coded switch DPIDs, creates a stores hard-coded
	//static flows for each switch:
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		flp = context.getServiceImpl(IFloodlightProviderService.class);
		topo = context.getServiceImpl(ITopologyService.class);
		flowPusher = context.getServiceImpl(IStaticFlowEntryPusherService.class);
		cStore = context.getServiceImpl(ICounterStoreService.class);
	    logger = LoggerFactory.getLogger(RulePlacer.class);
	    policy = new NetPolicy(context);
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		flp.addOFMessageListener(OFType.PACKET_IN, this);
		policy.start();
	}

	/*This method is called every time the controller receives an OFMessage from a switch. This method
	 * stores observed switches in the ruledSwitches Set and creates a RulePusher object to update the
	 * switch with static flows.
	 */
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		OFPacketIn oFPI = (OFPacketIn) msg;
		OFMatch match = new OFMatch();
		match.loadFromPacket(oFPI.getPacketData(), oFPI.getInPort());
		if(match.getNetworkDestination() != 0 && !ruledSwitches.contains(sw)){
			RulePusher pusher = new RulePusher(sw);
			pusher.start();
			ruledSwitches.add(sw);		
		}
		
        return Command.CONTINUE;
	}
	
	//Method that uses the IStaticFlowEntryPusherService to write static flow rules to the switch
	private void launchOFFMod(PreMod preMod, IOFSwitch sw){	
		OFActionOutput flowPath = new OFActionOutput(preMod.getOutPort(), (short) -1); //Create flowmod action
		OFFlowMod staticRouteFlow = (OFFlowMod) flp.getOFMessageFactory().getMessage(OFType.FLOW_MOD); //Create flowmod
		staticRouteFlow.setCommand(OFFlowMod.OFPFC_ADD) //Set flowmod parameters
			.setIdleTimeout((short) 90) //Long timeouts are set so that flow statistics are consistent
			.setHardTimeout((short) 90)
			.setMatch(preMod.getMatch())
			.setPriority(preMod.getPriority())
			.setActions(Collections.singletonList((OFAction)flowPath))
			.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
		
		flowPusher.addFlow("myFlow"+nameChanger, staticRouteFlow, sw.getStringId()); //Push flowmod switch
		nameChanger++;
	}
	
	//Class that periodically writes static flows to the specified switch
	protected class RulePusher extends Thread {
		
		IOFSwitch sw;
		Long dPID;
		
		public RulePusher(IOFSwitch sw){
			this.sw = sw;
			dPID = sw.getId();
		}
		
		public void run(){
			rulePusher(sw);
		}
		
		//Method that periodically sends static flows to the switches. Sends flow twice and sleeps after each rule to increase 
		//probability that all flows will be received by the switch, however, improvements should be made so that all rules 
		//are written every time.
		private void rulePusher(IOFSwitch sw){
			long lastSent = 0;
			while(flp.getAllSwitchDpids().contains(dPID)){
				//Refreshes rules every minute or after every policy update
				if(lastSent <= (System.currentTimeMillis()-60000) || update[(dPID.hashCode() - 1)].get()){ 
					try{
						update[(int) (dPID - 1)].set(false);
						if(switchRules.keySet().contains(sw.getId())){
							//Creates a new list of rules to prevent iterator exceptions if an update occurs during iteration
							ArrayList<PreMod> swRules = switchRules.get(sw.getId());
							ArrayList<PreMod> rules = new ArrayList<PreMod>();
							int rule = 0;
							while(rule < swRules.size()){
								rules.add(swRules.get(rule));
								rule++;
							}
							for(int i = 0; i < 2; i++){
							Iterator<PreMod> ruleIterator = switchRules.get(sw.getId()).iterator();
								while(ruleIterator.hasNext()){
									PreMod currPM = ruleIterator.next();
									launchOFFMod(currPM, sw);
									try{
										Thread.sleep(100);
									}catch (InterruptedException e) {System.out.println( "awakened prematurely" );}
								}
							}
							lastSent = System.currentTimeMillis();
						}
					}catch (Exception e) {e.printStackTrace();}
				}
				try{
					Thread.sleep(2000);
				}catch (InterruptedException e) {System.out.println( "awakened prematurely" );}	
			}
		}
	}
	
	//Class that generates the traffic handling policy based off the topology:
	protected class NetPolicy extends Thread {
		
		private FloodlightModuleContext context; //Used to access Floodlight services
		private TopologyRoutes routes;
		private Date lastUpdate;
		
		public NetPolicy(FloodlightModuleContext context){
			this.context = context;
			lastUpdate = new Date((long) 0);
		}
		
		public void run(){
			topoListener();
		}
		
		private void topoListener(){
			while(true){
				try{
					//Runs if a topology update has occurred:
					if(topo.getLastUpdateTime() != null && topo.getLastUpdateTime().getTime() > lastUpdate.getTime()){
						lastUpdate = topo.getLastUpdateTime();
						update = new AtomicBoolean[flp.getAllSwitchDpids().size()];
						for(int i = 0; i<update.length; i++){
							update[i] = new AtomicBoolean(false);
						}
						changeStop(lastUpdate); //Used to wait until topology has finished updating
						System.out.println(topo.getLastUpdateTime().toString());
						routes = new TopologyRoutes(context); //Creates a new TopologyRoutes object for the current topology
						switchRules = routes.routePolicy(); //Uses the TopologyRoutes object to create an initial routing policy
						endPointPolicy(); //Applies endpoint policy
						for(int i = 0; i<update.length; i++){
							update[i].set(true);
						}
					}
					try{
						Thread.sleep(1000);
					}catch (InterruptedException e) {System.out.println( "awakened prematurely" );}
				}catch (Exception e) {e.printStackTrace();}
			}
		}
		
		//Method that waits until the topology has finished updating:
		private void changeStop(Date lastUpdate){
			try{
				Thread.sleep(3000);
			}catch (InterruptedException e) {System.out.println( "awakened prematurely" );}
			if(lastUpdate == topo.getLastUpdateTime()) return;
			else changeStop(topo.getLastUpdateTime());
		}
		
		//This method is designed to provide a method for implementing an endpoint policy, it is currently
		//set up to hardcode rules for a specific switch:
		private void endPointPolicy(){
			for(Long dPID = (long) 1; dPID <= flp.getAllSwitchDpids().size(); dPID++){
		    	switch(dPID.intValue()){
				case 1:{
					/*ArrayList<PreMod> swRules = switchRules.get(dPID);
					OFMatch rule = new OFMatch();
					rule.setDataLayerType((short) 0x0800);
					rule.setNetworkSource(ipv4ToInt("10.0.0.4"));
					rule.setWildcards(Wildcards.FULL.withNwSrcMask(30)); //Set the CIDR to apply the rule to an entire subnet
					swRules.add(new PreMod(rule, (short) 0, (short) 5));
	    			break;
				}
				case 2:{
					//ArrayList<PreMod> swRules = switchRules.get(dPID);
					break;
				}
				case 3:{
					//ArrayList<PreMod> swRules = switchRules.get(dPID);
					break;
				}
				//...
				case 8:{
					ArrayList<PreMod> swRules = switchRules.get(dPID);
					OFMatch rule = new OFMatch();
					rule.setDataLayerType((short) 0x0800);
					rule.setNetworkSource(ipv4ToInt("10.0.0.4"));
					rule.setWildcards(Wildcards.FULL.withNwSrcMask(30)); //Set the CIDR to apply the rule to an entire subnet
					swRules.add(new PreMod(rule, (short) 0, (short) 5));*/
	    			break;
				}
				//...
		    	}	
		    	System.out.println("Number of Rules for Switch " + dPID + ": " + switchRules.get(dPID).size());
		    }
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
	
	public static Map<Long, ArrayList<PreMod>> getRoutes(){
		return switchRules;
	}
	
}
