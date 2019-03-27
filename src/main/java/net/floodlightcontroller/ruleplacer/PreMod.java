//Isaac A. Vawter, NSF REU FIU 2014
package net.floodlightcontroller.ruleplacer;

import org.openflow.protocol.OFMatch;

//Class created to contain the core elements for creating a flowmod:
public class PreMod {

	protected OFMatch match;
	protected short outPort;
	protected short priority;
	
	//Constructor that allows priority to be specified:
	public PreMod(OFMatch match, short outPort, short priority){
		this.match = match;
		this.outPort = outPort;
		this.priority = priority;
	}
	
	//Constructor with a default priority that is higher than Floodlight's default learning switch:
	public PreMod(OFMatch match, short outPort){
		this.match = match;
		this.outPort = outPort;
		this.priority = 10;
	}

	public OFMatch getMatch() {
		return match;
	}

	public void setMatch(OFMatch match) {
		this.match = match;
	}

	public short getOutPort() {
		return outPort;
	}

	public void setOutPort(short outPort) {
		this.outPort = outPort;
	}

	public short getPriority() {
		return priority;
	}

	public void setPriority(short priority) {
		this.priority = priority;
	}
	
}
