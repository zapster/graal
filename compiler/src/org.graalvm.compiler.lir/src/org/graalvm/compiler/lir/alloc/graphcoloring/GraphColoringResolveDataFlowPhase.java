/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.lir.alloc.graphcoloring;

import static org.graalvm.compiler.lir.LIRValueUtil.asConstant;
import static org.graalvm.compiler.lir.LIRValueUtil.isConstantValue;
import static org.graalvm.compiler.lir.LIRValueUtil.isStackSlotValue;

import java.util.BitSet;
import java.util.List;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Scope;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp;
import org.graalvm.compiler.lir.alloc.graphcoloring.GraphColoringPhase.Options;
import org.graalvm.compiler.lir.ssa.SSAUtil;
import org.graalvm.compiler.lir.ssa.SSAUtil.PhiValueVisitor;

import jdk.vm.ci.meta.Value;

public class GraphColoringResolveDataFlowPhase {

    private static final CounterKey numPhiResolutionMoves = DebugContext.counter("GCRA[numPhiResolutionMoves]");
    private static final CounterKey numStackToStackMoves = DebugContext.counter("GCRA[numStackToStackMoves]");
    private Chaitin allocator;
    private final DebugContext debug;

    GraphColoringResolveDataFlowPhase(Chaitin allocator) {
        this.allocator = allocator;
        this.debug = allocator.debug;
    }

    protected void resolveDataFlow() {
        try (Scope s = debug.scope("GraphColoringMoveResolver")) {

            GraphColoringMoveResolver moveResolver = new GraphColoringMoveResolver(allocator);
            BitSet blockCompleted = new BitSet(allocator.blockCount());

            resolveDataFlow0(moveResolver, blockCompleted);
        }

    }

    private void resolveDataFlow0(GraphColoringMoveResolver moveResolver, BitSet blockCompleted) {

        BitSet alreadyResolved = new BitSet(allocator.blockCount());

        for (AbstractBlockBase<?> fromBlock : allocator.getLIR().getControlFlowGraph().getBlocks()) {
            if (!blockCompleted.get(fromBlock.getId())) {
                alreadyResolved.clear();
                alreadyResolved.or(blockCompleted);

                for (AbstractBlockBase<?> toBlock : fromBlock.getSuccessors()) {

                    /*
                     * Check for duplicate edges between the same blocks (can happen with switch
                     * blocks).
                     */
                    if (!alreadyResolved.get(toBlock.getId())) {
                        if (debug.isLogEnabled()) {
                            debug.log("processing edge between B%d and B%d", fromBlock.getId(), toBlock.getId());
                        }

                        alreadyResolved.set(toBlock.getLinearScanNumber());

                        // collect all intervals that have been split between
                        // fromBlock and toBlock
                        resolveCollectMappings(fromBlock, toBlock, null, moveResolver);
                        if (moveResolver.hasMappings()) {
                            resolveFindInsertPos(fromBlock, toBlock, moveResolver);
                            moveResolver.resolveAndAppendMoves();
                        }
                    }
                }
            }
        }
    }

    private void resolveFindInsertPos(AbstractBlockBase<?> fromBlock, AbstractBlockBase<?> toBlock, GraphColoringMoveResolver moveResolver) {
        if (fromBlock.getSuccessorCount() <= 1) {
            if (debug.isLogEnabled()) {
                debug.log("inserting moves at end of fromBlock B%d", fromBlock.getId());
            }

            List<LIRInstruction> instructions = allocator.getLIR().getLIRforBlock(fromBlock);
            LIRInstruction instr = instructions.get(instructions.size() - 1);
            if (instr instanceof StandardOp.JumpOp) {
                // insert moves before branch
                moveResolver.setInsertPosition(instructions, instructions.size() - 1);
            } else {
                moveResolver.setInsertPosition(instructions, instructions.size());
            }

        } else {
            if (debug.isLogEnabled()) {
                debug.log("inserting moves at beginning of toBlock B%d", toBlock.getId());
            }

            assert verifyEdge(fromBlock, toBlock);

            moveResolver.setInsertPosition(allocator.getLIR().getLIRforBlock(toBlock), 1);
        }

    }

