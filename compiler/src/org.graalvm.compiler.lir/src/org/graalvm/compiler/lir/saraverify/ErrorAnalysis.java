package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.saraverify.DefAnalysisInfo.Triple;

import jdk.vm.ci.meta.Value;

public class ErrorAnalysis {

    public static void analyse(LIR lir, DefAnalysisResult defAnalysisResult, Map<Node, DuSequenceWeb> mapping) {

        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();
        for (AbstractBlockBase<?> block : blocks) {

            // TODO: merge of DefAnalysisInfo
            DefAnalysisInfo defAnalysisInfo = new DefAnalysisInfo();
            ErrorAnalysisValueConsumer errorAnalysisValueConsumer = new ErrorAnalysisValueConsumer(mapping, defAnalysisInfo);

            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
            for (LIRInstruction instruction : instructions) {

                if (!instruction.isMoveOp()) {
                    // non copy instruction
                    // error analysis
                    instruction.visitEachInput(errorAnalysisValueConsumer);
                }

                // TODO: compute local flow
            }
        }
    }

    private static void useCheck(Value value, int useOperandPosition, LIRInstruction instruction, Map<Node, DuSequenceWeb> mapping, DefAnalysisInfo defAnalysisInfo) {
        Value location = SARAVerifyUtil.getValueIllegalValueKind(value);

        UseNode useNode = new UseNode(location, instruction, useOperandPosition);
        DuSequenceWeb mappedWeb = mapping.get(useNode);

        List<Triple> locationTriples = defAnalysisInfo.getLocationTriples(mappedWeb);

        if (locationTriples.stream().anyMatch(triple -> triple.getLocation().equals(location))) {
            // mapped value is in assumed location

            Triple staleTriple = defAnalysisInfo.getStaleTriple(location, mappedWeb);
            if (staleTriple != null) {
                // value is stale
                throw GraalError.shouldNotReachHere(
                                "instruction " + instruction + " uses stale value, " + staleTriple.getInstructionSequence()     //
                                                + " made " + mappedWeb + " in " + location + "become stale");
            }
            // mapped value is in assumed location and not stale ==> correct usage
        } else {
            // mapped value is not in assumed location
            Triple otherLocationTriple = locationTriples.stream()       //
                            .filter(triple -> defAnalysisInfo.getStaleTriple(triple.getLocation(), triple.getValue()) != null)  //
                            .findFirst().orElse(null);

            if (otherLocationTriple == null) {
                // mapped value is evicted
                List<Triple> evictedTriples = defAnalysisInfo.getEvictedTriples(mappedWeb);

                for (Triple triple : evictedTriples) {
                    throw GraalError.shouldNotReachHere("instruction " + instruction + " uses evicted value, "  //
                                    + triple.getInstructionSequence() + " evicted " + mappedWeb + " from "      //
                                    + triple.getLocation());
                }

            } else {
                // mapped value is in other location
                throw GraalError.shouldNotReachHere("instruction " + instruction + " uses wrong operand, but "  //
                                + otherLocationTriple.getInstructionSequence() + " defined " + mappedWeb + " in "   //
                                + otherLocationTriple.getLocation());
            }
        }
    }

    private static class ErrorAnalysisValueConsumer implements InstructionValueConsumer {

        private final Map<Node, DuSequenceWeb> mapping;
        private final DefAnalysisInfo defAnalysisInfo;
        private int useOperandPosition;

        public ErrorAnalysisValueConsumer(Map<Node, DuSequenceWeb> mapping, DefAnalysisInfo defAnalysisInfo) {
            this.mapping = mapping;
            this.defAnalysisInfo = defAnalysisInfo;
            useOperandPosition = 0;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            useCheck(value, useOperandPosition, instruction, mapping, defAnalysisInfo);
            useOperandPosition++;
        }
    }

}
