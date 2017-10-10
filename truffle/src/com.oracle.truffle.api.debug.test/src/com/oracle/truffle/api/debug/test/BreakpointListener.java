/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.debug.test;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Debugger;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import org.junit.Assert;

final class BreakpointListener implements PropertyChangeListener {

    static BreakpointListener register(boolean[] notified, Debugger debugger, Breakpoint globalBreakpoint) {
        BreakpointListener newBPListener = new BreakpointListener(notified, debugger, globalBreakpoint);
        debugger.addPropertyChangeListener(newBPListener);
        return newBPListener;
    }

    private final boolean[] notified;
    private final Debugger debugger;
    private final Breakpoint globalBreakpoint;

    private BreakpointListener(boolean[] notified, Debugger debugger, Breakpoint globalBreakpoint) {
        this.notified = notified;
        this.debugger = debugger;
        this.globalBreakpoint = globalBreakpoint;
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        notified[0] = true;
        Assert.assertEquals(Debugger.PROPERTY_BREAKPOINTS, event.getPropertyName());
        Assert.assertEquals(debugger, event.getSource());
        Assert.assertNull(event.getOldValue());
        Assert.assertNotEquals(globalBreakpoint, event.getNewValue());
        Breakpoint newBP = (Breakpoint) event.getNewValue();
        try {
            newBP.dispose();
            Assert.fail("Public dispose must not be possible for global breakpoints.");
        } catch (IllegalStateException ex) {
            // O.K.
        }
    }

    void unregister() {
        debugger.removePropertyChangeListener(this);
    }

}
