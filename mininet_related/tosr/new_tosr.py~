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
    base_rate = input("base_rate for each flow: ")
    net = Mininet( topo=None,
                   build=False,
                   ipBase='10.0.0.0/8')

    info( '*** Adding controller\n' )
    c0=net.addController(name='c0',
                      controller=RemoteController,
                      ip='192.168.56.1',
                      port=6633)

    info( '*** Add switches\n')
    sw_list = [];
    for i in range(1, 12):
        s_object = net.addSwitch('s'+str(i), cls=OVSKernelSwitch)
        sw_list.append(s_object)
        #print s_object.name + ' added sw'


    host_list = [];

    info( '*** Add hosts\n')
    for i in range(1,12):
        for j in range(1, 7):
            h_index = (i-1)*6 + j
            h_string = 'h' + str(h_index)
            mac_add = '00:00:00:00:00:' + str(h_index)
            ip_add = '10.0.' + str(i) + '.' + str(h_index)
            h_object = net.addHost(h_string, cls=Host, mac = mac_add, ip = ip_add, defaultRoute=None )
            host_list.append(h_object)
            #print h_object.name + ' added host'






    info( '*** Add links\n')
    linkBW = {'bw':10}
    mblinkBW = {'bw': 20}
    #links between hosts and switches, the first
    #two ports of each switch are connected to two
    #mbs and its factor. port1: 0.2, port2: -0.3 , port3: 0.1, port4: -0.2
    for i in range(1, 12):
        for j in range(1, 7):
            h_index = (i-1)*6 + j
            type = h_index % 6
            if(type >= 1 and type <=4):
                net.addLink(host_list[h_index-1], sw_list[i-1], cls = TCLink, **mblinkBW)
                #print 'h'+str(h_index) + ' connect to ' + sw_list[i-1].name
            else:
                net.addLink(host_list[h_index-1], sw_list[i-1], cls = TCLink, **linkBW)
                #print 'h'+str(h_index) + ' connect to ' + sw_list[i-1].name



    #links between switches
    net.addLink(sw_list[0], sw_list[1], cls=TCLink , **linkBW)
    net.addLink(sw_list[0], sw_list[4], cls=TCLink , **linkBW)
    net.addLink(sw_list[1], sw_list[2], cls=TCLink , **linkBW)
    net.addLink(sw_list[2], sw_list[3], cls=TCLink , **linkBW)
    net.addLink(sw_list[2], sw_list[6], cls=TCLink , **linkBW)
    net.addLink(sw_list[3], sw_list[4], cls=TCLink , **linkBW)
    net.addLink(sw_list[3], sw_list[5], cls=TCLink , **linkBW)
    net.addLink(sw_list[5], sw_list[9], cls=TCLink , **linkBW)
    net.addLink(sw_list[5], sw_list[6], cls=TCLink , **linkBW)
    net.addLink(sw_list[6], sw_list[7], cls=TCLink , **linkBW)
    net.addLink(sw_list[7], sw_list[8], cls=TCLink , **linkBW)
    net.addLink(sw_list[8], sw_list[10], cls=TCLink , **linkBW)
    net.addLink(sw_list[9], sw_list[8], cls=TCLink , **linkBW)
    net.addLink(sw_list[9], sw_list[10], cls=TCLink , **linkBW)


    info( '*** Starting network\n')
    net.build()
    info( '*** Starting controllers\n')
    for controller in net.controllers:
        controller.start()

    info( '*** Starting switches\n')
    #sw_list = ['s1', 's2', 's3', 's4', 's5', 's6', 's7', 's8', 's9', 's10', 's11']
    for sw in sw_list:
        net.get(sw.name).start([c0])


    #set up middleboxes, set ratio as 1+mb_factor

    for i in range(66):
        case = i%6
        if case == 0:
            ratio = 1.2
        elif case == 1:
            ratio = 0.7
        elif case == 2:
            ratio = 1.1
        elif case == 3:
            ratio = 0.8
        else:
            ratio = 1

        if ratio != 1:
            info(host_list[i].cmd('./pcap '+ str(ratio) +'&'))
            #print host_list[i].name + ' set up middlebox '+ str(ratio)

    time.sleep(2)

    #four backgroud flows, each has 3 Mbps
    bclients = [host_list[28], host_list[5], host_list[22], host_list[34]]
    bservers = [host_list[10], host_list[29], host_list[16], host_list[40]]
    #four flows with mbs, the base rate will increase accordingly
    clients = [host_list[4], host_list[52],host_list[64], host_list[10]]
    server = [ host_list[46], host_list[11],host_list[5], host_list[58]]
    for i in range (len(bservers)):
        info(bservers[i].cmd('iperf -s -u > /home/mininet/mininet/custom/server/'+ bservers[i].name+ '.txt &'))

    for i in range (len(server)):
        info(server[i].cmd('iperf -s -u > /home/mininet/mininet/custom/server/ps/'+ server[i].name+ '.txt &'))    

    info( '*** setup servers\n')
    time.sleep(2)

    duration = 160
    b_duration = 180
    gap = 4
    rate = 0
    for i in range(len(bclients)):
	if i==0:
	    rate = 4
	elif i==1:
	    rate = 2
	else:
	    rate = 6
        info(bclients[i].cmd('iperf -u -c '+bservers[i].IP()+' -b '+ str(rate) + 'M -t '+ str(b_duration) +' -i 10 > /home/mininet/mininet/custom/clients/'+bclients[i].name+' &'))
        print "bclient " + bclients[i].name + " bserver " + bservers[i].name + " rate " + str(rate)
        time.sleep(2)
        b_duration = b_duration - 2
    time.sleep(gap)

    for i in range(len(clients)):
        info(clients[i].cmd('iperf -u -c '+server[i].IP()+' -b '+ str(base_rate) + 'M -t '+ str(duration) +' -i 10 > /home/mininet/mininet/custom/clients/'+clients[i].name+' &'))
	print "client " + clients[i].name + " server " + server[i].name +  " rate " + str(base_rate)
        time.sleep(gap)
	duration = duration - gap



    CLI(net)
    net.stop()

if __name__ == '__main__':
    setLogLevel( 'info' )
    myNetwork()
