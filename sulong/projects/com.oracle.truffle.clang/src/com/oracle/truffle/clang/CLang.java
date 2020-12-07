/*
 * Copyright (c) 2020, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.clang;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;

import org.graalvm.polyglot.io.ByteSequence;
import org.graalvm.polyglot.io.ProcessHandler;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.io.TruffleProcessBuilder;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.llvm.api.Toolchain;

@TruffleLanguage.Registration(id = "clang", name = "clang", interactive = false, characterMimeTypes = {CLang.MIME_TYPE_C, "text/x-cxx"}, defaultMimeType = "text/x-c", dependentLanguages = {
                "llvm"}, fileTypeDetectors = CLang.FileDetector.class)
public final class CLang extends TruffleLanguage<Env> {

    public static final String MIME_TYPE_C = "text/x-c";
    public static final String MIME_TYPE_CXX = "text/x-cxx";

    private enum Lang {
        C("CC", "c", MIME_TYPE_C, ".c"),
        CXX("CXX", "c++", MIME_TYPE_CXX, ".cpp");

        private final String language;
        private final String tool;
        private final String mimeType;
        private final String[] fileExts;

        Lang(String tool, String language, String mimeType, String... fileExts) {
            this.tool = tool;
            this.language = language;
            this.mimeType = mimeType;
            this.fileExts = fileExts;
        }

        static Lang getForMimeType(String mimeType) {
            for (Lang l : Lang.values()) {
                if (l.mimeType.equals(mimeType)) {
                    return l;
                }
            }
            // no matching mime type found - assume C
            return C;
        }

        static Lang getForFile(TruffleFile file) {
            String name = file.getName();
            if (name != null) {
                for (Lang l : Lang.values()) {
                    for (String ext : l.fileExts) {
                        if (name.endsWith(ext)) {
                            return l;
                        }
                    }
                }
            }
            // no matching extension found - assume C
            return C;
        }
    }

    public static final class FileDetector implements TruffleFile.FileTypeDetector {

        @Override
        public String findMimeType(TruffleFile file) {
            Lang lang = Lang.getForFile(file);
            if (lang != null) {
                return lang.mimeType;
            }
            return null;
        }

        @Override
        public Charset findEncoding(TruffleFile file) {
            return null;
        }
    }

    @Override
    protected Env createContext(Env env) {
        return env;
    }

    /**
     * Do not use this on fast-path.
     */
    public static Env getEnv() {
        CompilerAsserts.neverPartOfCompilation("Use faster context lookup methods for the fast-path.");
        return getCurrentContext(CLang.class);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        try {
            Source cSource = request.getSource();
            byte[] out;
            // try to compile only
            out = compile(cSource, "-c");
            try {
                // try to link
                Source objSource = Source.newBuilder(cSource).content(ByteSequence.create(out)).name(cSource.getName() + ".o").build();
                out = compile(objSource);
            } catch (CompilationFailureException e) {
                // ignore linker error
            }
            Source source = Source.newBuilder("llvm", ByteSequence.create(out), cSource.getName() + ".bc").build();
            return getEnv().parseInternal(source);
        } catch (IOException | InterruptedException | CompilationFailureException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] compile(Source src, String... args) throws IOException, InterruptedException, CompilationFailureException {
        ProcessHandler.Redirect inputRedirect = src.getPath() == null ? ProcessHandler.Redirect.PIPE : ProcessHandler.Redirect.INHERIT;
        String mimeType = src.getMimeType();
        Lang lang = Lang.getForMimeType(mimeType);
        if (lang == null) {
            throw new CompilationFailureException("Unsupported MimeType: " + mimeType);
        }
        String toolCmd = getToolchain().getToolPath(lang.tool).toString();

        String inFile = inputRedirect == ProcessHandler.Redirect.PIPE ? "-" : src.getPath();

        // build command line
        ArrayList<String> cmd = new ArrayList<>();
        Collections.addAll(cmd, toolCmd, "-x", lang.language, inFile, "-o", "-");
        Collections.addAll(cmd, args);

        ByteArrayOutputStream clangOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream clangError = new ByteArrayOutputStream();

        // start compilation process
        TruffleProcessBuilder builder = getEnv().newProcessBuilder();
        Process p = builder //
                        .command(cmd) //
                        .redirectInput(inputRedirect) //
                        .redirectOutput(builder.createRedirectToStream(clangOutput)) //
                        .redirectError(builder.createRedirectToStream(clangError)) //
                        .start();

        if (inputRedirect == ProcessHandler.Redirect.PIPE) {
            // pipe input source to clang process
            try (OutputStream clangInput = p.getOutputStream()) {
                if (src.hasCharacters()) {
                    try (OutputStreamWriter osw = new OutputStreamWriter(clangInput)) {
                        osw.write(src.getCharacters().toString());
                    }
                } else {
                    assert src.hasBytes();
                    src.getBytes().bytes().forEachOrdered(b -> writeUnchecked(clangInput, b));
                }
            }
        }
        p.waitFor();
        if (p.exitValue() != 0) {
            throw new CompilationFailureException(clangError);
        }
        return clangOutput.toByteArray();
    }

    private void writeUnchecked(OutputStream os, int b) {
        try {
            os.write(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Toolchain getToolchain() {
        LanguageInfo llvmInfo = getEnv().getInternalLanguages().get("llvm");
        return getEnv().lookup(llvmInfo, Toolchain.class);
    }

    private static class CompilationFailureException extends Exception {
        private static final long serialVersionUID = -4403892980772936757L;
        private final ByteArrayOutputStream err;

        public CompilationFailureException(ByteArrayOutputStream err) {
            this.err = err;
        }

        public CompilationFailureException(String msg) {
            super(msg);
            err = null;
        }

        @Override
        public String getMessage() {
            if (err != null) {
                return new String(err.toByteArray());
            }
            return super.getMessage();
        }
    }

}
