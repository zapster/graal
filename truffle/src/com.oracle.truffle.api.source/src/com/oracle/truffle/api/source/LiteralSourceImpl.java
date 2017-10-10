/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.source;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.util.Objects;

final class LiteralSourceImpl extends Content implements Content.CreateURI {

    private final String name;

    LiteralSourceImpl(String name, CharSequence code) {
        this.name = name;
        this.code = enforceCharSequenceContract(code);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CharSequence getCharacters() {
        return code;
    }

    @Override
    public String getPath() {
        return null;
    }

    @Override
    public URL getURL() {
        return null;
    }

    @Override
    URI getURI() {
        return createURIOnce(this);
    }

    @Override
    public URI createURI() {
        return getNamedURI(name, code.toString().getBytes());
    }

    @Override
    public Reader getReader() {
        return new CharSequenceReader(code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, code);
    }

    @Override
    String findMimeType() throws IOException {
        return null;
    }

    @Override
    Object getHashKey() {
        return code;
    }

}
