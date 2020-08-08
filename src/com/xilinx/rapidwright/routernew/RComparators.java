package com.xilinx.rapidwright.routernew;

import java.util.Comparator;

public class RComparators {
   
	public static Comparator<RQueueElement> PRIORITY_COMPARATOR = new Comparator<RQueueElement>() {
        @Override
        public int compare(RQueueElement node1, RQueueElement node2) {
            if(node1.cost < node2.cost) {
            	return -1;
            } else {
            	return 1;
            }
        }
    };
    
	public static Comparator<RConnection>  FanoutBBConnection = new Comparator<RConnection>() {
    	@Override
    	public int compare(RConnection a, RConnection b) {
    		if(a.net.fanout < b.net.fanout){
    			return 1;
    		}else if(a.net.fanout == b.net.fanout){
    			if(a.boundingBox > b.boundingBox){
    				return 1;
    			}else if(a.boundingBox == b.boundingBox){
    				if(a.hashCode() > b.hashCode()){
    					return 1;
    				}else if(a.hashCode() < b.hashCode()){
    					return -1;
    				}else{
    					if(a != b) System.err.println("Failure: Error while comparing 2 connections. HashCode of Two Connections was identical");
    					return 0;
    				}
    			}else{
    				return -1;
    			}
    		}else{
    			return -1;
    		}
    	}
    };
    
	public static Comparator<RNetplus> FanoutNet = new Comparator<RNetplus>() {
    	@Override
    	public int compare(RNetplus n1, RNetplus n2) {
    		if(n1.fanout < n2.fanout){
    			return 1;
    		}else if(n1.fanout == n2.fanout){
    			if(n1.hpwl > n2.hpwl){
    				return 1;
    			}else if(n1.hpwl == n2.hpwl){
    				if(n1.hashCode() > n2.hashCode()){
    					return 1;
    				}else if(n1.hashCode() < n2.hashCode()){
    					return -1;
    				}else{
    					if(n1 != n2) System.err.println("Failure: Error while comparing 2 nets. HashCode of Two Nets was identical");
    					return 0;
    				}
    			}else{
    				return -1;
    			}
    		}else{
    			return -1;
    		}
    	}
    };
   
	public static Comparator<RConnection> ConnectionCriticality = new Comparator<RConnection>() {
    	@Override
    	public int compare(RConnection a, RConnection b) {
    		if(a.getCriticality() < b.getCriticality()){
    			return 1;
    		}else if(a.getCriticality() == b.getCriticality()) {
    			if(a.hashCode() > b.hashCode()){
    				return 1;
    			}else if(a.hashCode() < b.hashCode()){
    				return -1;
    			}else{
    				if(a != b) System.out.println("Failure: Error while comparing 2 connections. HashCode of Two Connections was identical");
    				return 0;
    			}
    		}else{
    			return -1;
    		}
    	}
    };
}
