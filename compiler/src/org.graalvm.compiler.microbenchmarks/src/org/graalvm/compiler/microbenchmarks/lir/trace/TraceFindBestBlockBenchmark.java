/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.microbenchmarks.lir.trace;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.lir.alloc.trace.lsra.TraceLinearScanWalker;
import org.graalvm.compiler.microbenchmarks.graal.GraalBenchmark;
import org.graalvm.compiler.nodes.cfg.Block;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;

@Warmup(iterations = 15)
@State(Scope.Thread)
public class TraceFindBestBlockBenchmark extends GraalBenchmark {

    public TraceFindBestBlockBenchmark() {
        blocks = new AbstractBlockBase<?>[props.length];
        int i = 0;
        for (double d : props) {
            Block block = new Block(null);
            blocks[i] = block;
            block.setProbability(d);
            i++;
        }
        table = new AbstractBlockBase[calls.length][];
    }

    public static class State extends ControlFlowGraphState {
        @MethodDescString
        @Param({
                "java.lang.String#equals",
                "java.util.HashMap#computeIfAbsent"
        })
        public String method;
    }
    AbstractBlockBase<?>[][] table;

    double[] props = {0.7462303357954825, 0.8375200177278143, 0.8291448175505362, 0.8208533693750307, 0.8080229956209518, 0.8080229956209518, 0.8080229956209518, 0.8080229956209518, 0.604089407658821, 0.604089407658821, 0.8080229956209517, 0.8080229956209517, 50.88715059798553, 50.091757430568364, 50.091757430568364, 50.091757430568364, 50.091757430568364, 37.44930557522955, 37.44930557522955, 50.091757430568364, 50.091757430568364};
    final AbstractBlockBase<?>[] blocks;
    int[][] calls = {
            {0, 3},
            {0, 3},
            {2, 3},
            {0, 3},
            {1, 3},
            {0, 3},
            {1, 3},
            {1, 3},
            {1, 3},
            {1, 3},
            {3, 5},
            {3, 4},
            {3, 5},
            {3, 7},
            {3, 11}
    };


    @Benchmark
    public ArrayList<AbstractBlockBase<?>> findBestBlocks() {
        ArrayList<AbstractBlockBase<?>> res = new ArrayList<>(calls.length);
        for (int[] pair : calls) {
            res.add(TraceLinearScanWalker.findBestBlockBetween(pair[0], pair[1], blocks));
        }
        return res;
    }

    @Benchmark
    public ArrayList<AbstractBlockBase<?>> lookupBestBlocks() {
        ArrayList<AbstractBlockBase<?>> res = new ArrayList<>(calls.length);
        for (int[] pair : calls) {
            res.add(TraceLinearScanWalker.lookupBestBlockBetween(pair[0], pair[1], blocks, table));
        }
        return res;
    }
}
