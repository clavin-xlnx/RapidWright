/*
 * Copyright (c) 2019 Xilinx, Inc.
 * All rights reserved.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.timing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jgrapht.GraphPath;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.routernew.Connection;
import com.xilinx.rapidwright.routernew.Netplus;
import com.xilinx.rapidwright.routernew.RoutableTimingGroupRouter;
import com.xilinx.rapidwright.util.Pair;


/**
 * A TimingManager sets up and creates an example TimingModel and an example TimingGraph for a given
 * Design.
 */
public class TimingManager {

    private TimingModel timingModel;
    private TimingGraph timingGraph;
    private Design design;
    private Device device;

    public static final int BUILD_GRAPH_PATHS_DEFAULT_PARAM = 1; // use 0 instead for all paths

    /**
     * Default constructor: creates the TimingManager object, which the user needs to create for 
     * using our TimingModel, and then it builds the model.
     * @param design RapidWright Design object.
     */
    public TimingManager(Design design) {
        this(design, true);
    }

    /**
     * Alternate constructor for creating the objects for the TimingModel, but with the choice to 
     * not build the model yet.
     * @param design RapidWright Design object.
     * @param doBuild Whether to go ahead and build the model now.  For example, a user might not 
     * want to build the TimingGraph yet.
     */
    public TimingManager(Design design, boolean doBuild) {
    	this.design = design;        
        timingModel = new TimingModel(this.design.getDevice());
        timingGraph = new TimingGraph(this.design);
        timingModel.setTimingManager(this);
        timingGraph.setTimingManager(this);
        timingGraph.setTimingModel(timingModel);
        this.device = this.design.getDevice();
        if (doBuild)
            build();
    }
    
    //----------------------- methods added for timing-driven routing -------------------------------
    
    public void updateIllegalNetsDelays(List<Netplus> illegalNets, Map<Node, Float> nodesDelays){
    	 for(Netplus n:illegalNets){
    		 for(Connection c:n.getConnection()){
    			 float netDelay = 0;
    			 for(Node node:c.nodes){
    				 netDelay +=  nodesDelays.get(node);
    			 }
    			 c.setTimingEdgeDelay(netDelay);
    		 }
    	 }
    }
      
    //dealing with required time: set to the max delay, i.e. max arrival time
    public Pair<Float, TimingVertex> calculateArrivalRequireAndSlack(){
    	this.timingGraph.resetRequiredAndArrivalTime();
    	this.timingGraph.computeArrivalTimesTopologicalOrder();
    	Pair<Float, TimingVertex> maxs = this.timingGraph.getMaxDelay();
    	this.timingGraph.setTimingRequirementTopologicalOrder(maxs.getFirst());
    	
    	return maxs;
    }
    
    public void getCriticalPathInfo(RoutableTimingGroupRouter router){
    	TimingVertex maxV = router.maxDelayAndTimingVertex.getSecond();
    	float maxDelay = router.maxDelayAndTimingVertex.getFirst();
    	System.out.println("Max delay: " + maxDelay);
    	
    	List<TimingEdge> criticalEdges = this.timingGraph.getCriticalTimingEdgesInOrder(maxV);
    	System.out.println(String.format("%-25s %s", "Critical TimingEdges:", criticalEdges));
    	
    	// print out string for timing vertex of inputs only
    	StringBuilder s = new StringBuilder();
    	s.append(String.format("%-26s", "of which critical inputs:"));
    	s.append("{");
    	int i = 0;
    	for(TimingEdge v:criticalEdges){
    		if(i % 2 == 0){
	    		s.append(v.getDst().getName());
	    		s.append(" ");
    		}
    		i++;
    	}
    	s.replace(s.length() - 1, s.length(), "");
    	s.append("}");
    	System.out.println(s);
    	
    	Map<TimingEdge, Connection> timingEdgeConnctionMap = router.timingEdgeConnectionMap;
    	
    	System.out.println("Detail delays:");
    	System.out.printf("------------------  -------------------  ----------  ---------------  --------  ---------- ---------------\n");
    	System.out.printf("%18s  %19s  %10s  %14s  %8s  %10s  %15s\n", 
        		"First TimingVertex", 
        		"Second TimingVertex", 
        		"Logic (ps)", 
        		"Intrasite (ps)", 
        		"Net (ps)",
        		"Total (ps)",
        		"Net name"
        		);     
    	for(TimingEdge e : criticalEdges){
    		System.out.printf("%18s  %19s  %10.1f  %14.1f  %8.1f  %10.1f  %15s\n", 
    				e.getSrc(),
    				e.getDst(),
    				e.getLogicDelay(),
    				e.getIntraSiteDelay(),
    				e.getNetDelay(),
    				e.getDelay(),
    				e.getNet() == null? "null" : e.getNet().getName());
    	}
    	System.out.printf("------------------  -------------------  ----------  ---------------  --------  ---------- ---------------\n");
    	
    	for(TimingEdge e : criticalEdges) {
    		if(timingEdgeConnctionMap.containsKey(e)){
    			System.out.println(timingEdgeConnctionMap.get(e));
    			List<ImmutableTimingGroup> groups = timingEdgeConnctionMap.get(e).timingGroups;
    			for(int iGroup = groups.size() -1; iGroup >= 0; iGroup--) {
    				System.out.println("\t " + groups.get(iGroup));
    			}
    		}
    		System.out.println();
    	}
    	
    }
    
    public float calculateCriticality(List<Connection> cons, 
    		float maxCriticality, float criticalityExponent, float maxDelay){
    	for(Connection c:cons){
    		c.resetCriticality();
    	}
    	
    	float maxCriti = 0;
    	for(Connection c : cons){
    		c.calculateCriticality(maxDelay, maxCriticality, criticalityExponent);
    		if(c.criticality > maxCriti)
    			maxCriti = c.criticality;
    	}
    	return maxCriti;
    }
    
    public boolean comparableFloat(Float a, float b){
    	return Math.abs(a - b) < Math.pow(10, -9);
    }
    
    
  //-----------------------------------------------------------------------------------------------

    /**
     * Builds the TimingModel and TimingGraph.
     * @return Indication of successful completion.
     */
    public boolean build() {
        timingModel.build();
        timingGraph.build();
        return postBuild();
    }

    private boolean postBuild() {
        timingGraph.removeClockCrossingPaths();
        timingGraph.buildGraphPaths(0);//(BUILD_GRAPH_PATHS_DEFAULT_PARAM);//default, 0 to build all paths
        timingGraph.computeArrivalTimes();//TopologicalOrder();//.computeArrivalTimes();
        timingGraph.computeSlacks();
        return true;
    }

    /**
     * Gets the TimingGraph object.
     * @return TimingGraph
     */
    public TimingGraph getTimingGraph() {
        return timingGraph;
    }

    /**
     * Gets the TimingModel object.
     * @return TimingModel
     */
    public TimingModel getTimingModel() {
        return timingModel;
    }

    /**
     * Gets the corresponding design used in creating this TimingManager.
     * @return Corresponding design used in creating this TimingManager.
     */
    public Design getDesign() {
        return design;
    }
    
    /**
     * Gets the corresponding device used in creating this TimingManager.
     * @return Corresponding device used in creating this TimingManager.
     */
    public Device getDevice() {
        return device;
    }
}
