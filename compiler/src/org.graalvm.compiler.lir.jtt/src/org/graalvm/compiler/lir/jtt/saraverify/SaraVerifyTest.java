package org.graalvm.compiler.lir.jtt.saraverify;

import java.util.ArrayList;
import java.util.EnumSet;

import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.test.GraalCompilerTest;
import org.graalvm.compiler.lir.InstructionValueConsumer;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstruction.OperandFlag;
import org.graalvm.compiler.lir.LIRInstruction.OperandMode;
import org.graalvm.compiler.lir.StandardOp.JumpOp;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestBinary;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestMoveFromReg;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestReturn;
import org.graalvm.compiler.lir.saraverify.DuSequenceAnalysis;
import org.junit.Test;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.meta.Value;

public class SaraVerifyTest extends GraalCompilerTest {

    @Test
    public void test() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        Register rbp = new Register(11, 0, "rbp", Register.SPECIAL);
        Register r0 = new Register(0, 0, "r0", Register.SPECIAL);
        Register r1 = new Register(1, 0, "r1", Register.SPECIAL);
        Register r2 = new Register(2, 0, "r2", Register.SPECIAL);

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{r0.asValue(), r1.asValue(), rbp.asValue()});
        TestBinary addOp = new TestBinary(TestBinary.ArithmeticOpcode.ADD, r2.asValue(), r0.asValue(), r1.asValue());
        TestBinary addOp2 = new TestBinary(TestBinary.ArithmeticOpcode.ADD, r2.asValue(), r1.asValue(), r2.asValue());
        TestReturn returnOp = new TestReturn(rbp.asValue(), r2.asValue());

        instructions.add(labelOp);
        instructions.add(addOp);
        instructions.add(addOp2);
        instructions.add(new JumpOp(null));
        instructions.add(returnOp);

        System.out.println("\n== Pre-Analysis ==");
        printInstructions(instructions);

        // here pre-analyse (instructions)
        // testDefOp.setValue(r0.asValue());

        // here verification phase (instructions)
        System.out.println("\n== After Analysis; Before Verification ==");
        // printInstructions(instructions);
        DuSequenceAnalysis duSequenceAnalysis = new DuSequenceAnalysis();
        duSequenceAnalysis.determineDuSequenceWebs(instructions);
        System.out.println(duSequenceAnalysis.getDuPairs());
    }

    @Test
    public void testDuPair() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        Register rbp = new Register(5, 0, "rbp", null);
        Variable v0 = new Variable(LIRKind.Illegal, 0);

        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{rbp.asValue()});
        TestMoveFromReg move = new TestMoveFromReg(v0, rbp.asValue());
        TestReturn returnOp = new TestReturn(v0, null);

        instructions.add(labelOp);
        instructions.add(move);
        instructions.add(returnOp);

        // printInstructions(instructions);
        // DuSequenceAnalysis.determineDuPairs(instructions);
    }

    private static void printInstructions(ArrayList<LIRInstruction> instructions) {
        InstructionValueConsumer proc = new InstructionValueConsumer() {

            @Override
            public void visitValue(LIRInstruction inst, Value value, OperandMode mode, EnumSet<OperandFlag> flags) {
                if (value == null) {
                    return;
                }

                String string;

                if (value instanceof RegisterValue) {
                    RegisterValue regValue = (RegisterValue) value;
                    string = regValue.getRegister().name;
                } else if (value instanceof Variable) {
                    Variable var = (Variable) value;
                    string = "v" + var.index;
                } else {
                    string = value.toString();
                }
                System.out.println("\t\t" + string);
            }
        };

        for (LIRInstruction inst : instructions) {

            System.identityHashCode(inst);

            System.out.println(inst);
            System.out.println("\t<input>");
            inst.visitEachInput(proc);
            System.out.println("\t<output>");
            inst.visitEachOutput(proc);
            System.out.println("\t<alive>");
            inst.visitEachAlive(proc);
            System.out.println("\t<temp>");
            inst.visitEachTemp(proc);
            System.out.println("\t<state>");
            inst.visitEachState(proc);
        }
    }
}
