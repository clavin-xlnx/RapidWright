package com.xilinx.rapidwright.routernew;

public enum RoutableType {
	// for congestion-driven Node router
	SOURCERR,
	SINKRR,
	INTERRR,
	
	//for timing-driven NodeGroup router
	PINFEED_O,
	PINFEED_I,
	WIRE,
	PINBOUNCE
}
