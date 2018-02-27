package org.graalvm.compiler.lir.saraverify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.LIRPhase;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyConstDef;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis.DummyRegDef;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.Value;

public class VerificationPhase extends LIRPhase<AllocationContext> {

    private final String DEBUG_SCOPE = "SARAVerifyVerificationPhase";

    @Override
    protected void run(TargetDescription target, LIRGenerationResult lirGenRes, AllocationContext context) {
        AnalysisResult inputResult = context.contextLookup(AnalysisResult.class);

        if (inputResult == null) {
            // no input du-sequences were created by the RegisterAllocationVerificationPhase
            return;
        }

        Map<Value, List<DefNode>> inputDuSequences = inputResult.getDuSequences();
        Map<Register, DummyRegDef> inputDummyRegDefs = inputResult.getDummyRegDefs();
        Map<Constant, DummyConstDef> inputDummyConstDefs = inputResult.getDummyConstDefs();

        LIR lir = lirGenRes.getLIR();
        DebugContext debugContext = lir.getDebug();

        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        AnalysisResult outputResult = duSequenceAnalysis.determineDuSequences(lirGenRes, context.registerAllocationConfig.getRegisterConfig().getAttributesMap(), inputDummyRegDefs,
                        inputDummyConstDefs);

        Map<Value, List<DefNode>> outputDuSequences = outputResult.getDuSequences();

        if (!verifyDataFlow(inputDuSequences, outputDuSequences, debugContext)) {
            throw GraalError.shouldNotReachHere(DuSequenceAnalysis.ERROR_MSG_PREFIX + "Data Flow not equal");
        }

        if (!verifyOperandCount(inputResult.getInstructionDefOperandCount(),
                        inputResult.getInstructionUseOperandCount(),
                        outputResult.getInstructionDefOperandCount(), outputResult.getInstructionUseOperandCount())) {
            throw GraalError.shouldNotReachHere(DuSequenceAnalysis.ERROR_MSG_PREFIX + "Operand numbers not equal");
        }
    }

    public boolean verifyDataFlow(Map<Value, List<DefNode>> inputDuSequences, Map<Value, List<DefNode>> outputDuSequences, DebugContext debugContext) {
        List<DuSequenceWeb> inputDuSequenceWebs = createDuSequenceWebs(inputDuSequences);
        List<DuSequenceWeb> outputDuSequenceWebs = createDuSequenceWebs(outputDuSequences);

        for (DuSequenceWeb inputDuSequenceWeb : inputDuSequenceWebs) {
            if (!outputDuSequenceWebs.stream().anyMatch(outputDuSequenceWeb -> outputDuSequenceWeb.equals(inputDuSequenceWeb))) {
                return false;
            }
        }

        return true;
    }

    public static boolean verifyOperandCount(Map<LIRInstruction, Integer> inputInstructionDefOperandCount,
                    Map<LIRInstruction, Integer> inputInstructionUseOperandCount,
                    Map<LIRInstruction, Integer> outputInstructionDefOperandCount,
                    Map<LIRInstruction, Integer> outputInstructionUseOperandCount) {

        for (Entry<LIRInstruction, Integer> entry : inputInstructionDefOperandCount.entrySet()) {
            Integer count = outputInstructionDefOperandCount.get(entry.getKey());

            if (count != null && count.compareTo(entry.getValue()) != 0) {
                return false;
            }
        }

        for (Entry<LIRInstruction, Integer> entry : inputInstructionUseOperandCount.entrySet()) {
            Integer count = outputInstructionUseOperandCount.get(entry.getKey());

            if (count != null && count.compareTo(entry.getValue()) != 0) {
                return false;
            }
        }

        return true;
    }

    public List<DuSequenceWeb> createDuSequenceWebs(Map<Value, List<DefNode>> nodes) {
        List<DuSequenceWeb> duSequenceWebs = new ArrayList<>();
        Map<Node, DuSequenceWeb> nodeDuSequenceWeb = new HashMap<>();

        for (List<DefNode> nodeList : nodes.values()) {
            for (DefNode node : nodeList) {
                DuSequenceWeb duSequenceWeb = createDuSequenceWebs(node, nodeDuSequenceWeb);
                duSequenceWeb.getDefNodes().add(node);
                duSequenceWebs.add(duSequenceWeb);
            }
        }

        return duSequenceWebs;
    }

    private static DuSequenceWeb createDuSequenceWebs(Node node, Map<Node, DuSequenceWeb> nodeDuSequenceWeb) {
        DuSequenceWeb duSequenceWeb = nodeDuSequenceWeb.get(node);

        if (duSequenceWeb != null) {
            // node already visited
            return duSequenceWeb;
        }

        if (node.isUseNode()) {
            // node is a use node
            UseNode useNode = (UseNode) node;
            duSequenceWeb = new DuSequenceWeb();
            duSequenceWeb.getUseNodes().add(useNode);
            nodeDuSequenceWeb.put(node, duSequenceWeb);
            return duSequenceWeb;
        } else {
            List<Node> nextNodes;

            if (node.isDefNode()) {
                DefNode defNode = (DefNode) node;
                nextNodes = defNode.getNextNodes();
            } else {
                MoveNode moveNode = (MoveNode) node;
                nextNodes = moveNode.getNextNodes();
            }

            List<DuSequenceWeb> nextNodesDuSequenceWebs = nextNodes.stream() //
                            .map(nextNode -> createDuSequenceWebs(nextNode, nodeDuSequenceWeb))    //
                            .collect(Collectors.toList());

            DuSequenceWeb mergedDuSequenceWeb = mergeDuSequenceWebs(nextNodesDuSequenceWebs);
            nodeDuSequenceWeb.put(node, mergedDuSequenceWeb);

            return mergedDuSequenceWeb;
        }
    }

    private static DuSequenceWeb mergeDuSequenceWebs(List<DuSequenceWeb> duSequenceWebs) {
        DuSequenceWeb duSequenceWeb = new DuSequenceWeb();

        for (DuSequenceWeb web : duSequenceWebs) {
            assert web != null;

            duSequenceWeb.defNodesAddAll(web.getDefNodes());
            duSequenceWeb.useNodesAddAll(web.getUseNodes());
        }

        return duSequenceWeb;
    }

}
