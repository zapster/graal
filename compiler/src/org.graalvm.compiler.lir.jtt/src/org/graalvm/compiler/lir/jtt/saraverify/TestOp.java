package org.graalvm.compiler.lir.jtt.saraverify;

import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.HINT;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.ILLEGAL;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.REG;
import static org.graalvm.compiler.lir.LIRInstruction.OperandFlag.STACK;

import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LIRInstructionClass;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.StandardOp.ValueMoveOp;
import org.graalvm.compiler.lir.asm.CompilationResultBuilder;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Value;

public class TestOp {
    public static class TestDef extends LIRInstruction {

        public static final LIRInstructionClass<TestDef> TYPE = LIRInstructionClass.create(TestDef.class);
        @Def protected AllocatableValue value;

        public TestDef(AllocatableValue value) {
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

    public static class TestMoveFromReg extends LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<TestMoveFromReg> TYPE = LIRInstructionClass.create(TestMoveFromReg.class);

        @Def({REG, STACK}) protected AllocatableValue result;
        @Use({REG, HINT}) protected AllocatableValue input;

        public TestMoveFromReg(AllocatableValue result, AllocatableValue input) {
            super(TYPE);
            this.result = result;
            this.input = input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }
    }

    public static class TestMoveToReg extends LIRInstruction implements ValueMoveOp {
        public static final LIRInstructionClass<TestMoveToReg> TYPE = LIRInstructionClass.create(TestMoveToReg.class);

        @Def({REG, HINT}) protected AllocatableValue result;
        @Use({REG, STACK}) protected AllocatableValue input;

        public TestMoveToReg(AllocatableValue result, AllocatableValue input) {
            super(TYPE);
            this.result = result;
            this.input = input;
        }

        @Override
        public AllocatableValue getResult() {
            return result;
        }

        @Override
        public AllocatableValue getInput() {
            return input;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }

    }

    public static class TestBinary extends LIRInstruction {
        public enum ArithmeticOpcode {
            ADD,
            SUB,
            MUL,
            DIV;
        }

        public static final LIRInstructionClass<TestBinary> TYPE = LIRInstructionClass.create(TestBinary.class);

        @Opcode protected final ArithmeticOpcode opcode;
        @Def protected AllocatableValue result;
        @Use protected AllocatableValue x;
        @Use protected AllocatableValue y;
        @Temp protected AllocatableValue raxTemp;

        public TestBinary(ArithmeticOpcode opcode, AllocatableValue result, AllocatableValue x, AllocatableValue y) {
            super(TYPE);
            this.opcode = opcode;
            this.result = result;
            this.x = x;
            this.y = y;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }

    public static class TestReturn extends LIRInstruction {

        public static final LIRInstructionClass<TestReturn> TYPE = LIRInstructionClass.create(TestReturn.class);

        @Use({REG, LIRInstruction.OperandFlag.STACK}) protected Value savedRbp;
        @Use({REG, ILLEGAL}) protected Value value;

        public TestReturn(AllocatableValue savedRbp, AllocatableValue value) {
            super(TYPE);
            this.savedRbp = savedRbp;
            this.value = value;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
        }
    }
}