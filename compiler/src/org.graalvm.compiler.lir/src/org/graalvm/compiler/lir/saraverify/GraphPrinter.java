package org.graalvm.compiler.lir.saraverify;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRValueUtil;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.VirtualStackSlot;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.Value;

public class GraphPrinter {

    public static class Options {
        // @formatter:off
        @Option(help = "Enable printing of SARA Verify Graphs.", type = OptionType.Debug)
        public static final OptionKey<Boolean> SARAVerifyGraph = new OptionKey<>(false);
        // @formatter:on
    }

    public static void printGraphs(Map<Value, Set<DefNode>> inputDuSequences, List<DuSequenceWeb> inputDuSequenceWebs,
                    Map<Value, Set<DefNode>> outputDuSequences, List<DuSequenceWeb> outputDuSequenceWebs, DebugContext debugContext) {
        Path dir = debugContext.getDumpPath("_SARAVerifyGraphs", true);

        printGraphs(inputDuSequences, inputDuSequenceWebs, dir, "input");
        printGraphs(outputDuSequences, outputDuSequenceWebs, dir, "output");
    }

    public static void printGraphs(Map<Value, Set<DefNode>> inputDuSequences, List<DuSequenceWeb> inputDuSequenceWebs, DebugContext debugContext) {
        Path dir = debugContext.getDumpPath("_SARAVerifyGraphs", true);
        printGraphs(inputDuSequences, inputDuSequenceWebs, dir, "input");
    }

    private static void printGraphs(Map<Value, Set<DefNode>> duSequences, List<DuSequenceWeb> duSequenceWebs, Path dir, String folderName) {
        // print graphs
        Path subDir = dir.resolve(folderName);
        createDirectory(subDir);
        printDuSequences(duSequences, subDir);
        printDuSequenceWebs(duSequenceWebs, subDir);
    }

