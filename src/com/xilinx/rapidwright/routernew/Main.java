package com.xilinx.rapidwright.routernew;

import java.util.Collection;
import java.util.HashSet;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class Main {
	public Design design;
	private String toWriteDCPfileName;
	private CodePerfTracker t;
	
	private boolean routerNew = true;
	private RoutingGranularityOpt opt = RoutingGranularityOpt.ROUTABLENODE;
	
	//allowed number of routing iterations
	private int nrOfTrials = 100;
	private int bbRange = 5;
	private boolean isINTtileRange = false;//TODO
	private float mdWeight = 1.5f;
	private float hopWeight = 0.5f;
	private float initial_pres_fac = 0.5f; 
	private float pres_fac_mult = 2f; 
	private float acc_fac = 1;
	private float base_cost_fac = 1;
	
	public Main(String[] arguments) {
		if(arguments.length < 2){
			System.out.println("USAGE:\n <input.dcp>\n <output .dcp folder>");
		}
		
		this.design = Design.readCheckpoint(arguments[0]);
		
		int folderSep = arguments[0].lastIndexOf("/");
		int extensionSep = arguments[0].lastIndexOf(".");
		this.toWriteDCPfileName = arguments[1] + arguments[0].substring(folderSep, extensionSep) + ".dcp";
		
		this.t = new CodePerfTracker("Router", true);
		
		for(int i = 2; i < arguments.length; i++) {
			if(arguments[i].contains("routingGranularity")){
				
				short optNum = Short.parseShort(arguments[++i]);
				if(optNum == 3){
					this.opt = RoutingGranularityOpt.ROUTABLETIMINGGROUP;
				}else if(optNum == 2){
					this.opt = RoutingGranularityOpt.ROUTABLENODE;
				}else if(optNum == 1){
					this.opt = RoutingGranularityOpt.ROUTABLEWIRE;
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
		this.checkAverageWiresandNodesEachTile();
		if(!this.routerNew){
			RWRouter router = new RWRouter(this.design);
			this.t.start("Route Design");
//			router.routeDesign();
			
			System.out.println("------------------------------------------------------------------------------");
			System.out.printf("Find input pin feed took : %10.4f s\n", router.findInputPinFeedTime);
			System.out.println("Failed connections: " + router.failedConnections);
			System.out.println("Total nodes processed: " + router.totalNodesProcessed);
			System.out.println("------------------------------------------------------------------------------");
			
			this.t.stop();
			router.getDesign().writeCheckpoint(this.toWriteDCPfileName,t);
			
		}else{			
			if(this.opt == RoutingGranularityOpt.ROUTABLENODE){
				RoutableNodeRouter router = new RoutableNodeRouter(this.design, 
						this.toWriteDCPfileName,
						this.nrOfTrials,
						this.t,
						(short) this.bbRange,
						this.mdWeight,
						this.hopWeight,
						this.initial_pres_fac,
						this.pres_fac_mult,
						this.acc_fac,
						this.base_cost_fac);
				
				router.designInfo();
				this.routerConfigurationInfo();
				
				this.t.start("Route Design");
				routingRuntime = router.routingRuntime();
				this.t.stop();
				
				router.getDesign().writeCheckpoint(this.toWriteDCPfileName,t);
				
				router.getAllHopsAndManhattanD();
				
				this.rnodesInfo(router.manhattanD,
						router.hops,
						router.firstIterRNodes,
						router.rrgNodeId,
						router.usedRNodes.size(),
						router.checkAverageNumWires(),
						1);
				
				this.runtimeInfoPrinting(routingRuntime, 
						router.itry, 
						router.connectionsRouted,
						router.sortedListOfConnection.size(),
						router.nodesExpanded,
						router.routerTimer);
				
			}else if(this.opt == RoutingGranularityOpt.ROUTABLEWIRE){
				RoutableWireRouter router = new RoutableWireRouter(this.design, 
						this.toWriteDCPfileName,
						this.nrOfTrials,
						this.t,
						(short) this.bbRange,
						this.mdWeight,
						this.hopWeight,
						this.initial_pres_fac,
						this.pres_fac_mult,
						this.acc_fac,
						this.base_cost_fac);
				
				router.designInfo();
				this.routerConfigurationInfo();
				
				this.t.start("Route Design");
				routingRuntime = router.routingRuntime();
				this.t.stop();
				
				router.getDesign().writeCheckpoint(this.toWriteDCPfileName,t);
				
				router.getAllHopsAndManhattanD();
				
				this.rnodesInfo(router.manhattanD,
						router.hops,
						router.firstIterRNodes,
						router.rrgNodeId,
						router.usedRNodes.size(),
						1,
						0);
				
				this.runtimeInfoPrinting(routingRuntime, 
						router.itry, 
						router.connectionsRouted,
						router.sortedListOfConnection.size(),
						router.nodesExpanded,
						router.routerTimer);
				
			}else if(this.opt == RoutingGranularityOpt.ROUTABLETIMINGGROUP){
				RoutableTimingGroupRouter router = new RoutableTimingGroupRouter(this.design, 
						this.toWriteDCPfileName,
						this.nrOfTrials,
						this.t,
						(short) this.bbRange,
						this.mdWeight,
						this.hopWeight,
						this.initial_pres_fac,
						this.pres_fac_mult,
						this.acc_fac,
						this.base_cost_fac);
				
				router.designInfo();
				this.routerConfigurationInfo();
				
				this.t.start("Route Design");
				routingRuntime = router.routingRuntime();
				this.t.stop();
				
				router.getDesign().writeCheckpoint(this.toWriteDCPfileName,t);
				
				router.getAllHopsAndManhattanD();
				
				this.rnodesInfo(router.manhattanD,
						router.hops,
						router.firstIterRNodes,
						router.rrgNodeId,
						router.usedRNodes.size(),
						router.checkAverageNumWires(),
						router.checkAverageNumNodes());
				
				this.runtimeInfoPrinting(routingRuntime, 
						router.itry, 
						router.connectionsRouted,
						router.sortedListOfConnection.size(),
						router.nodesExpanded,
						router.routerTimer);
				
			}		
		}
	}
	
	public void checkAverageWiresandNodesEachTile(){
		Device dev = this.design.getDevice();
		Collection<Tile> tiles = dev.getAllTiles();
		float totalWires = 0;
		float totalINTWires = 0;
		int totalINTtiles = 0;
		HashSet<Integer> xTiles = new HashSet<>();
		for(Tile tile:tiles){
			totalWires += tile.getWireCount();
			if(tile.getTileNamePrefix().contains("INT_")){
				totalINTWires += tile.getWireCount();
				totalINTtiles++;
				xTiles.add(tile.getColumn());
			}
		}
		float averWireEachTile = totalWires / tiles.size();
		float averWireEachINTtile = totalINTWires / totalINTtiles;
		System.out.println("total Wires: " + totalWires + "\ntotal tiles: " + tiles.size());
		System.out.println("total INT Wires: " + totalINTWires+ "\ntotal INT tiles: " + totalINTtiles);
		System.out.printf("average wires in each tile: %5.2f\n", averWireEachTile);
		System.out.printf("average wires in each INT tile: %5.2f\n", averWireEachINTtile);
	}
	
	public void routerConfigurationInfo(){
		StringBuilder s = new StringBuilder();
		s.append("Router: ");
		if(routerNew){
			s.append("PathFinder-based connection router");
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
	
	public void rnodesInfo(float sumMD, long hops, int firstIterRNodes, int totalRNodes, int totalUsage, float averWire, float averNode){
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
		System.out.printf("Total Manhattan distance: %10.2f\n", sumMD);
		System.out.printf("Total hops: %d\n", hops);
		System.out.printf("Rnodes created 1st iter: %d\n", firstIterRNodes);
		System.out.printf("Total rnodes created: %d\n", totalRNodes);
		System.out.printf("Total rnodes used: %d\n", totalUsage);
		if(this.opt == RoutingGranularityOpt.ROUTABLENODE){
			System.out.printf("Average #wire in rnodes: %5.2f\n", averWire);
		}else if(this.opt == RoutingGranularityOpt.ROUTABLETIMINGGROUP){
			System.out.printf("Average #wire in rnodes: %5.2f\n", averWire);
			System.out.printf("Average #node in rnodes: %5.2f\n", averNode);
		}
	}
	
	public void runtimeInfoPrinting(int routingRuntime, 
			int iterations,
			int consRouted,
			int toalCons,
			long nodesExpanded,
			RouterTimer timer){
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
		System.out.printf("Routing took %.2f s\n", routingRuntime*1e-3);
		System.out.println("Num iterations: " + iterations);
		System.out.println("Connections routed: " + consRouted);
		System.out.println("Connections rerouted: " + (consRouted - toalCons));
		System.out.println("Nodes expanded: " + nodesExpanded);
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
		System.out.print(timer);
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
		System.out.printf("====================================================================================================================\n\n");
	}
	
}
