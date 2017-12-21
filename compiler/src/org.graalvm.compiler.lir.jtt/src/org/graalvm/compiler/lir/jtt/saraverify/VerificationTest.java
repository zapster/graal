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
import java.util.HashMap;
import java.util.Map;

import org.graalvm.compiler.jtt.JTTTest;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary.ArithmeticOpcode;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromConst;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestReturn;
import org.graalvm.compiler.lir.saraverify.DuSequence;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis;
import org.graalvm.compiler.lir.saraverify.VerificationPhase;
import org.junit.Assert;
import org.junit.Test;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Value;

public class VerificationTest extends JTTTest {

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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        addOp.result = r2.asValue();
        addOp.x = r0.asValue();
        addOp.y = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r2.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r1.asValue(), r0.asValue(), rbp.asValue()});
        addOp.result = r2.asValue();
        addOp.x = r0.asValue();
        addOp.y = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r2.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        addOp.result = r2.asValue();
        addOp.x = r1.asValue();
        addOp.y = r0.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r2.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r1.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r0.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        TestMoveFromConst moveConstToReg = new TestMoveFromConst(r1.asValue(), JavaConstant.INT_1);
        instructions.add(2, moveConstToReg);

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r0.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        TestMoveFromConst moveConstToReg = new TestMoveFromConst(r1.asValue(), JavaConstant.INT_1);
        instructions.add(2, moveConstToReg);

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r1.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        addOp.result = r1.asValue();
        addOp.x = r1.asValue();
        addOp.y = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r1.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        addOp.result = r1.asValue();
        addOp.x = r1.asValue();
        addOp.y = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r0.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
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

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> inputDuSequences = duSequenceAnalysis.getDuSequences();

        labelOp.clearIncomingValues();
        labelOp.addIncomingValues(new Value[]{r0.asValue(), rbp.asValue()});
        moveOp.input = r0.asValue();
        moveOp.result = r1.asValue();
        returnOp.savedRbp = rbp.asValue();
        returnOp.value = r1.asValue();

        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        ArrayList<DuSequence> outputDuSequences = duSequenceAnalysis.getDuSequences();

        VerificationPhase verificationPhase = new VerificationPhase();
        Assert.assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences, this.getDebugContext()));
    }

    @Test
    public void testVerifyDataFlow() {
        VerificationPhase verificationPhase = new VerificationPhase();
        ArrayList<DuSequence> duSequences = new ArrayList<>();

        assertTrue(verificationPhase.verifyDataFlow(duSequences, duSequences, this.getDebugContext()));
    }

    @Test
    public void testVerifyOperandCount() {
        Map<LIRInstruction, Integer> emptyMap = new HashMap<>();
        assertTrue(VerificationPhase.verifyOperandCount(emptyMap, emptyMap, emptyMap, emptyMap));
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

        assertTrue(VerificationPhase.verifyOperandCount(operandDefCountMap, operandUseCountMap, operandDefCountMap, operandUseCountMap));
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

        assertTrue(VerificationPhase.verifyOperandCount(inputOperandDefCount, inputOperandUseCount, outputOperandDefCount, outputOperandUseCount));
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

        assertTrue(VerificationPhase.verifyOperandCount(inputOperandDefCount, inputOperandUseCount, outputOperandDefCount, outputOperandUseCount));
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

        assertFalse(VerificationPhase.verifyOperandCount(inputOperandDefCount, inputOperandUseCount, outputOperandDefCount, outputOperandUseCount));
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

        assertFalse(VerificationPhase.verifyOperandCount(inputOperandDefCount, inputOperandUseCount, outputOperandDefCount, outputOperandUseCount));
    }
}
