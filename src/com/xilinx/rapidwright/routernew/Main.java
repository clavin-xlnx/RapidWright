package com.xilinx.rapidwright.routernew;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class Main {
	Design design;
	String toWriteDCPfileName;
	CodePerfTracker t;
	
	boolean timing_driven = true;
	ExpanGranularityOpt opt = ExpanGranularityOpt.WIRE;
	
	//allowed number of routing iterations
	int nrOfTrials = 100;
	
	
	public Main(String[] args) {
		if(args.length != 2){
			System.out.println("USAGE: <input.dcp> <output.dcp>");
		}
		
		this.design = Design.readCheckpoint(args[0]);	
		this.toWriteDCPfileName = args[1];
		this.t = new CodePerfTracker("Router", true);	
		
	}
	
	public static void main(String[] args) {
		Main main = new Main(args);
		main.processing();
	}
	
	public void processing(){
		if(!this.timing_driven){
			RWRouter router = new RWRouter(this.design);
			this.t.start("Route Design");
			router.routeDesign();
			
			System.out.println("------------------------------------------------------------------------------");
			System.out.println("Failed connections: " + router.failedConnections);
			System.out.println("Total nodes processed: " + router.totalNodesProcessed);
			System.out.println("------------------------------------------------------------------------------");
			
			this.t.stop();
			router.getDesign().writeCheckpoint(this.toWriteDCPfileName,t);
		}else{
			if(this.opt == ExpanGranularityOpt.WIRE){			
				PFRouterWireBased router = new PFRouterWireBased(this.design, this.toWriteDCPfileName, this.nrOfTrials, this.t);
				router.route();
			}else if(this.opt == ExpanGranularityOpt.NODE){
//				PFRouterNodeBased router = new PFRouterNodeBased(this.design, this.toWriteDCPfileName, this.nrOfTrials, this.t);
//				router.route();
			}
		}
	}
}
