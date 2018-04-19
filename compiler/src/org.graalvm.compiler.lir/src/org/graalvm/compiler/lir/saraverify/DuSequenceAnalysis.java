package org.graalvm.compiler.lir.saraverify;

import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.StandardOp.LoadConstantOp;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.ssa.SSAUtil;
import org.graalvm.compiler.lir.ssa.SSAUtil.PhiValueVisitor;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class DuSequenceAnalysis {

    public static class DummyConstDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyConstDef> TYPE = LIRInstructionClass.create(DummyConstDef.class);
        protected Constant constant;

        public DummyConstDef(Constant constant) {
            super(TYPE);
            this.constant = constant;
        }

        public Constant getValue() {
            return constant;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

    }

    public static class DummyRegDef extends LIRInstruction {
        public static final LIRInstructionClass<DummyRegDef> TYPE = LIRInstructionClass.create(DummyRegDef.class);
        @Def({REG}) protected AllocatableValue value;

        public DummyRegDef(AllocatableValue value) {
            super(TYPE);
            this.value = value;
        }

        public void setValue(AllocatableValue value) {
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    private int defOperandPosition;
    private int useOperandPosition;

    private Map<LIRInstruction, Integer> instructionDefOperandCount;
    private Map<LIRInstruction, Integer> instructionUseOperandCount;

    private Set<Node> nodes;

    public static final String ERROR_MSG_PREFIX = "SARA verify error: ";

    public static final CounterKey skippedCompilationUnits = DebugContext.counter("SARAVerify[skipped]");
    public static final CounterKey executedCompilationUnits = DebugContext.counter("SARAVerify[executed]");

    public AnalysisResult determineDuSequences(LIRGenerationResult lirGenRes, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        LIR lir = lirGenRes.getLIR();
        AbstractBlockBase<?>[] blocks = lir.getControlFlowGraph().getBlocks();
        AbstractBlockBase<?> startBlock = lir.getControlFlowGraph().getStartBlock();

        int blockCount = blocks.length;
        BitSet blockQueue = new BitSet(blockCount);

        Map<Value, Set<DefNode>> duSequences = new HashMap<>();
        Map<AbstractBlockBase<?>, Map<Value, Set<Node>>> blockUnfinishedDuSequences = new HashMap<>();

        initializeCollections();

        blockQueue.set(0, blockCount);

        Set<AbstractBlockBase<?>> visited = new HashSet<>();

        while (!blockQueue.isEmpty()) {
            int blockIndex = blockQueue.previousSetBit(blockCount);
            AbstractBlockBase<?> block = blocks[blockIndex];
            blockQueue.clear(blockIndex);
            visited.add(block);

            Map<Value, Set<Node>> mergedUnfinishedDuSequences = mergeMaps(blockUnfinishedDuSequences, block.getSuccessors());
            determineDuSequences(lir, block, lir.getLIRforBlock(block), duSequences, mergedUnfinishedDuSequences, startBlock);

            Map<Value, Set<Node>> previousUnfinishedDuSequences = blockUnfinishedDuSequences.get(block);

            if (!mergedUnfinishedDuSequences.equals(previousUnfinishedDuSequences)) {
                blockUnfinishedDuSequences.put(block, mergedUnfinishedDuSequences);

                for (AbstractBlockBase<?> predecessor : block.getPredecessors()) {
                    int predecessorIndex = predecessor.getId();
                    blockQueue.set(predecessorIndex);
                }
            }
        }

        assert Arrays.stream(blocks).allMatch(block -> visited.contains(block)) : "Not all blocks were visited during the Du-Sequence analysis.";

        Map<Value, Set<Node>> startBlockUnfinishedDuSequences = blockUnfinishedDuSequences.get(startBlock);
        analyseUndefinedValues(duSequences, startBlockUnfinishedDuSequences, registerAttributes, dummyRegDefs, dummyConstDefs);

        // TODO: assertion for hashcode collision of nodes
// assert nodes.size() == nodes.stream().mapToInt(node -> node.hashCode()).distinct().count() //
// : "Hashcode collision of nodes.";

        return new AnalysisResult(duSequences, instructionDefOperandCount, instructionUseOperandCount, dummyRegDefs, dummyConstDefs);
    }

    public AnalysisResult determineDuSequences(List<LIRInstruction> instructions, RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs,
                    Map<Constant, DummyConstDef> dummyConstDefs) {
        initializeCollections();

        // analysis of given instructions
        Map<Value, Set<DefNode>> duSequences = new HashMap<>();
        Map<Value, Set<Node>> unfinishedDuSequences = new HashMap<>();
        determineDuSequences(null, null, instructions, duSequences, unfinishedDuSequences, null);

        // analysis of dummy instructions
        analyseUndefinedValues(duSequences, unfinishedDuSequences, registerAttributes,
                        dummyRegDefs, dummyConstDefs);

        return new AnalysisResult(duSequences, instructionDefOperandCount, instructionUseOperandCount, dummyRegDefs, dummyConstDefs);
    }

    private void determineDuSequences(LIR lir, AbstractBlockBase<?> block, List<LIRInstruction> instructions, Map<Value, Set<DefNode>> duSequences,
                    Map<Value, Set<Node>> unfinishedDuSequences, AbstractBlockBase<?> startBlock) {
        DefInstructionValueConsumer defConsumer = new DefInstructionValueConsumer(duSequences, unfinishedDuSequences);
        UseInstructionValueConsumer useConsumer = new UseInstructionValueConsumer(unfinishedDuSequences);

        List<LIRInstruction> reverseInstructions = new ArrayList<>(instructions);
        Collections.reverse(reverseInstructions);

        for (LIRInstruction inst : reverseInstructions) {
            defOperandPosition = 0;
            useOperandPosition = 0;

            if (inst instanceof JumpOp) {
                // jump
                JumpOp jumpInst = (JumpOp) inst;

                if (jumpInst.getPhiSize() > 0) {
                    // phi
                    PhiValueVisitor visitor = new SARAVerifyPhiValueVisitor(unfinishedDuSequences, inst);
                    SSAUtil.forEachPhiValuePair(lir, block.getSuccessors()[0], block, visitor);
                }
            } else if (inst.isValueMoveOp()) {
                // value move
                ValueMoveOp moveInst = (ValueMoveOp) inst;
                insertMoveNode(unfinishedDuSequences, moveInst.getResult(), moveInst.getInput(), inst);
            } else if (inst.isLoadConstantOp()) {
                // load constant
                LoadConstantOp loadConstantOp = (LoadConstantOp) inst;
                insertMoveNode(unfinishedDuSequences, loadConstantOp.getResult(), new ConstantValue(ValueKind.Illegal, loadConstantOp.getConstant()), inst);
            } else if (block == startBlock || !(inst instanceof LabelOp)) {
                visitValues(inst, defConsumer, useConsumer, useConsumer);

                instructionDefOperandCount.put(inst, defOperandPosition);
                instructionUseOperandCount.put(inst, useOperandPosition);
            }
        }
    }

    private void initializeCollections() {
        instructionDefOperandCount = new IdentityHashMap<>();
        instructionUseOperandCount = new IdentityHashMap<>();
        nodes = new HashSet<>();
    }

    public static <KeyType, SetType> Set<SetType> getOrCreateSet(Map<KeyType, Set<SetType>> map, KeyType key) {
        Set<SetType> set = map.get(key);

        if (set == null) {
            set = new HashSet<>();
            map.put(key, set);
        }

        return set;
    }

    public static <T, U, V> Map<U, Set<V>> mergeMaps(Map<T, Map<U, Set<V>>> map, T[] mergeKeys) {
        Map<U, Set<V>> mergedMap = new HashMap<>();

        for (T mergeKey : mergeKeys) {
            Map<U, Set<V>> mergeValueMap = map.get(mergeKey);

            if (mergeValueMap != null) {
                for (Entry<U, Set<V>> entry : mergeValueMap.entrySet()) {
                    Set<V> mergedMapValue = mergedMap.get(entry.getKey());

                    if (mergedMapValue == null) {
                        mergedMapValue = new HashSet<>();
                        mergedMap.put(entry.getKey(), mergedMapValue);
                    }

                    Set<V> newValues = entry.getValue().stream().filter(x -> !(mergedMap.get(entry.getKey()).contains(x))).collect(Collectors.toSet());
                    mergedMapValue.addAll(newValues);
                }
            }
        }
        return mergedMap;
    }

    private static void analyseUndefinedValues(Map<Value, Set<DefNode>> duSequences, Map<Value, Set<Node>> unfinishedDuSequences,
                    RegisterAttributes[] registerAttributes, Map<Register, DummyRegDef> dummyRegDefs, Map<Constant, DummyConstDef> dummyConstDefs) {

        for (Entry<Value, Set<Node>> entry : unfinishedDuSequences.entrySet()) {
            Value value = entry.getKey();
            LIRInstruction dummyDef;

            if (LIRValueUtil.isConstantValue(value)) {
                // constant
                ConstantValue constantValue = LIRValueUtil.asConstantValue(value);
                Constant constant = constantValue.getConstant();
                DummyConstDef dummyConstDef = dummyConstDefs.get(constant);
                if (dummyConstDef == null) {
                    dummyConstDef = new DummyConstDef(constant);
                    dummyConstDefs.put(constant, dummyConstDef);
                }
                dummyDef = dummyConstDef;
            } else {
                if (!ValueUtil.isRegister(value)) {
                    // other than register or constant
                    GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used value " + value + " is not defined.");
                }

                // register
                Register register = ValueUtil.asRegister(value);
                if (registerAttributes[register.number].isAllocatable()) {
                    // register is allocatable
                    GraalError.shouldNotReachHere(ERROR_MSG_PREFIX + "Used register " + register + " is not defined.");
                }

                DummyRegDef dummyRegDef = dummyRegDefs.get(register);
                if (dummyRegDef == null) {
                    dummyRegDef = new DummyRegDef(register.asValue());
                    dummyRegDefs.put(register, dummyRegDef);
                }
                dummyDef = dummyRegDef;
            }

            DefNode dummyDefNode = new DefNode(value, dummyDef, 0);
            dummyDefNode.addAllNextNodes(entry.getValue());
            duSequences.put(value, Stream.of(dummyDefNode).collect(Collectors.toSet()));
        }

        unfinishedDuSequences.clear();
    }

    private static void visitValues(LIRInstruction instruction, InstructionValueConsumer defConsumer,
                    InstructionValueConsumer useConsumer, InstructionValueConsumer aliveConsumer) {

        instruction.visitEachAlive(aliveConsumer);
        // TODO: instruction.forEachState(proc); alive

        // TODO: instruction.destroysCallerSavedRegisters()
        instruction.visitEachOutput(defConsumer);
        instruction.visitEachTemp(useConsumer);
        instruction.visitEachTemp(defConsumer);// TODO: throw error, if open usage (make assertion)
        instruction.visitEachInput(useConsumer);
    }

    private void insertMoveNode(Map<Value, Set<Node>> unfinishedDuSequences, Value result, Value input, LIRInstruction instruction) {
        Set<Node> resultNodes = unfinishedDuSequences.get(result);

        defOperandPosition = 1;
        useOperandPosition = 1;

        if (resultNodes == null) {
            // no usage for copied value
            return;
        }

        assert resultNodes.stream().allMatch(node -> !node.isDefNode());

        MoveNode moveNode = new MoveNode(result, input, instruction, 0, 0);
        Optional<Node> optionalMoveNode = nodes.stream().filter(node -> node.equals(moveNode)).findFirst();

        Set<Node> inputNodes = getOrCreateSet(unfinishedDuSequences, input);

        if (!optionalMoveNode.isPresent()) {
            moveNode.addAllNextNodes(resultNodes);
            inputNodes.add(moveNode);
            nodes.add(moveNode);
        } else {
            Node node = optionalMoveNode.get();
            node.addAllNextNodes(resultNodes);
            inputNodes.add(node);
        }

        unfinishedDuSequences.remove(result);
    }

    class DefInstructionValueConsumer implements InstructionValueConsumer {

        Map<Value, Set<DefNode>> duSequences;
        Map<Value, Set<Node>> unfinishedDuSequences;

        public DefInstructionValueConsumer(Map<Value, Set<DefNode>> duSequences, Map<Value, Set<Node>> unfinishedDuSequences) {
            this.duSequences = duSequences;
            this.unfinishedDuSequences = unfinishedDuSequences;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value)) {
                // value is part of a composite value
                defOperandPosition++;
                return;
            }

            Set<Node> unfinishedNodes = unfinishedDuSequences.get(value);
            if (unfinishedNodes == null) {
                // definition of a value, which is not used or usage is not present yet (loops)
                defOperandPosition++;
                return;
            }

            AllocatableValue allocatableValue = ValueUtil.asAllocatableValue(value);

            assert unfinishedNodes.stream().allMatch(node -> !node.isDefNode());

            DefNode defNode = new DefNode(allocatableValue, instruction, defOperandPosition);
            Optional<Node> optionalDefNode = nodes.stream().filter(node -> node.equals(defNode)).findFirst();

            if (!optionalDefNode.isPresent()) {
                Set<DefNode> defNodes = getOrCreateSet(duSequences, value);
                defNodes.add(defNode);
                defNode.addAllNextNodes(unfinishedNodes);
                nodes.add(defNode);
            } else {
                optionalDefNode.get().addAllNextNodes(unfinishedNodes);
            }

            unfinishedDuSequences.remove(value);

            defOperandPosition++;
        }

    }

    class UseInstructionValueConsumer implements InstructionValueConsumer {

        private Map<Value, Set<Node>> unfinishedDuSequences;

        public UseInstructionValueConsumer(Map<Value, Set<Node>> unfinishedDuSequences) {
            this.unfinishedDuSequences = unfinishedDuSequences;
        }

        @Override
        public void visitValue(LIRInstruction instruction, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
            if (ValueUtil.isIllegal(value) || flags.contains(OperandFlag.UNINITIALIZED) || LIRValueUtil.isConstantValue(value)) {
                // value is part of a composite value, uninitialized or a constant
                useOperandPosition++;
                return;
            }

            Set<Node> nodesList = getOrCreateSet(unfinishedDuSequences, value);

            UseNode useNode = new UseNode(value, instruction, useOperandPosition);
            Optional<Node> optionalUseNode = nodes.stream().filter(node -> node.equals(useNode)).findFirst();

            if (!optionalUseNode.isPresent()) {
                nodes.add(useNode);
                nodesList.add(useNode);
            } else {
                nodesList.add(optionalUseNode.get());
            }

            useOperandPosition++;
        }

    }

    class SARAVerifyPhiValueVisitor implements PhiValueVisitor {

        private Map<Value, Set<Node>> unfinishedDuSequences;
        private LIRInstruction instruction;

        public SARAVerifyPhiValueVisitor(Map<Value, Set<Node>> unfinishedDuSequences, LIRInstruction instruction) {
            this.unfinishedDuSequences = unfinishedDuSequences;
            this.instruction = instruction;
        }

        @Override
        public void visit(Value phiIn, Value phiOut) {
            if (LIRValueUtil.isConstantValue(phiOut)) {
                ConstantValue phiOutConstantValue = (ConstantValue) phiOut;
                phiOutConstantValue = new ConstantValue(ValueKind.Illegal, phiOutConstantValue.getConstant());
                insertMoveNode(unfinishedDuSequences, phiIn, phiOutConstantValue, instruction);
            } else {
                insertMoveNode(unfinishedDuSequences, phiIn, phiOut, instruction);
            }
        }

    }
}
