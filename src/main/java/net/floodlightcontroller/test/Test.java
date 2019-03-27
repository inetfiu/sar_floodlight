package net.floodlightcontroller.test;

//Test dl_action : set_src

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.List;


import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionDataLayerSource;
import org.openflow.protocol.action.OFActionOutput;


import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;
import net.floodlightcontroller.topology.ITopologyService;

public class Test implements IFloodlightModule, IOFMessageListener {
	
	protected IFloodlightProviderService floodlightProvider;
    protected IStaticFlowEntryPusherService sfpService;
    protected ITopologyService topologyService;
	protected IDeviceService deviceManagerService;
	
	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return "test";
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return (type.equals(OFType.PACKET_IN) && (name.equals("topology") || name.equals("devicemanager")));
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return (type.equals(OFType.PACKET_IN) && (name.equals("forwarding")));
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        switch (msg.getType()) {
        case PACKET_IN:
            return processPacketIn(sw, (OFPacketIn)msg, cntx);
        default:
            break;    
            }
        
        return Command.STOP;
		
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
	        l.add(IStaticFlowEntryPusherService.class);
			l.add(ITopologyService.class);
			l.add(IDeviceService.class);
	        return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        sfpService = context.getServiceImpl(IStaticFlowEntryPusherService.class);
		topologyService = context.getServiceImpl(ITopologyService.class);
		deviceManagerService = context.getServiceImpl(IDeviceService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
		 floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
	}
	
	
    private net.floodlightcontroller.core.IListener.Command processPacketIn(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
        
        Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
        IPacket pkt = eth.getPayload(); 

        
        if(pkt instanceof ARP){
        	
        	//forwarding module handle all ARP message
        	return Command.CONTINUE;
        }
        
        if (pkt instanceof IPv4) {
        	
        	OFMatch match_1 = new OFMatch();
        	match_1.setInputPort((short) 1);
        	
        	List<OFAction> actions1 = new ArrayList<> ();
        	OFActionOutput action1 = new OFActionOutput((short) 3);
        	actions1.add(action1);

        	
        	OFFlowMod flow_1 = 
        			(OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
        	flow_1.setCommand(OFFlowMod.OFPFC_ADD).setIdleTimeout((short) 0)
        		  .setHardTimeout((short) 0)
        		  .setMatch(match_1)
        		  .setPriority((short) 200)
        		  .setActions(actions1)
        		  .setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
        	
        	sfpService.addFlow("flow-1", flow_1, sw.getStringId());
        	System.out.println("Flow-1 pushed");
        	
        	
        	OFMatch match_2 = new OFMatch();
        	match_1.setInputPort((short) 3);
        	
        	List<OFAction> actions2 = new ArrayList<> ();
        	OFActionDataLayerSource action2_1 = new OFActionDataLayerSource();
        	action2_1.setDataLayerAddress("00:00:00:00:00:01".getBytes());
        	OFActionOutput action2_2 = new OFActionOutput((short) 2);
        	actions2.add(action2_1);
        	actions2.add(action2_2);

       	
        	OFFlowMod flow_2 = 
        			(OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
        	flow_2.setCommand(OFFlowMod.OFPFC_ADD).setIdleTimeout((short) 0)
        		  .setHardTimeout((short) 0)
        		  .setMatch(match_2)
        		  .setPriority((short) 200)
        		  .setActions(actions2)
        		  .setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH + OFActionDataLayerSource.MINIMUM_LENGTH));
        	
        	sfpService.addFlow("flow-2", flow_2, sw.getStringId());
        	System.out.println("Flow-2 pushed");
        	
        	OFMatch match_3 = new OFMatch();
        	match_3.setInputPort((short) 2);
        	
        	List<OFAction> actions3 = new ArrayList<> ();
        	OFActionOutput action3 = new OFActionOutput((short) 2);
        	actions3.add(action3);

        	
        	OFFlowMod flow_3 = 
        			(OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
        	flow_3.setCommand(OFFlowMod.OFPFC_ADD).setIdleTimeout((short) 0)
        		  .setHardTimeout((short) 0)
        		  .setMatch(match_3)
        		  .setPriority((short) 200)
        		  .setActions(actions3)
        		  .setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
        	
        	sfpService.addFlow("flow-3", flow_3, sw.getStringId());
        	
           	System.out.println("Flow-3 pushed");
        	return Command.STOP;
        }
        
       return Command.STOP;
    }

}
