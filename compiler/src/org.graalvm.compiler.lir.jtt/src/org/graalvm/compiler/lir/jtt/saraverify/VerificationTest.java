package org.graalvm.compiler.lir.jtt.saraverify;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.List;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugDumpHandler;
import org.graalvm.compiler.debug.DebugHandler;
import org.graalvm.compiler.debug.DebugHandlersFactory;
import org.graalvm.compiler.debug.DebugOptions;
import org.graalvm.compiler.debug.DebugVerifyHandler;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary.ArithmeticOpcode;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromConst;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveToReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestReturn;
import org.graalvm.compiler.lir.saraverify.DuSequence;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis;
import org.graalvm.compiler.lir.saraverify.VerificationPhase;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.util.EconomicMap;
import org.junit.Test;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class VerificationTest {

    private Register r0 = new Register(0, 0, "r0", Register.SPECIAL);
    private Register r1 = new Register(1, 0, "r1", Register.SPECIAL);
    private Register r2 = new Register(2, 0, "r2", Register.SPECIAL);
    private Register rbp = new Register(11, 0, "rbp", Register.SPECIAL);
    private Register rax = new Register(12, 0, "rax", Register.SPECIAL);
    private Variable v0 = new Variable(ValueKind.Illegal, 0);
    private Variable v1 = new Variable(ValueKind.Illegal, 1);
    private Variable v2 = new Variable(ValueKind.Illegal, 2);
    private Variable v3 = new Variable(ValueKind.Illegal, 3);

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
        assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
    }

    @Test
    public void testSimpleAddSwitchedOperandOrder() {
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
        assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
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
        assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
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
        assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
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
        assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
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
        assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
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
        assertEquals(true, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
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
        assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
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
        assertEquals(false, verificationPhase.verifyDataFlow(inputDuSequences, outputDuSequences));
    }

}
