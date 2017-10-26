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
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestDefOp;
import org.graalvm.compiler.lir.jtt.saraverify.TestOp.TestReturn;
import org.junit.Test;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.meta.Value;

public class SaraVerifyTest extends GraalCompilerTest {

    @Test
    public void test() {
        ArrayList<LIRInstruction> instructions = new ArrayList<>();

        Register rsi = new Register(10, 0, "rsi", null);
        Register rdx = new Register(11, 0, "rdx", null);
        Register rbp = new Register(12, 0, "rbp", null);
        Register rax = new Register(13, 0, "rax", null);
        Register r0 = new Register(0, 0, "r0", null);
        Register r1 = new Register(1, 0, "r1", null);
        Register r2 = new Register(2, 0, "r2", null);

        TestDefOp testDefOp = new TestDefOp(new Variable(LIRKind.Illegal, 0));
        TestBinary addOp = new TestBinary(TestBinary.ArithmeticOpcode.ADD, r0.asValue(), r1.asValue(), r2.asValue());
        LabelOp labelOp = new LabelOp(null, true);
        labelOp.addIncomingValues(new Value[]{rsi.asValue(), rdx.asValue(), rbp.asValue()});
        TestReturn returnOp = new TestReturn(rbp.asValue(), rax.asValue());

        instructions.add(labelOp);
        instructions.add(testDefOp);
        instructions.add(addOp);
        instructions.add(new JumpOp(null));
        instructions.add(returnOp);

        System.out.println("\n== Pre-Analysis ==");
        printInstructions(instructions);

        // here pre-analyse (instructions)
        testDefOp.setValue(r0.asValue());

        // here verification phase (instructions)
        System.out.println("\n== After Analysis; Before Verification ==");
        printInstructions(instructions);
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
