package com.xilinx.rapidwright.routernew;
/**
 * The configuration of the router parameters
 * @author yun
 *
 */
public class Configuration {
	//routing granularity option, i.e. Node-based or NodeGroup-based wavefront expansion step
	private RoutingGranularityOpt opt = RoutingGranularityOpt.NODEGROUP;
	//allowed max number of routing iterations
	private int nrOfTrials;
	//routing bounding box constraint
	private short bbRange;
	//Manhattan distance weight factor
	private float mdWeight;
	//hop weight factor
	private float hopWeight;
	//delay weight fator
	private float delayWeight;
	
	private float criticalityExp;
	private float minRerouteCriti;
	private int rerouteCritiPercentage;
	
	//initial present congestion penalty factor
	private float initial_pres_fac; 
	//multiplication factor for present congestion update
	private float pres_fac_mult; 
	//historical congestion penality fator
	private float hist_fac;
	//true timingDriven to enable timing-aware routing
	private boolean timingDriven;
	//true to enable partial routing
	private boolean partialRouting;
	//the flag to direct the dependency of delay info files
	private boolean hpcRun;
	
	public Configuration() {
		this.setNrOfTrials(100);
		this.setBbRange((short) 5);
		this.setMdWeight(2.5f);
		this.setHopWeight(0.7f);
		this.setDelayWeight(0.5f);
		this.setCriticalityExp(1);
		this.setMinRerouteCriti(0.85f);
		this.setRerouteCritiPercentage(3);
		this.setInitial_pres_fac(0.5f); 
		this.setPres_fac_mult(2f); 
		this.setAcc_fac(1f);
		this.setTimingDriven(false);
		this.setPartialRouting(false);
		this.setHpcRun(false);
	}
	
	public void customizeConfig(int startIndex, String[] arguments) {
		for(int i = startIndex; i < arguments.length; i++) {
			if(arguments[i].contains("routingGranularity")){
				short optNum = Short.parseShort(arguments[++i]);
				if(optNum == 3){
					this.setOpt(RoutingGranularityOpt.NODEGROUP);
				}else if(optNum == 2){
					this.setOpt(RoutingGranularityOpt.NODE);
				}else if(optNum == 1){
					this.setOpt(RoutingGranularityOpt.WIRE);
				}
				
			}else if(arguments[i].contains("bbRange")){
				this.setBbRange(Short.parseShort(arguments[++i]));
				
			}else if(arguments[i].contains("mdWeight")){
				this.setMdWeight(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].contains("hopWeight")){
				this.setHopWeight(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].contains("delayWeight")){
				this.setDelayWeight(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].contains("criticalityExp")) {
				this.setCriticalityExp(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].contains("minRerouteCriti")){
				this.setMinRerouteCriti(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].contains("rerouteCritiPercentage")){
				this.setRerouteCritiPercentage(Integer.parseInt(arguments[++i]));
				
			}else if(arguments[i].contains("initial_pres_fac")){
				this.setInitial_pres_fac(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].contains("pres_fac_mult")){
				this.setPres_fac_mult(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].contains("acc_fac")){
				this.setAcc_fac(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].contains("timingDriven")){
				this.setTimingDriven(true);
				
			}else if(arguments[i].contains("routabilityDriven")){
				this.setTimingDriven(false);
				
			}else if(arguments[i].contains("partialRouting")){
				this.setPartialRouting(true);
				
			}else if(arguments[i].contains("hpcRun")){
				this.setHpcRun(true);
			}
		}
	}
	
	private void setDelayWeight(float delayWeight) {
		this.delayWeight = delayWeight;
	}

	public float getDelayWeight() {
		return delayWeight;
	}
	
	public int getNrOfTrials() {
		return nrOfTrials;
	}

	public void setNrOfTrials(int nrOfTrials) {
		this.nrOfTrials = nrOfTrials;
	}

	public short getBbRange() {
		return bbRange;
	}

	public void setBbRange(short bbRange) {
		this.bbRange = bbRange;
	}

	public float getMdWeight() {
		return mdWeight;
	}

	public void setMdWeight(float mdWeight) {
		this.mdWeight = mdWeight;
	}

	public float getHopWeight() {
		return hopWeight;
	}

	public void setHopWeight(float hopWeight) {
		this.hopWeight = hopWeight;
	}

	public float getCriticalityExp() {
		return criticalityExp;
	}

	public void setCriticalityExp(float criticalityExp) {
		this.criticalityExp = criticalityExp;
	}

	public float getMinRerouteCriti() {
		return minRerouteCriti;
	}

	public void setMinRerouteCriti(float minRerouteCriti) {
		this.minRerouteCriti = minRerouteCriti;
	}

	public int getRerouteCritiPercentage() {
		return rerouteCritiPercentage;
	}

	public void setRerouteCritiPercentage(int rerouteCritiPercentage) {
		this.rerouteCritiPercentage = rerouteCritiPercentage;
	}
	
	public float getInitial_pres_fac() {
		return initial_pres_fac;
	}

	public void setInitial_pres_fac(float initial_pres_fac) {
		this.initial_pres_fac = initial_pres_fac;
	}

	public float getPres_fac_mult() {
		return pres_fac_mult;
	}

	public void setPres_fac_mult(float pres_fac_mult) {
		this.pres_fac_mult = pres_fac_mult;
	}

	public float getAcc_fac() {
		return hist_fac;
	}

	public void setAcc_fac(float acc_fac) {
		this.hist_fac = acc_fac;
	}

	public boolean isTimingDriven() {
		return timingDriven;
	}

	public void setTimingDriven(boolean timingDriven) {
		this.timingDriven = timingDriven;
	}

	public boolean isPartialRouting() {
		return partialRouting;
	}

	public void setPartialRouting(boolean partialRouting) {
		this.partialRouting = partialRouting;
	}

	public boolean isHpcRun() {
		return hpcRun;
	}

	public void setHpcRun(boolean hpcRun) {
		this.hpcRun = hpcRun;
	}

	public RoutingGranularityOpt getOpt() {
		return opt;
	}

	public void setOpt(RoutingGranularityOpt opt) {
		this.opt = opt;
	}
	
	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("Router: ");
		s.append("PathFinder-based connection router");
		s.append("\n");
		s.append("Routing granularity: " + this.opt);
		s.append("\n");
		s.append("Timing-driven: " + this.timingDriven);
		s.append("\n");
		s.append("Partial routing: " + this.partialRouting);
		s.append("\n");
		s.append("Bounding box range: " + this.bbRange);
		s.append("\n");
		s.append("Manhattan distance weight: " + this.mdWeight);
		s.append("\n");
		s.append("Hops weight: " + this.hopWeight);
		s.append("\n");
		s.append("Delay weight: " + this.delayWeight);
		s.append("\n");
		s.append("criticality exponent: " + this.criticalityExp);
		s.append("\n");
		s.append("reroute criticality threshold: " + this.minRerouteCriti);
		s.append("\n");
		s.append("reroute percentage: " + this.rerouteCritiPercentage);
		s.append("\n");
		s.append("initial pres fac: " + this.initial_pres_fac);
		s.append("\n");
		s.append("pres fac mult: " + this.pres_fac_mult);
		s.append("\n");
		s.append("acc fac: " + this.hist_fac);
		
		return s.toString();
	}
}
