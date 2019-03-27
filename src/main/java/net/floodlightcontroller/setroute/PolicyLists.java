package net.floodlightcontroller.setroute;

import java.util.TreeMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class PolicyLists {
	
	protected Set<Long> seenHosts;
	protected Map<Integer, Short> hostOutport;
	
	public PolicyLists(){
		seenHosts = new TreeSet<Long>();
		hostOutport = new TreeMap<Integer, Short>();
	}

	public Set<Long> getSeenHosts() {
		return seenHosts;
	}

	public Map<Integer, Short> getHostOutport() {
		return hostOutport;
	}
	
	public void addHost(long mac){
		seenHosts.add(mac);
	}
	
	public void addRule(int ip, short outPort){
		hostOutport.put(ip, outPort);
	}

}
