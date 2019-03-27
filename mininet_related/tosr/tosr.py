#!/usr/bin/python
# each switch connects to four mbs

from mininet.net import Mininet
from mininet.node import Controller, RemoteController, OVSController
from mininet.node import CPULimitedHost, Host, Node
from mininet.node import OVSKernelSwitch, UserSwitch
from mininet.node import IVSSwitch
from mininet.cli import CLI
from mininet.log import setLogLevel, info
from mininet.link import TCLink, Intf
from subprocess import call
import time

def myNetwork():

    net = Mininet( topo=None,
                   build=False,
                   ipBase='10.0.0.0/8')

    info( '*** Adding controller\n' )
    c0=net.addController(name='c0',
                      controller=RemoteController,
                      ip='192.168.56.1',
                      port=6633)

    info( '*** Add switches\n')
    s2 = net.addSwitch('s2', cls=OVSKernelSwitch)
    s3 = net.addSwitch('s3', cls=OVSKernelSwitch)
    s4 = net.addSwitch('s4', cls=OVSKernelSwitch)
    s5 = net.addSwitch('s5', cls=OVSKernelSwitch)
    s1 = net.addSwitch('s1', cls=OVSKernelSwitch)
    s6 = net.addSwitch('s6', cls=OVSKernelSwitch)
    s7 = net.addSwitch('s7', cls=OVSKernelSwitch)
    s8 = net.addSwitch('s8', cls=OVSKernelSwitch)
    s9 = net.addSwitch('s9', cls=OVSKernelSwitch)
    s10 = net.addSwitch('s10', cls=OVSKernelSwitch)
    s11 = net.addSwitch('s11', cls=OVSKernelSwitch)



    info( '*** Add hosts\n')
    h1 = net.addHost('h1', cls=Host, mac='00:00:00:00:00:01',ip='10.0.1.1', defaultRoute=None)
    h2 = net.addHost('h2', cls=Host, mac='00:00:00:00:00:02',ip='10.0.1.2', defaultRoute=None)
    h3 = net.addHost('h3', cls=Host, mac='00:00:00:00:00:03',ip='10.0.1.3', defaultRoute=None)
    h4 = net.addHost('h4', cls=Host, mac='00:00:00:00:00:04',ip='10.0.1.4', defaultRoute=None)    
    h5 = net.addHost('h5', cls=Host, mac='00:00:00:00:00:05',ip='10.0.1.5', defaultRoute=None)
    h6 = net.addHost('h6', cls=Host, mac='00:00:00:00:00:06',ip='10.0.1.6', defaultRoute=None)
    
    h7 = net.addHost('h7', cls=Host, mac='00:00:00:00:00:07',ip='10.0.2.7', defaultRoute=None)
    h8 = net.addHost('h8', cls=Host, mac='00:00:00:00:00:08',ip='10.0.2.8', defaultRoute=None) 
    h9 = net.addHost('h9', cls=Host, mac='00:00:00:00:00:09',ip='10.0.2.9', defaultRoute=None)
    h10 = net.addHost('h10', cls=Host, mac='00:00:00:00:00:10',ip='10.0.2.10', defaultRoute=None)
 
    h11 = net.addHost('h11', cls=Host, mac='00:00:00:00:00:11',ip='10.0.2.11', defaultRoute=None)
    h12 = net.addHost('h12', cls=Host, mac='00:00:00:00:00:12',ip='10.0.2.12', defaultRoute=None) 
    h13 = net.addHost('h13', cls=Host, mac='00:00:00:00:00:13',ip='10.0.3.13', defaultRoute=None)
    h14 = net.addHost('h14', cls=Host, mac='00:00:00:00:00:14',ip='10.0.3.14', defaultRoute=None)

    h15 = net.addHost('h15', cls=Host, mac='00:00:00:00:00:15',ip='10.0.3.15', defaultRoute=None)
    h16 = net.addHost('h16', cls=Host, mac='00:00:00:00:00:16',ip='10.0.3.16', defaultRoute=None)
    h17 = net.addHost('h17', cls=Host, mac='00:00:00:00:00:17',ip='10.0.4.17', defaultRoute=None)
    h18 = net.addHost('h18', cls=Host, mac='00:00:00:00:00:18',ip='10.0.4.18', defaultRoute=None)

    h19 = net.addHost('h19', cls=Host, mac='00:00:00:00:00:19',ip='10.0.4.19', defaultRoute=None)
    h20 = net.addHost('h20', cls=Host, mac='00:00:00:00:00:20',ip='10.0.4.20', defaultRoute=None)
    h21 = net.addHost('h21', cls=Host, mac='00:00:00:00:00:21',ip='10.0.5.21', defaultRoute=None)
    h22 = net.addHost('h22', cls=Host, mac='00:00:00:00:00:22',ip='10.0.5.22', defaultRoute=None)
 
    h23 = net.addHost('h23', cls=Host, mac='00:00:00:00:00:23',ip='10.0.5.23', defaultRoute=None)
    h24 = net.addHost('h24', cls=Host, mac='00:00:00:00:00:24',ip='10.0.5.24', defaultRoute=None) 
    h25 = net.addHost('h25', cls=Host, mac='00:00:00:00:00:25',ip='10.0.6.25', defaultRoute=None)
    h26 = net.addHost('h26', cls=Host, mac='00:00:00:00:00:26',ip='10.0.6.26', defaultRoute=None)
    h27 = net.addHost('h27', cls=Host, mac='00:00:00:00:00:27',ip='10.0.6.27', defaultRoute=None)
    h28 = net.addHost('h28', cls=Host, mac='00:00:00:00:00:28',ip='10.0.6.28', defaultRoute=None)

    h29 = net.addHost('h29', cls=Host, mac='00:00:00:00:00:29',ip='10.0.7.29', defaultRoute=None)
    h30 = net.addHost('h30', cls=Host, mac='00:00:00:00:00:30',ip='10.0.7.30', defaultRoute=None)
    h31 = net.addHost('h31', cls=Host, mac='00:00:00:00:00:31',ip='10.0.7.31', defaultRoute=None)
    h32 = net.addHost('h32', cls=Host, mac='00:00:00:00:00:32',ip='10.0.7.32', defaultRoute=None)

    h33 = net.addHost('h33', cls=Host, mac='00:00:00:00:00:33',ip='10.0.8.33', defaultRoute=None)
    h34 = net.addHost('h34', cls=Host, mac='00:00:00:00:00:34',ip='10.0.8.34', defaultRoute=None) 
    h35 = net.addHost('h35', cls=Host, mac='00:00:00:00:00:35',ip='10.0.8.35', defaultRoute=None)
    h36 = net.addHost('h36', cls=Host, mac='00:00:00:00:00:36',ip='10.0.8.36', defaultRoute=None)
    h37 = net.addHost('h37', cls=Host, mac='00:00:00:00:00:37',ip='10.0.8.37', defaultRoute=None)
    h38 = net.addHost('h38', cls=Host, mac='00:00:00:00:00:38',ip='10.0.8.38', defaultRoute=None)

    h39 = net.addHost('h39', cls=Host, mac='00:00:00:00:00:39',ip='10.0.9.39', defaultRoute=None)
    h40 = net.addHost('h40', cls=Host, mac='00:00:00:00:00:40',ip='10.0.9.40', defaultRoute=None)
    h41 = net.addHost('h41', cls=Host, mac='00:00:00:00:00:41',ip='10.0.9.41', defaultRoute=None)
    h42 = net.addHost('h42', cls=Host, mac='00:00:00:00:00:42',ip='10.0.9.42', defaultRoute=None)

    h43 = net.addHost('h43', cls=Host, mac='00:00:00:00:00:43',ip='10.0.10.43', defaultRoute=None)
    h44 = net.addHost('h44', cls=Host, mac='00:00:00:00:00:44',ip='10.0.10.44', defaultRoute=None)
    h45 = net.addHost('h45', cls=Host, mac='00:00:00:00:00:45',ip='10.0.10.45', defaultRoute=None)
    h46 = net.addHost('h46', cls=Host, mac='00:00:00:00:00:46',ip='10.0.10.46', defaultRoute=None) 

    h47 = net.addHost('h47', cls=Host, mac='00:00:00:00:00:47',ip='10.0.11.47', defaultRoute=None)
    h48 = net.addHost('h48', cls=Host, mac='00:00:00:00:00:48',ip='10.0.11.48', defaultRoute=None)
    h49 = net.addHost('h49', cls=Host, mac='00:00:00:00:00:49',ip='10.0.11.49', defaultRoute=None)
    h50 = net.addHost('h50', cls=Host, mac='00:00:00:00:00:50',ip='10.0.11.50', defaultRoute=None)
    h51 = net.addHost('h51', cls=Host, mac='00:00:00:00:00:51',ip='10.0.11.51', defaultRoute=None)
    h52 = net.addHost('h52', cls=Host, mac='00:00:00:00:00:52',ip='10.0.11.52', defaultRoute=None)



    

    info( '*** Add links\n')
    linkBW = {'bw':10}
    mblinkBW = {'bw': 80}
    #links between hosts and switches, the first
    #two ports of each switch are connected to two
    #mbs and its factor. port1: 0.2, port2: -0.3 , port3: 0.1, port4: -0.2
    net.addLink(h1, s1, cls=TCLink , **mblinkBW)
    net.addLink(h2, s1, cls=TCLink , **mblinkBW)
    net.addLink(h3, s1, cls=TCLink , **mblinkBW)
    net.addLink(h4, s1, cls=TCLink , **mblinkBW)    
    net.addLink(h5, s1, cls=TCLink , **linkBW)
    net.addLink(h6, s1, cls=TCLink , **linkBW)

    net.addLink(h7, s2, cls=TCLink , **mblinkBW)
    net.addLink(h8, s2, cls=TCLink , **mblinkBW)  
    net.addLink(h9, s2, cls=TCLink , **mblinkBW)
    net.addLink(h10, s2, cls=TCLink , **mblinkBW)
    net.addLink(h11, s2, cls=TCLink , **linkBW)
    net.addLink(h12, s2, cls=TCLink , **linkBW)  
    
    net.addLink(h13, s3, cls=TCLink , **mblinkBW)
    net.addLink(h14, s3, cls=TCLink , **mblinkBW)
    net.addLink(h15, s3, cls=TCLink , **mblinkBW)
    net.addLink(h16, s3, cls=TCLink , **mblinkBW)
    
    net.addLink(h17, s4, cls=TCLink , **mblinkBW)
    net.addLink(h18, s4, cls=TCLink , **mblinkBW)
    net.addLink(h19, s4, cls=TCLink , **mblinkBW)
    net.addLink(h20, s4, cls=TCLink , **mblinkBW)
   
    net.addLink(h21, s5, cls=TCLink , **mblinkBW)
    net.addLink(h22, s5, cls=TCLink , **mblinkBW)
    net.addLink(h23, s5, cls=TCLink , **mblinkBW)
    net.addLink(h24, s5, cls=TCLink , **mblinkBW)
  
    net.addLink(h25, s6, cls=TCLink , **mblinkBW)
    net.addLink(h26, s6, cls=TCLink , **mblinkBW)
    net.addLink(h27, s6, cls=TCLink , **mblinkBW)
    net.addLink(h28, s6, cls=TCLink , **mblinkBW) 
   
    net.addLink(h29, s7, cls=TCLink , **mblinkBW)
    net.addLink(h30, s7, cls=TCLink , **mblinkBW)
    net.addLink(h31, s7, cls=TCLink , **mblinkBW)
    net.addLink(h32, s7, cls=TCLink , **mblinkBW)   

    net.addLink(h33, s8, cls=TCLink , **mblinkBW)
    net.addLink(h34, s8, cls=TCLink , **mblinkBW)    
    net.addLink(h35, s8, cls=TCLink , **mblinkBW)
    net.addLink(h36, s8, cls=TCLink , **mblinkBW)
    net.addLink(h37, s8, cls=TCLink , **linkBW)
    net.addLink(h38, s8, cls=TCLink , **linkBW) 
  
    net.addLink(h39, s9, cls=TCLink , **mblinkBW)
    net.addLink(h40, s9, cls=TCLink , **mblinkBW)
    net.addLink(h41, s9, cls=TCLink , **mblinkBW)
    net.addLink(h42, s9, cls=TCLink , **mblinkBW) 
 
    net.addLink(h43, s10, cls=TCLink , **mblinkBW)
    net.addLink(h44, s10, cls=TCLink , **mblinkBW)
    net.addLink(h45, s10, cls=TCLink , **mblinkBW)
    net.addLink(h46, s10, cls=TCLink , **mblinkBW) 
   
    net.addLink(h47, s11, cls=TCLink , **mblinkBW)
    net.addLink(h48, s11, cls=TCLink , **mblinkBW)
    net.addLink(h49, s11, cls=TCLink , **mblinkBW)
    net.addLink(h50, s11, cls=TCLink , **mblinkBW)  
    net.addLink(h51, s11, cls=TCLink , **linkBW)
    net.addLink(h52, s11, cls=TCLink , **linkBW)  


    
    
    #links between switches
    net.addLink(s1, s2, cls=TCLink , **linkBW)
    net.addLink(s1, s5, cls=TCLink , **linkBW)
    net.addLink(s2, s3, cls=TCLink , **linkBW)
    net.addLink(s3, s4, cls=TCLink , **linkBW)
    net.addLink(s3, s7, cls=TCLink , **linkBW)
    net.addLink(s4, s5, cls=TCLink , **linkBW)
    net.addLink(s4, s6, cls=TCLink , **linkBW)
    net.addLink(s6, s10, cls=TCLink , **linkBW)
    net.addLink(s6, s7, cls=TCLink , **linkBW)
    net.addLink(s7, s8, cls=TCLink , **linkBW)
    net.addLink(s8, s9, cls=TCLink , **linkBW)
    net.addLink(s9, s11, cls=TCLink , **linkBW)
    net.addLink(s10, s9, cls=TCLink , **linkBW)
    net.addLink(s10, s11, cls=TCLink , **linkBW)


    info( '*** Starting network\n')
    net.build()
    info( '*** Starting controllers\n')
    for controller in net.controllers:
        controller.start()

    info( '*** Starting switches\n')
    sw_list = ['s1', 's2', 's3', 's4', 's5', 's6', 's7', 's8', 's9', 's10', 's11']
    for sw in sw_list:
        net.get(sw).start([c0])

   
    #set up middleboxes, set ratio as 1+mb_factor
    list = [h1,h2,h3,h4,h7,h8,h9,h10,h13,h14,h15,h16, h17,h18,h19,h20,h21,h22,h23,h24,h25,h26,h27,h28,
h29,h30,h31,h32,h33,h34,h35,h36,h39,h40,h41,h42,h43,h44,h45,h46,h47,h48,h49,h50]
    for i in range(44):
	case = i%4
	if case == 0:
	    ratio = 1.2
        elif case == 1:
	    ratio = 0.7
	elif case == 2:
	    ratio = 1.1
	else:
	    ratio = 0.8

        info(list[i].cmd('./pcap '+' '+str(ratio) +'&'))
	print list[i].name + ' set up middlebox '+ str(ratio)

    time.sleep(4)
    
    
    #four flows: h5-h37, h6-h38, h51-11, h52-12
    clients = [h5,h6,h51,h52]
    server = [h37,h38,h11,h12]
    for i in range(len(server)):
	    info(server[i].cmd('iperf -s -u > /tmp/server/'+server[i].name+ '.txt &'))
            

    info( '*** setup servers\n')
    time.sleep(2)
    
    duration = 160
    gap = 4
    for i in range(len(clients)):
        info(clients[i].cmd('iperf -u -c '+server[i].IP()+' -b 1M -t '+ str(duration) +' -i 10 > /tmp/'+clients[i].name+' &'))
	print "client " + clients[i].name + " server " + server[i].name
        time.sleep(gap)
	duration = duration - gap



    CLI(net)
    net.stop()

if __name__ == '__main__':
    setLogLevel( 'info' )
    myNetwork()

