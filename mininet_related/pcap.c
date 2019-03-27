#include <pcap.h>
#include <pcap/pcap.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/if_ether.h> 
#include <net/ethernet.h>
#include <netinet/ether.h> 
#include <netinet/ip.h> 


pcap_t* descr;
double ratio;

/* Callback function which modifies the pacet */
void my_callback(u_char *args,const struct pcap_pkthdr* pkthdr,const u_char* packet)
{


   struct ether_header * eptr = (struct ether_header *) packet;
   u_int16_t type = ntohs(eptr->ether_type);

    if(type == ETHERTYPE_IP)
    {/* handle IP packet */
       //printf("test  :  ");
	int initSize = ((256 * *(packet + 16)) + *(packet + 17) + 14);
  	printf("INITIAL SIZE: %d", initSize - 14);	
	

	if((initSize * ratio) < initSize){
	 initSize = initSize * ratio;
	}
	u_char* modifiedPacket = (u_char*) malloc(ratio * initSize * sizeof(u_char) + 14);
	memcpy(modifiedPacket, packet, initSize);
	
	    int i = (initSize - 14) * ratio;
	    int j = initSize;
           
	    for(; j < i ; j++){//Increase the size of the payload
		modifiedPacket[j] = 0;
	    }
	unsigned char* IP = (unsigned char*) malloc(sizeof(unsigned char) * 20);
	for(j = 14; j < 20 + 14; j++){
		IP[j - 14] = modifiedPacket[j];		
	}
	
	int packetsize = i + 14;
	printf("PACKET SIZE: %d\n", packetsize);
	while(packetsize > 1512){
		printf("in here");
		if(pcap_sendpacket(descr, modifiedPacket, 1512) < 0){
			printf("packet not successfully send\n\n");
			exit(0);
		}
		packetsize -= 1512;
	}
	if(packetsize < 20 + 14 + 8){
		packetsize = 20 + 14 + 8;
	}
    		if(pcap_sendpacket(descr, modifiedPacket, packetsize) < 0){//to send the packet, you must specify the interface, the packet, and the size of the packet
			printf("packet not successfully send\n\n");
		}
    }else{
	   
	    if(pcap_sendpacket(descr, packet, pkthdr->len) < 0){
			printf("packet not successfully sent <-\n\n");
		}
	}
}



    
int main(int argc,char **argv)
{ 
    char *dev; 
    char errbuf[PCAP_ERRBUF_SIZE];
    
    struct bpf_program fp;      /* hold compiled program     */
    bpf_u_int32 maskp;          /* subnet mask               */
    bpf_u_int32 netp;           /* ip                        */
    u_char* args = NULL;
    ratio = atoi(argv[1]);
    /* Options must be passed in as a string because I am lazy */
    if(argc < 2){ 
        fprintf(stdout,"Usage: %s numpackets \"options\"\n",argv[0]);
        return 0;
    }

    /* grab a device to peak into... */
    dev = "m1-eth0";

    /* ask pcap for the network address and mask of the device */
    pcap_lookupnet(dev,&netp,&maskp,errbuf);

    /* open device for reading. NOTE: defaulting to
     * promiscuous mode*/
    descr = pcap_open_live(dev,1000000,1,-1,errbuf); //max packet size capture will be 2048
    if(descr == NULL)
    { printf("pcap_open_live(): %s\n",errbuf); exit(1); }


    if(argc > 2)
    {
        /* Lets try and compile the program.. non-optimized */
        if(pcap_compile(descr,&fp,argv[2],0,netp) == -1)
        { fprintf(stderr,"Error calling pcap_compile\n"); exit(1); }

        /* set the compiled program as the filter */
        if(pcap_setfilter(descr,&fp) == -1)
        { fprintf(stderr,"Error setting filter\n"); exit(1); }
    }

    /* ... and loop */ 
    pcap_loop(descr,0.0,my_callback,args);

    fprintf(stdout,"\nfinished\n");
    return 0;
}
