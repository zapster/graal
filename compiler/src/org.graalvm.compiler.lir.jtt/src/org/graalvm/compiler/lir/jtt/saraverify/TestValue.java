package org.graalvm.compiler.lir.jtt.saraverify;

import java.util.List;

import org.graalvm.compiler.lir.Variable;

import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.meta.ValueKind;

public class TestValue {

    public static final Register r0 = new Register(0, 0, "r0", Register.SPECIAL);
    public static final Register r1 = new Register(1, 0, "r1", Register.SPECIAL);
    public static final Register r2 = new Register(2, 0, "r2", Register.SPECIAL);
    public static final Register r3 = new Register(3, 0, "r3", Register.SPECIAL);
    public static final Register rbp = new Register(4, 0, "rbp", Register.SPECIAL);
    public static final Register rax = new Register(5, 0, "rax", Register.SPECIAL);
    public static final Variable v0 = new Variable(ValueKind.Illegal, 0);
    public static final Variable v1 = new Variable(ValueKind.Illegal, 1);
    public static final Variable v2 = new Variable(ValueKind.Illegal, 2);
    public static final Variable v3 = new Variable(ValueKind.Illegal, 3);

    public static final RegisterArray registers = new RegisterArray(r0, r1, r2, r3, rbp, rax);
    public static final RegisterArray allocatable = new RegisterArray(r0, r1, r2, rbp, rax);

    public static RegisterAttributes[] getAttributesMap() {
        RegisterAttributes[] map = new RegisterAttributes[registers.size()];
        List<Register> allocatableList = allocatable.asList();

        for (int i = 0; i < registers.size(); i++) {
            boolean isAllocatable = allocatableList.contains(registers.get(i));
            map[i] = new RegisterAttributes(false, false, isAllocatable);
        }

        return map;
    }
}
