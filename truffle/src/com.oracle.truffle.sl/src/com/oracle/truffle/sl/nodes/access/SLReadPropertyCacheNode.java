/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.sl.nodes.access;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Location;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.sl.runtime.SLUndefinedNameException;

public abstract class SLReadPropertyCacheNode extends SLPropertyCacheNode {

    public abstract Object executeRead(DynamicObject receiver, Object name);

    /**
     * Polymorphic inline cache for a limited number of distinct property names and shapes.
     */
    @Specialization(limit = "CACHE_LIMIT", //
                    guards = {
                                    "namesEqual(cachedName, name)",
                                    "shapeCheck(shape, receiver)"
                    }, //
                    assumptions = {
                                    "shape.getValidAssumption()"
                    })
    protected static Object readCached(DynamicObject receiver, @SuppressWarnings("unused") Object name,
                    @SuppressWarnings("unused") @Cached("name") Object cachedName,
                    @Cached("lookupShape(receiver)") Shape shape,
                    @Cached("lookupLocation(shape, name)") Location location) {

        return location.get(receiver, shape);
    }

    protected Location lookupLocation(Shape shape, Object name) {
        /* Initialization of cached values always happens in a slow path. */
        CompilerAsserts.neverPartOfCompilation();

        Property property = shape.getProperty(name);
        if (property == null) {
            /* Property does not exist. */
            throw SLUndefinedNameException.undefinedProperty(this, name);
        }

        return property.getLocation();
    }

    /**
     * The generic case is used if the number of shapes accessed overflows the limit of the
     * polymorphic inline cache.
     */
    @TruffleBoundary
    @Specialization(replaces = {"readCached"}, guards = "receiver.getShape().isValid()")
    protected Object readUncached(DynamicObject receiver, Object name) {
        Object result = receiver.get(name);
        if (result == null) {
            /* Property does not exist. */
            throw SLUndefinedNameException.undefinedProperty(this, name);
        }
        return result;
    }

    @Specialization(guards = "!receiver.getShape().isValid()")
    protected Object updateShape(DynamicObject receiver, Object name) {
        CompilerDirectives.transferToInterpreter();
        receiver.updateShape();
        return readUncached(receiver, name);
    }

    public static SLReadPropertyCacheNode create() {
        return SLReadPropertyCacheNodeGen.create();
    }

}
