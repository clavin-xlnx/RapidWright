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
	private boolean isINTtileRange = false;
	private float mdWeight = 1;
	private float hopWeight = 1;
	private float initial_pres_fac = 0.5f; 
	private float pres_fac_mult = 2; 
	private float acc_fac = 1;
	private float base_cost_fac = 1;
	
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
				}else if(optNum == 3){
					this.opt = RoutingGranularityOpt.TIMINGGROUP;
				}
				
			}else if(arguments[i].contains("bbRange")){
				this.bbRange = Short.parseShort(arguments[++i]);
				
			}else if(arguments[i].contains("isINTtileRange")){
				this.isINTtileRange = true;
				
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
				
			}else if(arguments[i].contains("base_cost_fac")){
//				if(this.opt == RoutingGranularityOpt.WIRE){
//					this.base_cost_fac = 1;
//				}else {
					this.base_cost_fac = Float.parseFloat(arguments[++i]);
//				}
				
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
						this.acc_fac,
						this.base_cost_fac);//base cost factor of RNode<Wire> is 1 by default
				router.router.designInfo();
				this.routerConfigurationInfo();
				
				routingRuntime = router.routingRuntime();
				
				router.router.getAllHopsAndManhattanD();
				
				this.rnodesInfo(router.router.getManhattanD(),
						router.router.getHops(),
						router.globalRNodeIndex,
						router.router.getUsedRNodes(),
						1,
						0);
				
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
						this.acc_fac,
						this.base_cost_fac);
				router.router.designInfo();
				this.routerConfigurationInfo();
				
				routingRuntime = router.routingRuntime();
				
				router.router.getAllHopsAndManhattanD();
				
				this.rnodesInfo(router.router.getManhattanD(),
						router.router.getHops(),
						router.globalRNodeIndex,
						router.router.getUsedRNodes(),
						router.checkAverageNumWires(),
						1);
				
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
		s.append("\n");
		s.append("base cost fac: " + this.base_cost_fac);
		
		System.out.println(s);
	}
	
	public void rnodesInfo(float sumMD, long hops, int totalRNodes, int totalUsage, float averWire, float averNode){
		System.out.printf("---------------------------------------------------------------------------------------------------\n");
		System.out.printf("Total Manhattan distance: %10.2f\n", sumMD);
		System.out.printf("Total hops: %d\n", hops);
		System.out.printf("Total rnodes created: %d\n", totalRNodes);
		System.out.printf("Total rnodes used: %d\n", totalUsage);
		if(this.opt == RoutingGranularityOpt.NODE){
			System.out.printf("Average #wire in rnodes: %5.2f\n", averWire);
		}else if(this.opt == RoutingGranularityOpt.TIMINGGROUP){
			System.out.printf("Average #wire in rnodes: %5.2f\n", averWire);
			System.out.printf("Average #node in rnodes: %5.2f\n", averNode);
		}
	}
	
	public void runtimeInfoPrinting(int routingRuntime, 
			int iterations, 
			int consRouted,
			int toalCons, 
			int nodesExpanded, 
			RouterTimer timer){
		System.out.printf("---------------------------------------------------------------------------------------------------\n");
		System.out.printf("Routing took %.2f s\n", routingRuntime*1e-3);
		System.out.println("Num iterations: " + iterations);
		System.out.println("Connections routed: " + consRouted);
		System.out.println("Connections rerouted: " + (consRouted - toalCons));
		System.out.println("Nodes expanded: " + nodesExpanded);
		System.out.printf("---------------------------------------------------------------------------------------------------\n");
		System.out.print(timer);
		System.out.printf("---------------------------------------------------------------------------------------------------\n");
		System.out.printf("===================================================================================================\n\n");
	}
	
}
