package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import org.jgrapht.GraphPath;

/**
 * Basic example to show reading in a placed and routed DCP and calculating the worst case
 * data path delay. 
 */
public class ReportTimingExample {

    public static void main(String[] args) {
        if(args.length != 1) {
        	System.out.println("USAGE: <dcp_file_name>");
        	return;
        }
        CodePerfTracker t = new CodePerfTracker("Report Timing Example");
        t.useGCToTrackMemory(true);

        // Read in an example placed and routed DCP        
        t.start("Read DCP");
        Design design = Design.readCheckpoint(args[0], CodePerfTracker.SILENT);
        
        // Instantiate and populate the timing manager for the design
        t.stop().start("Create TimingManager");
        TimingManager tim = new TimingManager(design);
        
        // Get and print out worst data path delay in design
        t.stop().start("Get Max Delay");
        GraphPath<TimingVertex, TimingEdge> criticalPath = tim.getTimingGraph().getMaxDelayPath();
        
        // Print runtime summary
        t.stop().printSummary();        
        System.out.println("\nCritical path: "+ ((int)criticalPath.getWeight())+ " ps");
        System.out.println("\nPath details:");
        System.out.println(criticalPath.toString().replace(",", ",\n")+"\n");
        
        //================= router debugging ====================
        for(TimingEdge e : criticalPath.getEdgeList()){
        	System.out.println(e.toString() + ", delay = " + e.delaysInfo() + ", " + e.getNet().toString());
        	/*for(SitePinInst spi : e.getNet().getPins()){
        		System.out.println(spi.toString());
        	}*/
        }
        
        StringBuilder s = new StringBuilder();
    	s.append("\nCritical TimingEdges: ");
    	s.append("{");
    	int i = 0;
    	for(TimingEdge v : criticalPath.getEdgeList()){
    		if(i % 2 == 0){
	    		s.append(v.getDst().getName());
	    		s.append(" ");
    		}
    		i++;
    	}
    	s.replace(s.length() - 1, s.length(), "");
    	s.append("}");  
        
        System.out.println(s);
        
        tim.getTimingGraph().getDelayOfPath("{FD_ibb/Q LUT6_0/O LUT6_133/O LUT6_2_30/LUT5/O FD_k/D}", null);
//        tim.getTimingGraph().getDelayOfPath("{FD_mjn/Q LUT6_2_a5/LUT5/O LUT5_27a/O LUT6_5ea/O FD_kpd/D}", null);
        //================= router debugging ====================
    }
}
