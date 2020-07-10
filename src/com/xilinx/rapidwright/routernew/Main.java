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
	private boolean columnRowConstraint = true;
	private float mdWeight = 1;
	private float hopWeight = 1;
	private float initial_pres_fac = 0.5f; 
	private float pres_fac_mult = 2; 
	private float acc_fac = 1;
	
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
				
			}else if(arguments[i].contains("bbRange")){
				this.bbRange = Short.parseShort(arguments[++i]);
				
			}else if(arguments[i].contains("columnRowConstraint")){
				this.columnRowConstraint = true;
				
			}else if(arguments[i].contains("mdWeight")){
				this.mdWeight = Float.parseFloat(arguments[++i]);
				
			}else if(arguments[i].contains("hopWeight")){
				this.hopWeight = Float.parseFloat(arguments[++i]);
				
			}else if(arguments[i].contains("initial_pres_fac")){
				this.initial_pres_fac = Float.parseFloat(arguments[++i]);
				
			}else if(arguments[i].contains("pres_fac_mult")){
				this.pres_fac_mult = Float.parseFloat(arguments[++i]);
				
			}else if(arguments[i].contains("acc_fac")){
				this.acc_fac = Float.parseFloat(arguments[++i]);
				
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
						this.bbRange,
						this.mdWeight,
						this.hopWeight,
						this.initial_pres_fac,
						this.pres_fac_mult,
						this.acc_fac);
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
						this.bbRange,
						this.mdWeight,
						this.hopWeight,
						this.initial_pres_fac,
						this.pres_fac_mult,
						this.acc_fac);
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
		s.append("Routing granularity: " + this.opt);
		s.append("\n");
		s.append("Bounding box range: " + this.bbRange);
		s.append("\n");
		s.append("Manhattan distance weight: " + this.mdWeight);
		s.append("\n");
		s.append("Hops weight: " + this.hopWeight);
		s.append("\n");
		s.append("initial pres fac: " + this.initial_pres_fac);
		s.append("\n");
		s.append("pres fac mult: " + this.pres_fac_mult);
		s.append("\n");
		s.append("acc fac: " + this.acc_fac);
		
		System.out.println(s);
	}
	
	public void runtimeInfoPrinting(int routingRuntime, 
			int iterations, 
			int consRouted,
			int toalCons, 
			int nodesExpanded, 
			RouterTimer timer){
		System.out.printf("--------------------------------------------------------------------------------------\n");
		System.out.println("Runtime " + routingRuntime + " ms");
		System.out.println("Num iterations: " + iterations);
		System.out.println("Connections routed: " + consRouted);
		System.out.println("Connections rerouted: " + (consRouted - toalCons));
		System.out.println("Nodes expanded: " + nodesExpanded);
		System.out.printf("--------------------------------------------------------------------------------------\n");
		System.out.print(timer);
		System.out.printf("--------------------------------------------------------------------------------------\n");
		System.out.printf("======================================================================================\n\n");
	}
	
}
