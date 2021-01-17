package com.xilinx.rapidwright.routernew;

import java.util.Collection;
import java.util.HashSet;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;

public class Main {
	public Design design;
	private String toWriteDCPfileName;
	private CodePerfTracker t;
	
	Configuration config = new Configuration();
	
	public Main(String[] arguments) {
		if(arguments.length < 2){
			System.out.println("USAGE:\n <input.dcp>\n <output .dcp folder>");
		}
		
		this.design = Design.readCheckpoint(arguments[0]);
		
		int folderSep = arguments[0].lastIndexOf("/");
		int extensionSep = arguments[0].lastIndexOf(".");
		this.toWriteDCPfileName = arguments[1] + arguments[0].substring(folderSep, extensionSep) + ".dcp";
		
		this.t = new CodePerfTracker("Router", true);
		
		config.customizeConfig(2, arguments);
	}
	
	public static void main(String[] args) {
		Main main = new Main(args);
//		MessageGenerator.waitOnAnyKey();//halt the program
		main.processing();
	}
	
	public void processing(){
		int routingRuntime = 0;
		this.checkAverageWiresandNodesEachTile();
					
		if(config.getOpt() == RoutingGranularityOpt.NODE){
			RoutableNodeRouter router = new RoutableNodeRouter(this.design, config);
			
			router.designInfo();
			this.routerConfigurationInfo();
			
			this.t.start("Route Design");
			routingRuntime = router.doRouting();
			this.t.stop();
			
			router.getDesign().writeCheckpoint(this.toWriteDCPfileName,t);
			
			router.getAllHopsAndManhattanD();
			
			this.rnodesInfo(router.manhattanD,
					router.hops,
					router.firstIterRNodes,
					router.rnodeId,
					router.usedRNodes.size(),
					router.checkAverageNumWires(),
					1, 0, 0,
					router.averFanoutRNodes, 0, 0, 0, 0, 0, 0);
			
			this.runtimeInfoPrinting(routingRuntime, 
					router.itry, 
					router.connectionsRouted,
					router.sortedListOfConnection.size(),
					router.nodesPushed,
					router.nodesPushedFirstIter,
					router.nodesPoped,
					router.nodesPopedFirstIter,
					router.routerTimer,
					router.callingOfGetNextRoutable);
			
		}else if(config.getOpt() == RoutingGranularityOpt.WIRE){
			RoutableWireRouter router = new RoutableWireRouter(this.design, 
					this.toWriteDCPfileName,
					config.getNrOfTrials(),
					this.t,
					(short) config.getBbRange(),
					config.getMdWeight(),
					config.getHopWeight(),
					config.getInitial_pres_fac(),
					config.getPres_fac_mult(),
					config.getAcc_fac());
			
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
					0, 0, 0,
					router.averFanoutRNodes, 0, 0, 0, 0, 0, 0);
			
			this.runtimeInfoPrinting(routingRuntime,
					router.itry, 
					router.connectionsRouted,
					router.sortedListOfConnection.size(),
					router.nodesExpanded,
					router.nodesExpandedFirstIter,
					router.nodesPopedFromQueue,
					router.nodesPopedFromQueueFirstIter,
					router.routerTimer,
					0);
			
		}else if(config.getOpt() == RoutingGranularityOpt.TIMINGGROUP){
				RoutableTimingGroupRouter router = new RoutableTimingGroupRouter(this.design, config);
				router.designInfo();
				this.routerConfigurationInfo();
				
				this.t.start("Route Design");
				routingRuntime = router.routingRuntime();
				this.t.stop();
				
				router.getDesign().writeCheckpoint(this.toWriteDCPfileName,t);
				
				router.getAllHopsAndManhattanD();
				router.checkAverageNumWires();
				
				this.rnodesInfo(router.manhattanD,
						router.hops,
						router.firstIterRNodes,
						router.rrgNodeId,
						router.getUsedRNodes(),
						router.averWire,
						router.averNodePerImmuTg,
						router.averImmuTgPerSiblings,
						router.averNodePerSiblings,
						router.averFanoutRNodes,
						router.estimator != null? router.estimator.intableQuery - router.intableCall: 0,
						router.estimator != null? router.estimator.outOfTableQuery - router.outtableCall: 0,
						router.callDelayEstimator,
						router.noCallOfDelayEstimator,
						router.estimator != null? router.estimator.pinbounceQuery - router.pinbounce: 0,
						router.estimator != null? router.estimator.pinfeedQuery - router.pinfeed: 0);
				
				this.runtimeInfoPrinting(routingRuntime,
						router.itry, 
						router.connectionsRouted,
						router.sortedListOfConnection.size(),
						router.nodesExpanded,
						router.nodesExpandedFirstIter,
						router.nodesPopedFromQueue,
						router.nodesPopedFromQueueFirstIter,
						router.routerTimer,
						router.callingOfGetNextRoutable);
				
				if(config.isTimingDriven()){
					router.timingInfo();
					System.out.printf("==========================================================================================================================================\n");
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
		System.out.println(config);
	}
	
	public void rnodesInfo(float sumMD, long hops, int firstIterRNodes, int totalRNodes, int totalUsage, float averWire, float averNode,
			float averImmuTgSiblings, float averNodeSiblings, float averChildren, long intableQ, long outOfTableQ, 
			long callOfDelayEstimator, long noCallOfDelayEstimator, long pinbounce, long pinfeed){
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
		System.out.printf("Total Manhattan distance: %10.2f\n", sumMD);
		System.out.printf("Total hops: %d\n", hops);
		System.out.printf("Rnodes created 1st iter: %d\n", firstIterRNodes);
		System.out.printf("Total rnodes created: %d\n", totalRNodes);
		System.out.printf("Total rnodes used: %d\n", totalUsage);
		if(config.getOpt() == RoutingGranularityOpt.NODE){
			System.out.printf("Average #wire in rnodes: %5.2f\n", averWire);
			System.out.printf("Average #children per node: %5.2f\n", averChildren);
		}else if(config.getOpt() == RoutingGranularityOpt.TIMINGGROUP){
			System.out.printf("Average #wire in rnodes: %5.2f\n", averWire);
			System.out.printf("Average #node in rnodes: %5.2f\n", averNode);
			System.out.printf("Average #TG per siblings: %5.2f\n", averImmuTgSiblings);
			System.out.printf("Average #node per siblings: %5.2f\n", averNodeSiblings);
			System.out.printf("Average #children per siblings: %5.2f\n", averChildren);
			System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
			System.out.println("Total Queries of delay estimator in pushing: " + callOfDelayEstimator);
			System.out.println(" Queries of intable delay: " + intableQ);
			System.out.println(" Queries of out-of-table delay: " + outOfTableQ);
			System.out.println(" Queries of pinbounce: " + pinbounce);
			System.out.println(" Queries of pinfeed: " + pinfeed);
			System.out.println("No call of delay estimator in pushing: " + noCallOfDelayEstimator);
		}
	}
	
	public void runtimeInfoPrinting(int routingRuntime, 
			int iterations,
			int consRouted,
			int toalCons,
			long nodesPushed,
			long nodesPushedFirstIter,
			long nodesPoped,
			long nodesPopedFirstIter,
			RouterTimer timer,
			long callingOfGetNextRoutable){
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");	
		System.out.println("Num iterations: " + iterations);
		System.out.println("Connections routed: " + consRouted);
		System.out.println(" Connections rerouted: " + (consRouted - toalCons));
		System.out.println("Nodes pushed: " + nodesPushed);
		System.out.println(" Nodes pushed first iter: " + nodesPushedFirstIter);
		System.out.println("Nodes poped: " + nodesPoped);
		System.out.println(" Nodes poped first iter: " + nodesPopedFirstIter);
		System.out.println("Calls of get next routables: " + callingOfGetNextRoutable);
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
		System.out.printf("Routing took %.2f s\n", routingRuntime*1e-3);
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
		System.out.print(timer);
		System.out.printf("--------------------------------------------------------------------------------------------------------------------\n");
		System.out.printf("==========================================================================================================================================\n");
	}
}
