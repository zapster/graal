package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.saraverify.DefAnalysis.DefAnalysisNonCopyValueConsumer;
import org.graalvm.compiler.lir.saraverify.DefAnalysis.DefAnalysisTempValueConsumer;
import org.graalvm.compiler.lir.saraverify.DefAnalysisInfo.Triple;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;

import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class ErrorAnalysis {

    public static void analyse(LIR lir, DefAnalysisResult defAnalysisResult, Map<Node, DuSequenceWeb> mapping, Map<Constant, DummyConstDef> dummyConstDefs, RegisterArray callerSaveRegisters) {

        Map<AbstractBlockBase<?>, DefAnalysisInfo> blockInfos = defAnalysisResult.getBlockSets();

        // create a list of register values with value kind illegal of caller saved registers
        List<Value> callerSaveRegisterValues = callerSaveRegisters.asList() //
                        .stream().map(register -> register.asValue(ValueKind.Illegal)).collect(Collectors.toList());

        List<Value> tempValues = new ArrayList<>();

        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();
        for (AbstractBlockBase<?> block : blocks) {

            DefAnalysisInfo defAnalysisInfo;
            if (block.getId() == 0) {
                defAnalysisInfo = new DefAnalysisInfo();
                DefAnalysis.initializeDefAnalysisInfoWithConstants(mapping, dummyConstDefs, defAnalysisInfo);
            } else {
                defAnalysisInfo = DefAnalysis.mergeDefAnalysisInfo(blockInfos, block.getPredecessors());
            }

            ErrorAnalysisValueConsumer errorAnalysisValueConsumer = new ErrorAnalysisValueConsumer(mapping, defAnalysisInfo);

            DefAnalysisNonCopyValueConsumer nonCopyValueConsumer = new DefAnalysisNonCopyValueConsumer(defAnalysisInfo, mapping);
            DefAnalysisTempValueConsumer tempValueConsumer = new DefAnalysisTempValueConsumer(tempValues);

            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);
            for (LIRInstruction instruction : instructions) {
                errorAnalysisValueConsumer.useOperandPosition = 0;

                if (!instruction.isMoveOp()) {
                    // non copy instruction
                    // error analysis
                    SARAVerifyUtil.visitValues(instruction, null, errorAnalysisValueConsumer);
                }

                // compute local flow
                DefAnalysis.computeLocalFlowInstruction(defAnalysisInfo, callerSaveRegisterValues, tempValues, nonCopyValueConsumer, tempValueConsumer, instruction);
            }
        }
    }

    private static void useCheck(Value value, int useOperandPosition, LIRInstruction instruction, Map<Node, DuSequenceWeb> mapping, DefAnalysisInfo defAnalysisInfo) {

        UseNode useNode = new UseNode(value, instruction, useOperandPosition);
        DuSequenceWeb mappedWeb = mapping.get(useNode);

        assert mappedWeb != null : "no mapping found for usage";

        List<Triple> locationTriples = defAnalysisInfo.getLocationTriples(mappedWeb);

        Value location = SARAVerifyUtil.getValueIllegalValueKind(value);

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
                            .filter(triple -> defAnalysisInfo.getStaleTriple(triple.getLocation(), triple.getValue()) == null)  //
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
            if (flags.contains(OperandFlag.UNINITIALIZED) || ValueUtil.isIllegal(value) || LIRValueUtil.isConstantValue(value)) {
                useOperandPosition++;
                return;
            }

            useCheck(value, useOperandPosition, instruction, mapping, defAnalysisInfo);
            useOperandPosition++;
        }
    }

}