    private static void printDuSequences(Map<Value, Set<DefNode>> duSequences, Path dir) {

        for (Entry<Value, Set<DefNode>> entry : duSequences.entrySet()) {
            String valueString = entry.getKey().toString().replace("\"", "").replace("/", "_");
            Path file = dir.resolve("DS_" + valueString + ".gv");

            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write("digraph finite_state_machine {\n" +           //
                                "    graph [ " + "fontname = \"Helvetica-Oblique\",\n" +        //
                                " fontsize = 18,\n" +                          //
                                "label=\"\\n\\n\\nDu Sequence of value " + entry.getKey().toString().replace("\"", "\\\"") + "\" ];\n" +         //
                                "    node [shape = rectangle];\n");

                HashSet<Node> visited = new HashSet<>();

                for (Node node : entry.getValue()) {
                    printDuSequence(node, visited, writer);
                }

                assert visited.size() == visited.stream().mapToInt(node -> node.hashCode()).distinct().count()  //
                : "Hashcode collision of visited nodes.";

                printNodeLabels(visited, writer);

                writer.write("}\n");
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private static void printDuSequence(Node node, Set<Node> visited, BufferedWriter writer) throws IOException {
        if (visited.contains(node)) {
            return;
        }

        visited.add(node);

        int nodeHashCode = node.hashCode();

        for (Node nextNode : node.getNextNodes()) {
            writer.write("\"n" + nodeHashCode + "\" -> \"n" + nextNode.hashCode() + "\";\n");
            printDuSequence(nextNode, visited, writer);
        }
    }

    private static void printNodeLabels(Set<? extends Node> nodes, BufferedWriter writer) throws IOException {
        for (Node node : nodes) {
            writer.write("\"n" + node.hashCode() + "\" [ label = \"" + getNodeLabel(node) + "\" ];\n");
        }
    }

    private static String getNodeLabel(Node node) {
        String nodeLabel = "";

        if (node.isDefNode()) {
            DefNode defNode = (DefNode) node;
            nodeLabel = "Def:" + defNode.getDefOperandPosition() + ": " + getValueLabel(defNode.getValue());
        } else if (node.isUseNode()) {
            UseNode useNode = (UseNode) node;
            nodeLabel = "Use:" + useNode.getUseOperandPosition() + ": " + getValueLabel(useNode.getValue());
        } else {
            MoveNode moveNode = (MoveNode) node;
            nodeLabel = "Move:" + moveNode.getResultOperandPosition() + ":" + moveNode.getInputOperandPosition() + ": "  //
                            + getValueLabel(moveNode.getResult()) + " = " + getValueLabel(moveNode.getInput());
        }

        LIRInstruction instruction = node.getInstruction();
        return nodeLabel + "\\n  " + instruction.id() + ": " + instruction.name() + " (" + String.format("0x%h", System.identityHashCode(instruction)) + ")";
    }

    private static String getValueLabel(Value value) {
        if (ValueUtil.isRegister(value)) {
            Register register = ValueUtil.asRegister(value);
            return register.name;
        }

        if (LIRValueUtil.isVariable(value)) {
            Variable variable = LIRValueUtil.asVariable(value);

            if (variable.getName() != null) {
                return variable.getName();
            } else {
                return "v" + variable.index;
            }
        }

        if (ValueUtil.isStackSlot(value)) {
            StackSlot stackSlot = ValueUtil.asStackSlot(value);
            int rawOffset = stackSlot.getRawOffset();

            if (!stackSlot.getRawAddFrameSize()) {
                return "out:" + rawOffset;
            } else if (rawOffset >= 0) {
                return "in:" + rawOffset;
            } else {
                return "stack:" + (-rawOffset);
            }
        }

        if (LIRValueUtil.isVirtualStackSlot(value)) {
            VirtualStackSlot virtualStackSlot = LIRValueUtil.asVirtualStackSlot(value);
            return "vstack:" + virtualStackSlot.getId();
        }

        return value.toString().replace("\"", "\\\"");
    }

    private static void printDuSequenceWebs(List<DuSequenceWeb> duSequenceWebs, Path dir) {
        int i = 0;

        for (DuSequenceWeb duSequenceWeb : duSequenceWebs) {
            Path file = dir.resolve("DSW_" + i + ".gv");

            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write("digraph finite_state_machine {\n" +
                                "    graph [ fontname = \"Helvetica-Oblique\",\n" +        //
                                " fontsize = 18,\n" +                           //
                                "label=\"\\n\\n\\nDu Sequence Web " + i + "\" ];\n" +       //
                                "    node [shape = rectangle];\n");

                printDuSequenceWeb(duSequenceWeb, writer);

                writer.write("}\n");
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            i++;
        }
    }

    public static void printDuSequenceWeb(DuSequenceWeb duSequenceWeb, BufferedWriter writer) throws IOException {
        printNodeLabels(duSequenceWeb.getDefNodes(), writer);
        printNodeLabels(duSequenceWeb.getMoveNodes(), writer);
        printNodeLabels(duSequenceWeb.getUseNodes(), writer);

        for (DefNode node : duSequenceWeb.getDefNodes()) {
            int nodeHashCode = node.hashCode();
            for (Node nextNode : node.getNextNodes()) {
                writer.write("\"n" + nodeHashCode + "\" -> \"n" + nextNode.hashCode() + "\";\n");
            }
        }

        for (MoveNode node : duSequenceWeb.getMoveNodes()) {
            int nodeHashCode = node.hashCode();
            for (Node nextNode : node.getNextNodes()) {
                writer.write("\"n" + nodeHashCode + "\" -> \"n" + nextNode.hashCode() + "\";\n");
            }
        }

        for (UseNode node : duSequenceWeb.getUseNodes()) {
            int nodeHashCode = node.hashCode();
            for (Node nextNode : node.getNextNodes()) {
                writer.write("\"n" + nodeHashCode + "\" -> \"n" + nextNode.hashCode() + "\";\n");
            }
        }
    }

    public static void createDirectory(Path dir) {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            GraalError.shouldNotReachHere("Directory " + dir + " does already exist.");
        }
    }
}