    private boolean verifyEdge(AbstractBlockBase<?> fromBlock, AbstractBlockBase<?> toBlock) {
        assert allocator.getLIR().getLIRforBlock(fromBlock).get(0) instanceof StandardOp.LabelOp : "block does not start with a label";

        /*
         * Because the number of predecessor edges matches the number of successor edges, blocks
         * which are reached by switch statements may have be more than one predecessor but it will
         * be guaranteed that all predecessors will be the same.
         */
        for (AbstractBlockBase<?> predecessor : toBlock.getPredecessors()) {
            assert fromBlock == predecessor : "all critical edges must be broken";
        }
        return true;
    }

    protected void resolveCollectMappings(AbstractBlockBase<?> fromBlock, AbstractBlockBase<?> toBlock, AbstractBlockBase<?> midBlock, GraphColoringMoveResolver moveResolver) {

        if (toBlock.getPredecessorCount() > 1) {
            int toBlockFirstInstructionId = allocator.getFirstLirInstructionId(toBlock);
            int fromBlockLastInstructionId = allocator.getLastLirInstructionId(fromBlock) + 1;

            if (Options.LIROptGcIrSpilling.getValue(allocator.getLIR().getOptions())) {
                Interval[] intervals = allocator.getIntervals();
                for (int i = 0; i < intervals.length; i++) {
                    if (intervals[i] != null) {
                        if (needsMapping(fromBlockLastInstructionId, toBlockFirstInstructionId, intervals[i])) {

                            moveResolver.addMapping(intervals[i].getSlot(), intervals[i].getLocation());
                        }
                    }
                }

            }

            AbstractBlockBase<?> phiOutBlock = midBlock != null ? midBlock : fromBlock;
            // List<LIRInstruction> instructions = allocator.getLIR().getLIRforBlock(phiOutBlock);
            // int phiOutIdx = SSAUtil.phiOutIndex(allocator.getLIR(), phiOutBlock);
            // int phiOutId = midBlock != null ? fromBlockLastInstructionId :
            // instructions.get(phiOutIdx).id();
            // assert phiOutId >= 0;

            PhiValueVisitor visitor = new PhiValueVisitor() {

                @Override
                public void visit(Value phiIn, Value phiOut) {
                    // assert isRegister(phiOut) : "phiOut is a register: " + phiOut;
                    // assert isRegister(phiIn) : "phiIn is a register: " + phiIn;

                    if (isConstantValue(phiOut)) {
                        numPhiResolutionMoves.increment(debug);
                        moveResolver.addMapping(asConstant(phiOut), phiIn);
                    } else {

                        if (!phiOut.equals(phiIn)) {
                            numPhiResolutionMoves.increment(debug);
                            if (!(isStackSlotValue(phiIn) && isStackSlotValue(phiOut))) {
                                moveResolver.addMapping(phiOut, phiIn);
                            } else {
                                numStackToStackMoves.increment(debug);
                                moveResolver.addMapping(phiOut, phiIn);
                            }
                        }
                    }
                }
            };

            SSAUtil.forEachPhiValuePair(allocator.getLIR(), toBlock, phiOutBlock, visitor);
            SSAUtil.removePhiOut(allocator.getLIR(), phiOutBlock);
        }
    }

    private static boolean needsMapping(int fromBlockLastInstructionId, int toBlockFirstInstructionId, Interval inter) {

        if (inter.isSpilled()) { // only needed if spilled
            if (fromBlockLastInstructionId >= inter.getDef()) { // no mapping if def in beginning of
                                                                // next block
                if (inter.isInLiveRange(toBlockFirstInstructionId)) { // alive in to Block
                    if (!inter.isInLiveRange(fromBlockLastInstructionId)) { // not alive in from
                        return true;                                         // block

                    }
                }

            }

        }

        return false;
    }

}
