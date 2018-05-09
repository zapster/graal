package org.graalvm.compiler.lir.jtt.saraverify;

import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.r0;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.r1;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.r2;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.rbp;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.v0;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.v1;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.v2;
import static org.graalvm.compiler.lir.jtt.saraverify.TestValue.v3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.jtt.JTTTest;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary.ArithmeticOpcode;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestDef;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromConst;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestReturn;
import org.graalvm.compiler.lir.saraverify.AnalysisResult;
import org.graalvm.compiler.lir.saraverify.DefNode;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis;
import org.graalvm.compiler.lir.saraverify.DuSequenceWeb;
import org.graalvm.compiler.lir.saraverify.MoveNode;
import org.graalvm.compiler.lir.saraverify.SimpleVerificationPhase;
import org.graalvm.compiler.lir.saraverify.UseNode;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Value;

public class VerificationTest extends JTTTest {

    @Rule public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testSimpleAddCorrect() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v1, v3});
        TestBinary addOp = new TestBinary(TestBinary.ArithmeticOpcode.ADD, v2, v0, v1);
        TestReturn returnOp = new TestReturn(v3, v2);

        instructions.add(labelOp);
        instructions.add(addOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        addOp.result = r2.asValue();
        addOp.x = r0.asValue();
        addOp.y = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r2.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(true, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleAddSwitchedOperandOrder1() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v1, v3});
        TestBinary addOp = new TestBinary(TestBinary.ArithmeticOpcode.ADD, v2, v0, v1);
        TestReturn returnOp = new TestReturn(v3, v2);

        instructions.add(labelOp);
        instructions.add(addOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r1.asValue(), r0.asValue(), rbp.asValue()});
        addOp.result = r2.asValue();
        addOp.x = r0.asValue();
        addOp.y = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r2.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(false, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleAddSwitchedOperandOrder2() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v1, v3});
        TestBinary addOp = new TestBinary(TestBinary.ArithmeticOpcode.ADD, v2, v0, v1);
        TestReturn returnOp = new TestReturn(v3, v2);

        instructions.add(labelOp);
        instructions.add(addOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        addOp.result = r2.asValue();
        addOp.x = r1.asValue();
        addOp.y = r0.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r2.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(false, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleCopyCorrect() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v2});
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestReturn returnOp = new TestReturn(v2, v1);

        instructions.add(labelOp);
        instructions.add(moveOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r1.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(true, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleCopyCorrect2() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v2});
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestReturn returnOp = new TestReturn(v2, v1);

        instructions.add(labelOp);
        instructions.add(moveOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r0.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(true, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleCopySpilledValueCorrectReturn() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v2});
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestReturn returnOp = new TestReturn(v2, v1);

        instructions.add(labelOp);
        instructions.add(moveOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        TestMoveFromConst moveConstToReg = new TestMoveFromConst(r1.asValue(), JavaConstant.INT_1);
        instructions.add(2, moveConstToReg);

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r0.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(true, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleCopySpilledValueWrongReturn() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v2});
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestReturn returnOp = new TestReturn(v2, v1);

        instructions.add(labelOp);
        instructions.add(moveOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        TestMoveFromConst moveConstToReg = new TestMoveFromConst(r1.asValue(), JavaConstant.INT_1);
        instructions.add(2, moveConstToReg);

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r1.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(false, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleCopyAddCorrect() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v2});
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestBinary addOp = new TestBinary(ArithmeticOpcode.ADD, v1, v1, v1);
        TestReturn returnOp = new TestReturn(v2, v1);

        instructions.add(labelOp);
        instructions.add(moveOp);
        instructions.add(addOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        addOp.result = r1.asValue();
        addOp.x = r1.asValue();
        addOp.y = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r1.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(true, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleCopyAddReturnStaledValue() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v2});
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestBinary addOp = new TestBinary(ArithmeticOpcode.ADD, v1, v1, v1);
        TestReturn returnOp = new TestReturn(v2, v1);

        instructions.add(labelOp);
        instructions.add(moveOp);
        instructions.add(addOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        addOp.result = r1.asValue();
        addOp.x = r1.asValue();
        addOp.y = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r0.asValue();

        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(false, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testSimpleCopyAddMissingAllocation() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{v0, v2});
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestBinary addOp = new TestBinary(ArithmeticOpcode.ADD, v1, v1, v1);
        TestReturn returnOp = new TestReturn(v2, v1);

        instructions.add(labelOp);
        instructions.add(moveOp);
        instructions.add(addOp);
        instructions.add(returnOp);

        AnalysisResult inputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> inputDuSequences = inputResult.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r1.asValue();

        thrown.expect(GraalError.class);
        AnalysisResult outputResult = getAnalysisResult(duSequenceAnalysis, instructions);
        Map<Value, Set<DefNode>> outputDuSequences = outputResult.getDuSequences();

        assertVerifyDataFlow(false, inputDuSequences, outputDuSequences);
    }

    @Test
    public void testCreateDuSequenceWebs() {
        Map<Value, Set<DefNode>> nodes = new HashMap<>();

        TestDef defOp1 = new TestDef(r0.asValue());
        TestDef defOp2 = new TestDef(v0);
        TestMoveFromReg moveOp = new TestMoveFromReg(v0, r0.asValue());
        TestReturn returnOp1 = new TestReturn(null, v0);
        TestReturn returnOp2 = new TestReturn(null, v0);

        DefNode defNode1 = new DefNode(r0.asValue(), defOp1, 0);
        DefNode defNode2 = new DefNode(v0, defOp2, 0);
        MoveNode moveNode = new MoveNode(v0, r0.asValue(), moveOp, 0, 0);
        UseNode useNode1 = new UseNode(v0, returnOp1, 0);
        UseNode useNode2 = new UseNode(v0, returnOp2, 0);

        defNode1.addNextNodes(moveNode);
        moveNode.addNextNodes(useNode1);
        defNode2.addNextNodes(useNode2);

        nodes.put(r0.asValue(), DuSequenceAnalysisTest.asSet(defNode1));
        nodes.put(v0, DuSequenceAnalysisTest.asSet(defNode2));

        DuSequenceWeb web1 = new DuSequenceWeb();
        web1.addNodes(Arrays.asList(defNode1, moveNode, useNode1));

        DuSequenceWeb web2 = new DuSequenceWeb();
        web2.addNodes(Arrays.asList(defNode2, useNode2));

        List<DuSequenceWeb> expectedWebs = new ArrayList<>();
        expectedWebs.add(web1);
        expectedWebs.add(web2);

        List<DuSequenceWeb> actualWebs = DuSequenceAnalysis.createDuSequenceWebs(nodes);
        Assert.assertEquals(2, actualWebs.size());

        assertTrue(actualWebs.contains(web1));
        assertTrue(actualWebs.contains(web2));
    }

    @Test
    public void testCreateDuSequenceWebs2() {
        Map<Value, Set<DefNode>> nodes = new HashMap<>();

        TestDef defOp1 = new TestDef(v0);
        TestDef defOp2 = new TestDef(v0);
        TestDef defOp3 = new TestDef(v0);
        TestReturn returnOp1 = new TestReturn(null, v0);
        TestReturn returnOp2 = new TestReturn(null, v0);

        DefNode defNode1 = new DefNode(v0, defOp1, 0);
        DefNode defNode2 = new DefNode(v0, defOp2, 0);
        DefNode defNode3 = new DefNode(v0, defOp3, 0);
        UseNode useNode1 = new UseNode(v0, returnOp1, 0);
        UseNode useNode2 = new UseNode(v0, returnOp2, 0);

        defNode1.addNextNodes(useNode1);
        defNode2.addNextNodes(useNode1);
        defNode2.addNextNodes(useNode2);
        defNode3.addNextNodes(useNode2);

        nodes.put(v0, DuSequenceAnalysisTest.asSet(defNode1, defNode2, defNode3));

        List<DuSequenceWeb> actualWebs = DuSequenceAnalysis.createDuSequenceWebs(nodes);
        DuSequenceWeb expectedWeb = new DuSequenceWeb();
        expectedWeb.addNodes(Arrays.asList(defNode1, defNode2, defNode3));
        expectedWeb.addNodes(Arrays.asList(useNode1, useNode2));

        Assert.assertEquals(1, actualWebs.size());
        assertTrue(expectedWeb.equals(actualWebs.get(0)));

        Map<Value, Set<DefNode>> nodes2 = new HashMap<>();
        nodes2.put(v0, DuSequenceAnalysisTest.asSet(defNode2, defNode1, defNode3));
        List<DuSequenceWeb> actualWebs2 = DuSequenceAnalysis.createDuSequenceWebs(nodes2);

        Assert.assertEquals(1, actualWebs.size());
        assertTrue(expectedWeb.equals(actualWebs2.get(0)));

        Map<Value, Set<DefNode>> nodes3 = new HashMap<>();
        nodes3.put(v0, DuSequenceAnalysisTest.asSet(defNode1, defNode3, defNode2));
        List<DuSequenceWeb> actualWebs3 = DuSequenceAnalysis.createDuSequenceWebs(nodes3);

        Assert.assertEquals(1, actualWebs3.size());
        assertTrue(expectedWeb.equals(actualWebs3.get(0)));
    }

    @Test
    public void testCreateDuSequenceWebs3() {
        Map<Value, Set<DefNode>> nodes = new HashMap<>();

        TestDef defOp = new TestDef(r0.asValue());
        TestMoveFromReg move1 = new TestMoveFromReg(v0, r0.asValue());
        TestMoveFromReg move2 = new TestMoveFromReg(v0, r0.asValue());
        TestReturn returnOp1 = new TestReturn(null, v0);

        DefNode defNode = new DefNode(r0.asValue(), defOp, 0);
        MoveNode moveNode1 = new MoveNode(v0, r0.asValue(), move1, 0, 0);
        MoveNode moveNode2 = new MoveNode(v0, r0.asValue(), move2, 0, 0);
        UseNode useNode = new UseNode(v0, returnOp1, 0);

        defNode.addNextNodes(moveNode1);
        defNode.addNextNodes(moveNode2);
        moveNode1.addNextNodes(useNode);
        moveNode2.addNextNodes(useNode);

        nodes.put(r0.asValue(), DuSequenceAnalysisTest.asSet(defNode));

        DuSequenceWeb web = new DuSequenceWeb();
        web.addNodes(Arrays.asList(defNode, moveNode1, moveNode2, useNode));

        List<DuSequenceWeb> actualWebs = DuSequenceAnalysis.createDuSequenceWebs(nodes);
        Assert.assertEquals(1, actualWebs.size());

        Assert.assertEquals(web, actualWebs.get(0));
    }

    @Test
    public void testVerifyOperandCount() {
        Map<LIRInstruction, Integer> emptyMap = new HashMap<>();
        assertTrue(SimpleVerificationPhase.verifyOperandCount(emptyMap, emptyMap, emptyMap, emptyMap));
        throw GraalError.unimplemented("Test using VerificationPhase");
    }

    @Test
    public void testVerifyOperandCount2() {
        Map<LIRInstruction, Integer> operandDefCountMap = new HashMap<>();
        Map<LIRInstruction, Integer> operandUseCountMap = new HashMap<>();

        LabelOp labelOp = new LabelOp(null, false);
        operandDefCountMap.put(labelOp, 3);
        operandUseCountMap.put(labelOp, 0);

        TestReturn returnOp = new TestReturn(v2, v1);
        operandDefCountMap.put(returnOp, 0);
        operandUseCountMap.put(returnOp, 2);

        assertTrue(SimpleVerificationPhase.verifyOperandCount(operandDefCountMap, operandUseCountMap, operandDefCountMap, operandUseCountMap));
        throw GraalError.unimplemented("Test using VerificationPhase");
    }

    @Test
    public void testVerifyOperandCount3() {
        Map<LIRInstruction, Integer> inputOperandDefCount = new HashMap<>();
        Map<LIRInstruction, Integer> inputOperandUseCount = new HashMap<>();
        Map<LIRInstruction, Integer> outputOperandDefCount = new HashMap<>();
        Map<LIRInstruction, Integer> outputOperandUseCount = new HashMap<>();

        LabelOp labelOp = new LabelOp(null, false);
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestReturn returnOp = new TestReturn(v2, v1);

        inputOperandDefCount.put(labelOp, 3);
        inputOperandDefCount.put(moveOp, 1);
        inputOperandDefCount.put(returnOp, 0);

        inputOperandUseCount.put(labelOp, 0);
        inputOperandUseCount.put(moveOp, 1);
        inputOperandUseCount.put(returnOp, 2);

        outputOperandDefCount.put(labelOp, 3);
        outputOperandDefCount.put(returnOp, 0);

        outputOperandUseCount.put(labelOp, 0);
        outputOperandUseCount.put(returnOp, 2);

        assertTrue(SimpleVerificationPhase.verifyOperandCount(inputOperandDefCount, inputOperandUseCount, outputOperandDefCount, outputOperandUseCount));
        throw GraalError.unimplemented("Test using VerificationPhase");
    }

    @Test
    public void testVerifyOperandCount4() {
        Map<LIRInstruction, Integer> inputOperandDefCount = new HashMap<>();
        Map<LIRInstruction, Integer> inputOperandUseCount = new HashMap<>();
        Map<LIRInstruction, Integer> outputOperandDefCount = new HashMap<>();
        Map<LIRInstruction, Integer> outputOperandUseCount = new HashMap<>();

        LabelOp labelOp = new LabelOp(null, false);
        TestMoveFromReg moveOp = new TestMoveFromReg(v1, v0);
        TestReturn returnOp = new TestReturn(v2, v1);

        inputOperandDefCount.put(labelOp, 3);
        inputOperandDefCount.put(returnOp, 0);

        inputOperandUseCount.put(labelOp, 0);
        inputOperandUseCount.put(returnOp, 2);

        outputOperandDefCount.put(labelOp, 3);
        outputOperandDefCount.put(moveOp, 1);
        outputOperandDefCount.put(returnOp, 0);

        outputOperandUseCount.put(labelOp, 0);
        outputOperandUseCount.put(moveOp, 1);
        outputOperandUseCount.put(returnOp, 2);

        assertTrue(SimpleVerificationPhase.verifyOperandCount(inputOperandDefCount, inputOperandUseCount, outputOperandDefCount, outputOperandUseCount));
        throw GraalError.unimplemented("Test using VerificationPhase");
    }

    @Test
    public void testVerifyOperandCount5() {
        Map<LIRInstruction, Integer> inputOperandDefCount = new HashMap<>();
        Map<LIRInstruction, Integer> inputOperandUseCount = new HashMap<>();
        Map<LIRInstruction, Integer> outputOperandDefCount = new HashMap<>();
        Map<LIRInstruction, Integer> outputOperandUseCount = new HashMap<>();

        LabelOp labelOp = new LabelOp(null, false);
        TestReturn returnOp = new TestReturn(v2, v1);

        inputOperandDefCount.put(labelOp, 3);
        inputOperandDefCount.put(returnOp, 0);

        inputOperandUseCount.put(labelOp, 0);
        inputOperandUseCount.put(returnOp, 2);

        outputOperandDefCount.put(labelOp, 3);
        outputOperandDefCount.put(returnOp, 1);

        outputOperandUseCount.put(labelOp, 0);
        outputOperandUseCount.put(returnOp, 2);

        assertFalse(SimpleVerificationPhase.verifyOperandCount(inputOperandDefCount, inputOperandUseCount, outputOperandDefCount, outputOperandUseCount));
        throw GraalError.unimplemented("Test using VerificationPhase");
    }

    @Test
    public void testVerifyOperandCount6() {
        Map<LIRInstruction, Integer> inputOperandDefCount = new HashMap<>();
        Map<LIRInstruction, Integer> inputOperandUseCount = new HashMap<>();
        Map<LIRInstruction, Integer> outputOperandDefCount = new HashMap<>();
        Map<LIRInstruction, Integer> outputOperandUseCount = new HashMap<>();

        LabelOp labelOp = new LabelOp(null, false);
        TestReturn returnOp = new TestReturn(v2, v1);

        inputOperandDefCount.put(labelOp, 3);
        inputOperandDefCount.put(returnOp, 0);

        inputOperandUseCount.put(labelOp, 0);
        inputOperandUseCount.put(returnOp, 1);

        outputOperandDefCount.put(labelOp, 3);
        outputOperandDefCount.put(returnOp, 0);

        outputOperandUseCount.put(labelOp, 0);
        outputOperandUseCount.put(returnOp, 2);

        assertFalse(SimpleVerificationPhase.verifyOperandCount(inputOperandDefCount, inputOperandUseCount, outputOperandDefCount, outputOperandUseCount));
        throw GraalError.unimplemented("Test using VerificationPhase");
    }

    private static AnalysisResult getAnalysisResult(DuSequenceAnalysis duSequenceAnalysis, List<LIRInstruction> instructions) {
        return duSequenceAnalysis.determineDuSequences(instructions, TestValue.getAttributesMap(), new HashMap<>(), new HashMap<>());
    }

    private void assertVerifyDataFlow(boolean expected, Map<Value, Set<DefNode>> inputDuSequences, Map<Value, Set<DefNode>> outputDuSequences) {
        SimpleVerificationPhase verificationPhase = new SimpleVerificationPhase();
        Assert.assertEquals(expected, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
        throw GraalError.unimplemented("Test using VerificationPhase");
    }
}
