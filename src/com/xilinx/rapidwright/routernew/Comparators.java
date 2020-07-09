package com.xilinx.rapidwright.routernew;

import java.util.Comparator;

public class Comparators {
    @SuppressWarnings("rawtypes")
	public static Comparator<QueueElement> PRIORITY_COMPARATOR = new Comparator<QueueElement>() {
        @Override
        public int compare(QueueElement node1, QueueElement node2) {
            if(node1.cost < node2.cost) {
            	return -1;
            } else {
            	return 1;
            }
        }
    };
    
    @SuppressWarnings("rawtypes")
	public static Comparator<Connection>  FanoutBBConnection = new Comparator<Connection>() {
    	@Override
    	public int compare(Connection a, Connection b) {
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
    
	@SuppressWarnings("rawtypes")
	public static Comparator<Netplus> FanoutNet = new Comparator<Netplus>() {
    	@Override
    	public int compare(Netplus n1, Netplus n2) {
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
   
    @SuppressWarnings("rawtypes")
	public static Comparator<Connection> ConnectionCriticality = new Comparator<Connection>() {
    	@Override
    	public int compare(Connection a, Connection b) {
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
