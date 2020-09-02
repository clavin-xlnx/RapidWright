/*
 *
 * Copyright (c) 2020 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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

package com.xilinx.rapidwright.timing.delayestimator;

import com.xilinx.rapidwright.timing.ImmutableTimingGroup;

/**
 * To estimate a route delay from a TG to a site-pin  TG.
 */
public interface DelayEstimator {
    /**
     * Find an estimated min delay of a path from a TG to a TG of an input site pin.
     * @param TG Timing group for the begin of the path. It can be any timing group except an input site pin.
     * @param sinkPin Timing grooup for the end of the path. It must be an input site pin.
     * @return An estimated delay including the delay of the TG and sinkPin.
     */
    public short getMinDelayToSinkPin (ImmutableTimingGroup TG, ImmutableTimingGroup sinkPin);
    public boolean load (String fileName);
    public boolean store (String fileName);
}

