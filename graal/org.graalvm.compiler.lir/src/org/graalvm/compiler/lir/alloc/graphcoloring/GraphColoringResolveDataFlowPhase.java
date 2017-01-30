package org.graalvm.compiler.lir.alloc.graphcoloring;

import static org.graalvm.compiler.core.common.GraalOptions.DetailedAsserts;
import static org.graalvm.compiler.lir.LIRValueUtil.asConstant;
import static org.graalvm.compiler.lir.LIRValueUtil.isConstantValue;
import static org.graalvm.compiler.lir.LIRValueUtil.isStackSlotValue;

import java.util.BitSet;
import java.util.List;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.debug.Debug.Scope;
import org.graalvm.compiler.debug.DebugCounter;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp;
import org.graalvm.compiler.lir.ssa.SSAUtil;
import org.graalvm.compiler.lir.ssa.SSAUtil.PhiValueVisitor;

import jdk.vm.ci.meta.Value;

public class GraphColoringResolveDataFlowPhase {

    private static final DebugCounter numPhiResolutionMoves = Debug.counter("SSA LSRA[numPhiResolutionMoves]");
    private static final DebugCounter numStackToStackMoves = Debug.counter("SSA LSRA[numStackToStackMoves]");
    private Chaitin allocator;

    GraphColoringResolveDataFlowPhase(Chaitin allocator) {
        this.allocator = allocator;
    }

    protected void resolveDataFlow() {
        try (Scope s = Debug.scope("GraphColoringMoveResolver")) {

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
                        if (Debug.isLogEnabled()) {
                            Debug.log("processing edge between B%d and B%d", fromBlock.getId(), toBlock.getId());
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
            if (Debug.isLogEnabled()) {
                Debug.log("inserting moves at end of fromBlock B%d", fromBlock.getId());
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
            if (Debug.isLogEnabled()) {
                Debug.log("inserting moves at beginning of toBlock B%d", toBlock.getId());
            }

            if (DetailedAsserts.getValue()) {
                assert allocator.getLIR().getLIRforBlock(fromBlock).get(0) instanceof StandardOp.LabelOp : "block does not start with a label";

                /*
                 * Because the number of predecessor edges matches the number of successor edges,
                 * blocks which are reached by switch statements may have be more than one
                 * predecessor but it will be guaranteed that all predecessors will be the same.
                 */
                for (AbstractBlockBase<?> predecessor : toBlock.getPredecessors()) {
                    assert fromBlock == predecessor : "all critical edges must be broken";
                }
            }

            moveResolver.setInsertPosition(allocator.getLIR().getLIRforBlock(toBlock), 1);
        }

    }

    protected void resolveCollectMappings(AbstractBlockBase<?> fromBlock, AbstractBlockBase<?> toBlock, AbstractBlockBase<?> midBlock, GraphColoringMoveResolver moveResolver) {

        if (toBlock.getPredecessorCount() > 1) {
            int toBlockFirstInstructionId = allocator.getFirstLirInstructionId(toBlock);
            int fromBlockLastInstructionId = allocator.getLastLirInstructionId(fromBlock) + 1;

            AbstractBlockBase<?> phiOutBlock = midBlock != null ? midBlock : fromBlock;
            List<LIRInstruction> instructions = allocator.getLIR().getLIRforBlock(phiOutBlock);
            int phiOutIdx = SSAUtil.phiOutIndex(allocator.getLIR(), phiOutBlock);
            int phiOutId = midBlock != null ? fromBlockLastInstructionId : instructions.get(phiOutIdx).id();
            // assert phiOutId >= 0;

            PhiValueVisitor visitor = new PhiValueVisitor() {

                @Override
                public void visit(Value phiIn, Value phiOut) {
                    // assert isRegister(phiOut) : "phiOut is a register: " + phiOut;
                    // assert isRegister(phiIn) : "phiIn is a register: " + phiIn;

                    if (isConstantValue(phiOut)) {
                        numPhiResolutionMoves.increment();
                        moveResolver.addMapping(asConstant(phiOut), phiIn);
                    } else {

                        if (!phiOut.equals(phiIn)) {
                            numPhiResolutionMoves.increment();
                            if (!(isStackSlotValue(phiIn) && isStackSlotValue(phiOut))) {
                                moveResolver.addMapping(phiOut, phiIn);
                            } else {
                                numStackToStackMoves.increment();
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

}
