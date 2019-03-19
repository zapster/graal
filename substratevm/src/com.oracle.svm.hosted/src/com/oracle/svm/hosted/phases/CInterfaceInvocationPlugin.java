/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.phases;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.graalvm.compiler.core.common.calc.FloatConvert;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.StampPair;
import org.graalvm.compiler.nodes.CallTargetNode;
import org.graalvm.compiler.nodes.CallTargetNode.InvokeKind;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.IndirectCallTargetNode;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.AndNode;
import org.graalvm.compiler.nodes.calc.ConditionalNode;
import org.graalvm.compiler.nodes.calc.FloatConvertNode;
import org.graalvm.compiler.nodes.calc.IntegerEqualsNode;
import org.graalvm.compiler.nodes.calc.LeftShiftNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.calc.NarrowNode;
import org.graalvm.compiler.nodes.calc.OrNode;
import org.graalvm.compiler.nodes.calc.RightShiftNode;
import org.graalvm.compiler.nodes.calc.SignExtendNode;
import org.graalvm.compiler.nodes.calc.ZeroExtendNode;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.NodePlugin;
import org.graalvm.compiler.nodes.memory.HeapAccess.BarrierType;
import org.graalvm.compiler.nodes.memory.address.OffsetAddressNode;
import org.graalvm.compiler.word.WordTypes;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.amd64.FrameAccess;
import com.oracle.svm.core.annotate.InvokeJavaFunctionPointer;
import com.oracle.svm.core.c.struct.CInterfaceLocationIdentity;
import com.oracle.svm.core.graal.code.amd64.SubstrateCallingConventionType;
import com.oracle.svm.core.graal.nodes.CInterfaceReadNode;
import com.oracle.svm.core.graal.nodes.CInterfaceWriteNode;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.nodes.CFunctionEpilogueNode;
import com.oracle.svm.core.nodes.CFunctionPrologueNode;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.c.CInterfaceError;
import com.oracle.svm.hosted.c.NativeLibraries;
import com.oracle.svm.hosted.c.info.AccessorInfo;
import com.oracle.svm.hosted.c.info.AccessorInfo.AccessorKind;
import com.oracle.svm.hosted.c.info.ConstantInfo;
import com.oracle.svm.hosted.c.info.ElementInfo;
import com.oracle.svm.hosted.c.info.PointerToInfo;
import com.oracle.svm.hosted.c.info.SizableInfo;
import com.oracle.svm.hosted.c.info.StructBitfieldInfo;
import com.oracle.svm.hosted.c.info.StructFieldInfo;
import com.oracle.svm.hosted.c.info.StructInfo;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public class CInterfaceInvocationPlugin implements NodePlugin {
    private final WordTypes wordTypes;

    private final NativeLibraries nativeLibs;

    private final ResolvedJavaType functionPointerType;

    public CInterfaceInvocationPlugin(MetaAccessProvider metaAccess, WordTypes wordTypes, NativeLibraries nativeLibs) {
        this.wordTypes = wordTypes;
        this.nativeLibs = nativeLibs;
        this.functionPointerType = metaAccess.lookupJavaType(CFunctionPointer.class);
    }

    @Override
    public boolean handleInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
        ElementInfo methodInfo = nativeLibs.findElementInfo(method);
        if (methodInfo instanceof AccessorInfo) {
            ElementInfo parentInfo = methodInfo.getParent();
            if (parentInfo instanceof StructFieldInfo) {
                int offset = ((StructFieldInfo) parentInfo).getOffsetInfo().getProperty();
                if (((AccessorInfo) methodInfo).getAccessorKind() == AccessorKind.OFFSET) {
                    return replaceOffsetOf(b, method, args, (AccessorInfo) methodInfo, offset);
                } else {
                    return replaceAccessor(b, method, args, (AccessorInfo) methodInfo, offset);
                }
            } else if (parentInfo instanceof StructBitfieldInfo) {
                return replaceBitfieldAccessor(b, method, args, (StructBitfieldInfo) parentInfo, (AccessorInfo) methodInfo);
            } else if (parentInfo instanceof StructInfo || parentInfo instanceof PointerToInfo) {
                return replaceAccessor(b, method, args, (AccessorInfo) methodInfo, 0);
            } else {
                throw shouldNotReachHere();
            }
        } else if (methodInfo instanceof ConstantInfo) {
            return replaceConstant(b, method, (ConstantInfo) methodInfo);
        } else if (method.getAnnotation(InvokeCFunctionPointer.class) != null) {
            return replaceFunctionPointerInvoke(b, method, args, SubstrateCallingConventionType.NativeCall);
        } else if (method.getAnnotation(InvokeJavaFunctionPointer.class) != null) {
            return replaceFunctionPointerInvoke(b, method, args, SubstrateCallingConventionType.JavaCall);
        } else {
            return false;
        }
    }

    private boolean replaceOffsetOf(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args, AccessorInfo accessorInfo, int displacement) {
        /*
         * A method annotated with @OffsetOf can be static, but does not need to be. If it is
         * non-static, we just ignore the receiver.
         */
        assert args.length == accessorInfo.parameterCount(!method.isStatic());

        JavaKind kind = wordTypes.asKind(b.getInvokeReturnType());
        b.addPush(pushKind(method), ConstantNode.forIntegerKind(kind, displacement, b.getGraph()));
        return true;
    }

    private boolean replaceAccessor(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args, AccessorInfo accessorInfo, int displacement) {
        StructuredGraph graph = b.getGraph();
        SizableInfo sizableInfo = (SizableInfo) accessorInfo.getParent();
        int elementSize = sizableInfo.getSizeInfo().getProperty();
        boolean isUnsigned = sizableInfo.isUnsigned();

        assert args.length == accessorInfo.parameterCount(true);
        ValueNode base = args[accessorInfo.baseParameterNumber(true)];
        switch (accessorInfo.getAccessorKind()) {
            case ADDRESS: {
                ValueNode address = makeAddress(graph, args, accessorInfo, base, displacement, elementSize);
                b.addPush(pushKind(method), address);
                return true;
            }
            case GETTER: {
                JavaKind resultKind = wordTypes.asKind(b.getInvokeReturnType());
                JavaKind readKind = kindFromSize(elementSize, resultKind);
                if (readKind == JavaKind.Object) {
                    assert resultKind == JavaKind.Object;
                } else if (readKind.getBitCount() > resultKind.getBitCount() && !readKind.isNumericFloat() && resultKind != JavaKind.Boolean) {
                    readKind = resultKind;
                }
                ValueNode address = makeAddress(graph, args, accessorInfo, base, displacement, elementSize);
                LocationIdentity locationIdentity = makeLocationIdentity(b, method, args, accessorInfo);
                Stamp stamp;
                if (readKind == JavaKind.Object) {
                    stamp = b.getInvokeReturnStamp(null).getTrustedStamp();
                } else if (readKind == JavaKind.Float || readKind == JavaKind.Double) {
                    stamp = StampFactory.forKind(readKind);
                } else {
                    stamp = StampFactory.forInteger(readKind.getBitCount());
                }
                ValueNode read = readOp(b, address, locationIdentity, stamp, accessorInfo);
                ValueNode adapted = adaptPrimitiveType(graph, read, readKind, resultKind == JavaKind.Boolean ? resultKind : resultKind.getStackKind(), isUnsigned);
                b.push(pushKind(method), adapted);
                return true;
            }
            case SETTER: {
                ValueNode value = args[accessorInfo.valueParameterNumber(true)];
                JavaKind valueKind = value.getStackKind();
                JavaKind writeKind = kindFromSize(elementSize, valueKind);
                ValueNode address = makeAddress(graph, args, accessorInfo, base, displacement, elementSize);
                LocationIdentity locationIdentity = makeLocationIdentity(b, method, args, accessorInfo);
                ValueNode adaptedValue = adaptPrimitiveType(graph, value, valueKind, writeKind, isUnsigned);
                writeOp(b, address, locationIdentity, adaptedValue, accessorInfo);
                return true;
            }
            default:
                throw shouldNotReachHere();
        }
    }

    private boolean replaceBitfieldAccessor(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args, StructBitfieldInfo bitfieldInfo, AccessorInfo accessorInfo) {
        int byteOffset = bitfieldInfo.getByteOffsetInfo().getProperty();
        int startBit = bitfieldInfo.getStartBitInfo().getProperty();
        int endBit = bitfieldInfo.getEndBitInfo().getProperty();
        boolean isUnsigned = bitfieldInfo.isUnsigned();
        assert byteOffset >= 0 && byteOffset < ((SizableInfo) bitfieldInfo.getParent()).getSizeInfo().getProperty();
        assert startBit >= 0 && startBit < 8;
        assert endBit >= startBit && endBit < 64;

        /*
         * The startBit is always in the first byte. Therefore, the endBit tells us how many bytes
         * we actually have to read and write.
         */
        JavaKind memoryKind;
        if (endBit < 8) {
            memoryKind = JavaKind.Byte;
        } else if (endBit < 16) {
            memoryKind = JavaKind.Short;
        } else if (endBit < 32) {
            memoryKind = JavaKind.Int;
        } else {
            memoryKind = JavaKind.Long;
        }
        int numBytes = memoryKind.getByteCount();

        /*
         * Try to align the byteOffset to be a multiple of numBytes. That should always be possible,
         * but we don't trust the C compiler and memory layout enough to make it an assertion.
         */
        int alignmentCorrection = byteOffset % numBytes;
        if (alignmentCorrection > 0 && endBit + alignmentCorrection * 8 < numBytes * 8) {
            byteOffset -= alignmentCorrection;
            startBit += alignmentCorrection * 8;
            endBit += alignmentCorrection * 8;
        }
        assert byteOffset >= 0 && byteOffset < ((SizableInfo) bitfieldInfo.getParent()).getSizeInfo().getProperty();
        assert startBit >= 0 && startBit < numBytes * 8;
        assert endBit >= startBit && endBit < numBytes * 8;

        int numBits = endBit - startBit + 1;
        assert numBits > 0 && numBits <= numBytes * 8;

        /*
         * The bit-operations on the value are either performed on Int or Long. We do not perform 8
         * or 16 bit arithmetic operations.
         */
        JavaKind computeKind = memoryKind.getStackKind();
        Stamp computeStamp = StampFactory.forKind(computeKind);
        int computeBits = computeKind.getBitCount();
        assert startBit >= 0 && startBit < computeBits;
        assert endBit >= startBit && endBit < computeBits;
        assert computeBits >= numBits;

        assert args.length == accessorInfo.parameterCount(true);
        ValueNode base = args[accessorInfo.baseParameterNumber(true)];
        StructuredGraph graph = b.getGraph();
        /*
         * Read the memory location. This is also necessary for writes, since we need to keep the
         * bits around the written bitfield unchanged.
         */
        ValueNode address = makeAddress(graph, args, accessorInfo, base, byteOffset, -1);
        LocationIdentity locationIdentity = makeLocationIdentity(b, method, args, accessorInfo);
        Stamp stamp = StampFactory.forInteger(memoryKind.getBitCount());
        ValueNode cur = readOp(b, address, locationIdentity, stamp, accessorInfo);
        cur = adaptPrimitiveType(graph, cur, memoryKind, computeKind, true);

        switch (accessorInfo.getAccessorKind()) {
            case GETTER: {
                if (isUnsigned) {
                    /*
                     * Unsigned reads: shift the bitfield to the right and mask out the unnecessary
                     * high-order bits.
                     */
                    cur = graph.unique(new RightShiftNode(cur, ConstantNode.forInt(startBit, graph)));
                    cur = graph.unique(new AndNode(cur, ConstantNode.forIntegerStamp(computeStamp, (1L << numBits) - 1, graph)));
                } else {
                    /*
                     * Signed reads: shift the bitfield to the right end to get the sign bit in
                     * place, then do a signed left shift to have a proper sign extension.
                     */
                    cur = graph.unique(new LeftShiftNode(cur, ConstantNode.forInt(computeBits - endBit - 1, graph)));
                    cur = graph.unique(new RightShiftNode(cur, ConstantNode.forInt(computeBits - numBits, graph)));
                }

                JavaKind resultKind = wordTypes.asKind(b.getInvokeReturnType());
                b.push(pushKind(method), adaptPrimitiveType(graph, cur, computeKind, resultKind == JavaKind.Boolean ? resultKind : resultKind.getStackKind(), isUnsigned));
                return true;
            }
            case SETTER: {
                /* Zero out the bits of our bitfields, i.e., the bits we are going to change. */
                long mask = ~(((1L << numBits) - 1) << startBit);
                cur = graph.unique(new AndNode(cur, ConstantNode.forIntegerStamp(computeStamp, mask, graph)));

                /*
                 * Mask the unnecessary high-order bits of the value to be written, and shift it to
                 * its place.
                 */
                ValueNode value = args[accessorInfo.valueParameterNumber(true)];
                value = adaptPrimitiveType(graph, value, value.getStackKind(), computeKind, isUnsigned);
                value = graph.unique(new AndNode(value, ConstantNode.forIntegerStamp(computeStamp, (1L << numBits) - 1, graph)));
                value = graph.unique(new LeftShiftNode(value, ConstantNode.forInt(startBit, graph)));

                /* Combine the leftover bits of the original memory word with the new value. */
                cur = graph.unique(new OrNode(cur, value));

                /* Narrow value to the number of bits we need to write. */
                cur = adaptPrimitiveType(graph, cur, computeKind, memoryKind, true);
                /* Perform the write (bitcount is taken from the stamp of the written value). */
                writeOp(b, address, locationIdentity, cur, accessorInfo);
                return true;
            }
            default:
                throw shouldNotReachHere();
        }
    }

    private static ValueNode readOp(GraphBuilderContext b, ValueNode address, LocationIdentity locationIdentity, Stamp stamp, AccessorInfo accessorInfo) {
        assert address.getStackKind() == FrameAccess.getWordKind();
        CInterfaceReadNode read = b.add(new CInterfaceReadNode(b.add(OffsetAddressNode.create(address)), locationIdentity, stamp, BarrierType.NONE, accessName(accessorInfo)));
        /*
         * The read must not float outside its block otherwise it may float above an explicit zero
         * check on its base address.
         */
        read.setForceFixed(true);
        return read;
    }

    private static void writeOp(GraphBuilderContext b, ValueNode address, LocationIdentity locationIdentity, ValueNode value, AccessorInfo accessorInfo) {
        b.add(new CInterfaceWriteNode(b.add(OffsetAddressNode.create(address)), locationIdentity, value, BarrierType.NONE, accessName(accessorInfo)));
    }

    private static String accessName(AccessorInfo accessorInfo) {
        if (accessorInfo.getParent() instanceof StructFieldInfo) {
            return accessorInfo.getParent().getParent().getName() + "." + accessorInfo.getParent().getName();
        } else {
            return accessorInfo.getParent().getName() + "*";
        }
    }

    private static ValueNode makeAddress(StructuredGraph graph, ValueNode[] args, AccessorInfo accessorInfo, ValueNode base, int displacement, int indexScaling) {
        ValueNode offset = ConstantNode.forIntegerKind(FrameAccess.getWordKind(), displacement, graph);

        if (accessorInfo.isIndexed()) {
            ValueNode index = args[accessorInfo.indexParameterNumber(true)];
            assert index.getStackKind().isPrimitive();
            ValueNode wordIndex = adaptPrimitiveType(graph, index, index.getStackKind(), FrameAccess.getWordKind(), false);
            ValueNode scaledIndex = graph.unique(new MulNode(wordIndex, ConstantNode.forIntegerKind(FrameAccess.getWordKind(), indexScaling, graph)));

            offset = graph.unique(new AddNode(scaledIndex, offset));
        }

        assert base.getStackKind() == FrameAccess.getWordKind();
        return graph.unique(new AddNode(base, offset));
    }

    private static LocationIdentity makeLocationIdentity(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args, AccessorInfo accessorInfo) {
        LocationIdentity locationIdentity;
        if (accessorInfo.hasLocationIdentityParameter()) {
            ValueNode locationIdentityNode = args[accessorInfo.locationIdentityParameterNumber(true)];
            if (!locationIdentityNode.isConstant()) {
                throw UserError.abort(new CInterfaceError(
                                "locationIdentity is not a compile time constant for call to " + method.format("%H.%n(%p)") + " in " + b.getMethod().asStackTraceElement(b.bci()),
                                method).getMessage());
            }
            locationIdentity = (LocationIdentity) SubstrateObjectConstant.asObject(locationIdentityNode.asConstant());
        } else if (accessorInfo.hasUniqueLocationIdentity()) {
            StructFieldInfo fieldInfo = (StructFieldInfo) accessorInfo.getParent();
            assert fieldInfo.getLocationIdentity() != null;
            locationIdentity = fieldInfo.getLocationIdentity();
        } else {
            locationIdentity = CInterfaceLocationIdentity.DEFAULT_LOCATION_IDENTITY;
        }
        return locationIdentity;
    }

    public static ValueNode adaptPrimitiveType(StructuredGraph graph, ValueNode value, JavaKind fromKind, JavaKind toKind, boolean isUnsigned) {
        if (fromKind == toKind) {
            return value;
        }
        assert fromKind.isNumericFloat() == toKind.isNumericFloat();

        int fromBits = fromKind.getBitCount();
        int toBits = toKind.getBitCount();

        if (fromBits == toBits) {
            return value;
        } else if (fromKind.isNumericFloat()) {
            FloatConvert op;
            if (fromKind == JavaKind.Float && toKind == JavaKind.Double) {
                op = FloatConvert.F2D;
            } else if (fromKind == JavaKind.Double && toKind == JavaKind.Float) {
                op = FloatConvert.D2F;
            } else {
                throw shouldNotReachHere();
            }
            return graph.unique(new FloatConvertNode(op, value));
        } else if (toKind == JavaKind.Boolean) {
            JavaKind computeKind = fromKind == JavaKind.Long ? JavaKind.Long : JavaKind.Int;
            LogicNode comparison = graph.unique(new IntegerEqualsNode(adaptPrimitiveType(graph, value, fromKind, computeKind, true), ConstantNode.forIntegerKind(computeKind, 0, graph)));
            return graph.unique(new ConditionalNode(comparison, ConstantNode.forBoolean(false, graph), ConstantNode.forBoolean(true, graph)));
        } else if (fromBits > toBits) {
            return graph.unique(new NarrowNode(value, toBits));
        } else if (isUnsigned) {
            return graph.unique(new ZeroExtendNode(value, toBits));
        } else {
            return graph.unique(new SignExtendNode(value, toBits));
        }
    }

    private static JavaKind kindFromSize(int sizeInBytes, JavaKind matchingKind) {
        if (matchingKind == JavaKind.Object || sizeInBytes * 8 == matchingKind.getBitCount()) {
            /* Out preferred matching kind fits, so we can use it. */
            return matchingKind;
        }

        if (matchingKind == JavaKind.Float || matchingKind == JavaKind.Double) {
            switch (sizeInBytes) {
                case 4:
                    return JavaKind.Float;
                case 8:
                    return JavaKind.Double;
            }
        } else {
            switch (sizeInBytes) {
                case 1:
                    return JavaKind.Byte;
                case 2:
                    return JavaKind.Short;
                case 4:
                    return JavaKind.Int;
                case 8:
                    return JavaKind.Long;
            }
        }
        throw shouldNotReachHere("Unsupported size: " + sizeInBytes);
    }

    private boolean replaceConstant(GraphBuilderContext b, ResolvedJavaMethod method, ConstantInfo constantInfo) {
        Object value = constantInfo.getValueInfo().getProperty();
        JavaKind kind = wordTypes.asKind(b.getInvokeReturnType());

        ConstantNode valueNode;
        switch (constantInfo.getKind()) {
            case INTEGER:
            case POINTER:
                if (method.getSignature().getReturnKind() == JavaKind.Boolean) {
                    valueNode = ConstantNode.forBoolean((long) value != 0, b.getGraph());
                } else {
                    valueNode = ConstantNode.forIntegerKind(kind, (long) value, b.getGraph());
                }
                break;
            case FLOAT:
                valueNode = ConstantNode.forFloatingKind(kind, (double) value, b.getGraph());
                break;
            case STRING:
            case BYTEARRAY:
                valueNode = ConstantNode.forConstant(SubstrateObjectConstant.forObject(value), b.getMetaAccess(), b.getGraph());
                break;
            default:
                throw shouldNotReachHere("Unexpected constant kind " + constantInfo);
        }
        b.push(pushKind(method), valueNode);
        return true;
    }

    private boolean replaceFunctionPointerInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args, CallingConvention.Type callType) {
        if (!functionPointerType.isAssignableFrom(method.getDeclaringClass())) {
            throw UserError.abort(new CInterfaceError("function pointer invocation method " + method.format("%H.%n(%p)") +
                            " must be in a type that extends " + CFunctionPointer.class.getSimpleName(), method).getMessage());
        }
        assert b.getInvokeKind() == InvokeKind.Interface;

        JavaType[] parameterTypes = method.getSignature().toParameterTypes(null);
        if (callType == SubstrateCallingConventionType.NativeCall) {
            Predicate<JavaType> isValid = t -> t.getJavaKind().isPrimitive() || wordTypes.isWord(t);
            UserError.guarantee(Stream.of(parameterTypes).allMatch(isValid) && isValid.test(method.getSignature().getReturnType(null)),
                            "C function pointer invocation method must have only primitive types or word types for its parameters and return value: " + method.format("%H.%n(%p)"));
            /*
             * We currently do not support automatic conversions for @CEnum because it entails
             * introducing additional invokes without real BCIs in a BytecodeParser context, which
             * does not work too well.
             */

            b.append(new CFunctionPrologueNode());
        }

        // We "discard" the receiver from the signature by pretending we are a static method
        assert args.length >= 1;
        ValueNode methodAddress = args[0];
        ValueNode[] argsWithoutReceiver = Arrays.copyOfRange(args, 1, args.length);
        assert argsWithoutReceiver.length == parameterTypes.length;

        Stamp returnStamp;
        if (wordTypes.isWord(b.getInvokeReturnType())) {
            returnStamp = wordTypes.getWordStamp((ResolvedJavaType) b.getInvokeReturnType());
        } else {
            returnStamp = b.getInvokeReturnStamp(null).getTrustedStamp();
        }

        CallTargetNode indirectCallTargetNode = b.add(new IndirectCallTargetNode(methodAddress, argsWithoutReceiver,
                        StampPair.createSingle(returnStamp), parameterTypes, method, callType, InvokeKind.Static));

        if (callType == SubstrateCallingConventionType.JavaCall) {
            b.handleReplacedInvoke(indirectCallTargetNode, b.getInvokeReturnType().getJavaKind());
        } else if (callType == SubstrateCallingConventionType.NativeCall) {
            // Native code cannot throw exceptions, omit exception edge
            InvokeNode invokeNode = new InvokeNode(indirectCallTargetNode, b.bci());
            if (pushKind(method) != JavaKind.Void) {
                b.addPush(pushKind(method), invokeNode);
            } else {
                b.add(invokeNode);
            }
            b.append(new CFunctionEpilogueNode());
        } else {
            throw shouldNotReachHere("Unsupported type of call: " + callType);
        }
        return true;
    }

    public static JavaKind pushKind(ResolvedJavaMethod method) {
        return method.getSignature().getReturnKind().getStackKind();
    }
}
