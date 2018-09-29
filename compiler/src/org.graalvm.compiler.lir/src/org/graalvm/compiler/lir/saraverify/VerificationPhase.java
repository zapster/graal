package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class VerificationPhase extends LIRPhase<AllocationContext> {

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        runVerification(lirGenRes, context);
    }

    protected static void runVerification(LIRGenerationResult lirGenRes, AllocationContext context) {
        assert UniqueInstructionVerifier.verify(lirGenRes);

        AnalysisResult inputResult = context.contextLookup(AnalysisResult.class);

        if (inputResult == null) {
            // no input du-sequences were created by the RegisterAllocationVerificationPhase
            return;
        }

        LIR lir = lirGenRes.getLIR();
        DebugContext debugContext = lir.getDebug();
        List<DuSequenceWeb> inputDuSequenceWebs = DuSequenceAnalysis.createDuSequenceWebs(inputResult.getDuSequences());

        assert inputDuSequenceWebs.stream().allMatch(web -> web.getDefNodes().size() == 1);

        Map<Constant, DummyConstDef> dummyConstDefs = inputResult.getDummyConstDefs();
        Map<Register, DummyRegDef> dummyRegDefs = inputResult.getDummyRegDefs();
        BlockMap<List<Value>> blockPhiInValues = inputResult.getBlockPhiInValues();
        BlockMap<List<Value>> blockPhiOutValues = inputResult.getBlockPhiOutValues();

        if (GraphPrinter.Options.SARAVerifyGraph.getValue(debugContext.getOptions())) {
            GraphPrinter.printGraphs(inputResult.getDuSequences(), inputDuSequenceWebs, debugContext);
        }

        RegisterArray callerSaveRegisters = lirGenRes.getRegisterConfig().getCallerSaveRegisters();

        Map<Node, DuSequenceWeb> mapping = generateMapping(lir, inputDuSequenceWebs, dummyRegDefs,
                        dummyConstDefs, blockPhiInValues, blockPhiOutValues);
        DefAnalysisResult defAnalysisResult = DefAnalysis.analyse(lir, mapping,
                        callerSaveRegisters, dummyRegDefs, dummyConstDefs, blockPhiInValues, blockPhiOutValues);
        ErrorAnalysis.analyse(lir, defAnalysisResult, mapping, dummyRegDefs, dummyConstDefs,
                        callerSaveRegisters, blockPhiInValues, blockPhiOutValues);
    }

    private static Map<Node, DuSequenceWeb> generateMapping(LIR lir, List<DuSequenceWeb> webs, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs,
                    BlockMap<List<Value>> blockPhiInValues, BlockMap<List<Value>> blockPhiOutValues) {
        Map<Node, DuSequenceWeb> map = new HashMap<>();

        MappingDefInstructionValueConsumer defConsumer = new MappingDefInstructionValueConsumer(map, webs);
        MappingUseInstructionValueConsumer useConsumer = new MappingUseInstructionValueConsumer(map, webs);

        int negativeInstructionID = -1;

        for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
            for (LIRInstruction instruction : lir.getLIRforBlock(block)) {
                defConsumer.defOperandPosition = 0;
                useConsumer.useOperandPosition = 0;

                // replaces id of instructions that have the id -1 with a decrementing negative id
                if (instruction.id() == -1) {
                    instruction.setId(negativeInstructionID);
                    negativeInstructionID--;
                }

                if (!instruction.isValueMoveOp()) {
                    SARAVerifyUtil.visitValues(instruction, defConsumer, useConsumer);
                }
            }
        }

        // generate mappings for dummy constant definitions
        for (Entry<Constant, DummyConstDef> dummyConstDef : dummyConstDefs.entrySet()) {
            insertDefMapping(new ConstantValue(ValueKind.Illegal, dummyConstDef.getKey()), dummyConstDef.getValue(), 0, webs, map);
        }

        // generate mappings for dummy register definitions
        for (Entry<Register, DummyRegDef> dummyRegDef : dummyRegDefs.entrySet()) {
            insertDefMapping(dummyRegDef.getKey().asValue(), dummyRegDef.getValue(), 0, webs, map);
        }

        // phi mappings (def nodes in map have value from input)
        generatePhiMapping(lir, webs, map, blockPhiInValues, blockPhiOutValues);

        assert assertMappings(webs, map);

        return map;
    }

    private static boolean assertMappings(List<DuSequenceWeb> webs, Map<Node, DuSequenceWeb> map) {
        assert webs.stream()        //
                        .flatMap(web -> web.getDefNodes().stream())     //
                        .allMatch(node -> map.keySet().stream()     //
                                        .anyMatch(keyNode -> {
                                            if (!keyNode.isDefNode()) {
                                                return false;
                                            }
                                            DefNode defNode = (DefNode) keyNode;
                                            return node.equalsInstructionAndPosition(defNode);
                                        })) : "unmapped definitions";

        assert webs.stream()        //
                        .flatMap(web -> web.getUseNodes().stream())     //
                        .allMatch(node -> map.keySet().stream()     //
                                        .anyMatch(keyNode -> {
                                            if (!keyNode.isUseNode()) {
                                                return false;
                                            }
                                            UseNode useNode = (UseNode) keyNode;
                                            return node.equalsInstructionAndPosition(useNode);
                                        })) : "unmapped usages";

        return true;
    }

    private static void generatePhiMapping(LIR lir, List<DuSequenceWeb> webs, Map<Node, DuSequenceWeb> map, BlockMap<List<Value>> blockPhiInValues, BlockMap<List<Value>> blockPhiOutValues) {
        for (AbstractBlockBase<?> block : lir.getControlFlowGraph().getBlocks()) {
            ArrayList<LIRInstruction> instructions = lir.getLIRforBlock(block);

            List<Value> phiInValues = blockPhiInValues.get(block);

            // operand position assumes that label op incoming values are all phi values
            int operandPosition = 0;
            if (phiInValues != null) {
                assert instructions.get(0) instanceof LabelOp : "First instruction is no label instruction.";
                assert ((LabelOp) instructions.get(0)).getPhiSize() == phiInValues.size()    //
                : "Number of phi values differs from phi size for label instruction.";

                for (Value phiInValue : phiInValues) {
                    // def mapping for phi in values
                    insertDefMapping(phiInValue, instructions.get(0), operandPosition, webs, map);
                    operandPosition++;
                }
            }

            List<Value> phiOutValues = blockPhiOutValues.get(block);

            operandPosition = 0;
            if (phiOutValues != null) {
                assert instructions.get(instructions.size() - 1) instanceof JumpOp : "Last instruction is no jump instruction.";

                for (Value phiOutValue : phiOutValues) {
                    // use mapping for phi out values
                    insertUseMapping(phiOutValue, instructions.get(instructions.size() - 1), operandPosition, webs, map);
                    operandPosition++;
                }
            }
        }
    }

    private static void insertDefMapping(Value value, LIRInstruction instruction, int defOperandPosition, List<DuSequenceWeb> webs, Map<Node, DuSequenceWeb> map) {
        DefNode defNode = new DefNode(value, instruction, defOperandPosition);

        Optional<DuSequenceWeb> webOptional = webs.stream()     //
                        .filter(web -> web.getDefNodes().stream().anyMatch(node -> node.equalsInstructionAndPosition(defNode)))      //
                        .findAny();

        if (webOptional.isPresent()) {
            map.put(defNode, webOptional.get());
        }
    }

    private static void insertUseMapping(Value value, LIRInstruction instruction, int useOperandPosition, List<DuSequenceWeb> webs, Map<Node, DuSequenceWeb> map) {
        UseNode useNode = new UseNode(value, instruction, useOperandPosition);

        Optional<DuSequenceWeb> webOptional = webs.stream()     //
                        .filter(web -> web.getUseNodes().stream().anyMatch(node -> node.equalsInstructionAndPosition(useNode)))      //
                        .findAny();

        if (webOptional.isPresent()) {
            map.put(useNode, webOptional.get());
        }
    }

    static class MappingDefInstructionValueConsumer implements InstructionValueConsumer {

        private int defOperandPosition = 0;
        private Map<Node, DuSequenceWeb> map;
        private List<DuSequenceWeb> webs;

        public MappingDefInstructionValueConsumer(Map<Node, DuSequenceWeb> map, List<DuSequenceWeb> webs) {
            this.map = map;
            this.webs = webs;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value)) {
                // value is part of a composite value
                defOperandPosition++;
                return;
            }

            insertDefMapping(value, instruction, defOperandPosition, webs, map);

            defOperandPosition++;
        }

    }

    static class MappingUseInstructionValueConsumer implements InstructionValueConsumer {

        private int useOperandPosition = 0;
        private Map<Node, DuSequenceWeb> map;
        private List<DuSequenceWeb> webs;

        public MappingUseInstructionValueConsumer(Map<Node, DuSequenceWeb> map, List<DuSequenceWeb> webs) {
            this.map = map;
            this.webs = webs;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value) || flags.contains(OperandFlag.UNINITIALIZED)) {
                // value is part of a composite value, uninitialized or a constant
                useOperandPosition++;
                return;
            }

            insertUseMapping(value, instruction, useOperandPosition, webs, map);

            useOperandPosition++;
        }
    }
}
