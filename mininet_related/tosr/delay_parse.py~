import os
import re


# myDirectory - where you put those output files

myDirectory = "./"
fileNum = 0
delay = 0
percentage = 0.0

for file in os.listdir(myDirectory):
	# replace .txt to the file extension of your file
    if file.endswith(".txt"):
        fileNum += 1
        print str(fileNum) + ":" + str(file)
        fileContent = open(myDirectory+file)
        fileContent = fileContent.read()
        if len(fileContent) == 0:
            print "Empty file!!! \n"
            continue
        fileContent = fileContent.split('\n')
        
        
        regex = 's/sec(.*?)ms.*\((.*?)\)'
        pattern = re.compile(regex)

	
        for row in fileContent:
            results = re.findall(pattern, row)   
            if len(results) > 0:
                firstMS = results[0][0].strip(' ')
                percentage = float (percentage) + float (results[0][1].strip(' ').strip('%'))
                print firstMS + ' : ' + str(percentage)
                delay = float (delay) + float (firstMS)
print 'number of file:' + str(fileNum)               
delay = delay/fileNum 
#percentage = (percentage - 4*26)/4.0    
percentage = (percentage - 26*fileNum)/fileNum	
print 'Average Delay is :' + str(delay)
print 'Average Packet Loss is: ' + str(percentage)
