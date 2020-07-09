/*
 *
 * Copyright (c) 2018 Xilinx, Inc.
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

package com.xilinx.rapidwright.util;

import java.util.Comparator;

public class PairUtil {

    public static class CompareFirst<T extends Comparable<T>, U> implements Comparator<Pair<T,U>> {
        public int compare(Pair<T,U> a, Pair<T,U> b) {
            return a.getFirst().compareTo(b.getFirst());
        }
    }

    public static class CompareSecond<T, U extends Comparable<U>> implements Comparator<Pair<T,U>> {
        public int compare(Pair<T,U> a, Pair<T,U> b) {
            return a.getSecond().compareTo(b.getSecond());
        }
    }
}
