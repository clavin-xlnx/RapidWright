package com.xilinx.rapidwright.routernew;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class Main {
	private Design design;
	private String toWriteDCPfileName;
	private CodePerfTracker t;
	
	private boolean routerNew = true;
	private RoutingGranularityOpt opt = RoutingGranularityOpt.NODE;
	
	//allowed number of routing iterations
	private int nrOfTrials = 100;
	private int bbRange = 3;
	
	public Main(String[] arguments) {
		if(arguments.length < 2){
			System.out.println("USAGE:\n <input.dcp>\n <output.dcp>");
		}
		
		this.design = Design.readCheckpoint(arguments[0]);	
		this.toWriteDCPfileName = arguments[1];
		this.t = new CodePerfTracker("Router", true);
		
		for(int i = 2; i < arguments.length; i++) {
			if(arguments[i].contains("routingGranularity")){
				
				short optNum = Short.parseShort(arguments[++i]);
				if(optNum == 1){
					this.opt = RoutingGranularityOpt.WIRE;
				}else if(optNum == 2){
					this.opt = RoutingGranularityOpt.NODE;
				}
				
			}else if(arguments[i].contains("bbRange")) {
				this.bbRange = Short.parseShort(arguments[++i]);
			}
		}
	}
	
	public static void main(String[] args) {
		Main main = new Main(args);
		main.processing();
	}
	
	public void processing(){
		int routingRuntime = 0;
		if(!this.routerNew){
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
			if(this.opt == RoutingGranularityOpt.WIRE){			
				PFRouterWireBased router = new PFRouterWireBased(this.design, 
						this.toWriteDCPfileName, 
						this.nrOfTrials, 
						this.t, 
						this.bbRange);
				router.router.designInfo();
				this.routerConfigurationInfo();
				
				routingRuntime = router.routingRuntime();
				
				this.runtimeInfoPrinting(routingRuntime, 
						router.router.getItry(), 
						router.router.getConnectionsRouted(),
						router.sortedListOfConnection.size(),
						router.router.getNodesExpanded(),
						router.routerTimer);
				
			}else if(this.opt == RoutingGranularityOpt.NODE){
				PFRouterNodeBased router = new PFRouterNodeBased(this.design, 
						this.toWriteDCPfileName, 
						this.nrOfTrials, 
						this.t, 
						this.bbRange);
				router.router.designInfo();
				this.routerConfigurationInfo();
				
				routingRuntime = router.routingRuntime();
				
				this.runtimeInfoPrinting(routingRuntime, 
						router.router.getItry(), 
						router.router.getConnectionsRouted(),
						router.sortedListOfConnection.size(),
						router.router.getNodesExpanded(),
						router.routerTimer);
			}
		}
	}
	
	public void routerConfigurationInfo(){
		StringBuilder s = new StringBuilder();
		s.append("Router: ");
		if(routerNew){
			s.append("PathFinder-based");
		}else{
			s.append("RapidWright orginal router");
		}
		s.append("\n");
		s.append("Routing granularity option: " + this.opt);
		s.append("\n");
		s.append("Bounding box range: " + this.bbRange);
		
		System.out.println(s);
	}
	
	public void runtimeInfoPrinting(int routingRuntime, 
			int iterations, 
			int consRouted,
			int toalCons, 
			int nodesExpanded, 
			RouterTimer timer){
		System.out.printf("------------------------------------------------------------------------------\n");
		System.out.println("Runtime " + routingRuntime + " ms");
		System.out.println("Num iterations: " + iterations);
		System.out.println("Connections routed: " + consRouted);
		System.out.println("Connections rerouted: " + (consRouted - toalCons));
		System.out.println("Nodes expanded: " + nodesExpanded);
		System.out.printf("------------------------------------------------------------------------------\n");
		System.out.print(timer);
		System.out.printf("------------------------------------------------------------------------------\n\n");
	}
	
}
