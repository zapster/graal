#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2007, 2015, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# ----------------------------------------------------------------------------------------------------

import os
from os.path import join, exists, getmtime, basename, isdir
from argparse import ArgumentParser, RawDescriptionHelpFormatter
import re
import stat
import zipfile
import tarfile
import subprocess
import tempfile
import shutil
import mx_truffle

import mx
from mx_gate import Task

from mx_unittest import unittest
from mx_javamodules import as_java_module
import mx_gate
import mx_unittest

import mx_graal_benchmark # pylint: disable=unused-import
import mx_graal_tools #pylint: disable=unused-import
import argparse
import shlex
import glob

_suite = mx.suite('compiler')

""" Prefix for running the VM. """
_vm_prefix = None

def get_vm_prefix(asList=True):
    """
    Get the prefix for running the VM (e.g. "gdb --args").
    """
    if asList:
        return _vm_prefix.split() if _vm_prefix is not None else []
    return _vm_prefix

#: The JDK used to build and run Graal.
jdk = mx.get_jdk(tag='default')

if jdk.javaCompliance < '1.8':
    mx.abort('Graal requires JDK8 or later, got ' + str(jdk))

#: Specifies if Graal is being built/run against JDK8. If false, then
#: JDK9 or later is being used (checked above).
isJDK8 = jdk.javaCompliance < '1.9'

def _check_jvmci_version(jdk):
    """
    Runs a Java utility to check that `jdk` supports the minimum JVMCI API required by Graal.
    """
    simplename = 'JVMCIVersionCheck'
    name = 'org.graalvm.compiler.hotspot.' + simplename
    binDir = mx.ensure_dir_exists(join(_suite.get_output_root(), '.jdk' + str(jdk.version)))
    if isinstance(_suite, mx.BinarySuite):
        javaSource = join(binDir, simplename + '.java')
        if not exists(javaSource):
            dists = [d for d in _suite.dists if d.name == 'GRAAL_HOTSPOT']
            assert len(dists) == 1, 'could not find GRAAL_HOTSPOT distribution'
            d = dists[0]
            assert exists(d.sourcesPath), 'missing expected file: ' + d.sourcesPath
            with zipfile.ZipFile(d.sourcesPath, 'r') as zf:
                with open(javaSource, 'w') as fp:
                    fp.write(zf.read(name.replace('.', '/') + '.java'))
    else:
        javaSource = join(_suite.dir, 'src', 'org.graalvm.compiler.hotspot', 'src', name.replace('.', '/') + '.java')
    javaClass = join(binDir, name.replace('.', '/') + '.class')
    if not exists(javaClass) or getmtime(javaClass) < getmtime(javaSource):
        mx.run([jdk.javac, '-d', binDir, javaSource])
    mx.run([jdk.java, '-cp', binDir, name])

if os.environ.get('JVMCI_VERSION_CHECK', None) != 'ignore':
    _check_jvmci_version(jdk)

class JVMCIClasspathEntry(object):
    """
    Denotes a distribution that is put on the JVMCI class path.

    :param str name: the name of the `JARDistribution` to be deployed
    """
    def __init__(self, name):
        self._name = name

    def dist(self):
        """
        Gets the `JARDistribution` deployed on the JVMCI class path.
        """
        return mx.distribution(self._name)

    def get_path(self):
        """
        Gets the path to the distribution jar file.

        :rtype: str
        """
        return self.dist().classpath_repr()

#: The deployed Graal distributions
_jvmci_classpath = [
    JVMCIClasspathEntry('GRAAL'),
]

def add_jvmci_classpath_entry(entry):
    """
    Appends an entry to the JVMCI classpath.
    """
    _jvmci_classpath.append(entry)

_bootclasspath_appends = []

def add_bootclasspath_append(dep):
    """
    Adds a distribution that must be appended to the boot class path
    """
    assert dep.isJARDistribution(), dep.name + ' is not a distribution'
    _bootclasspath_appends.append(dep)

mx_gate.add_jacoco_includes(['org.graalvm.compiler.*'])
mx_gate.add_jacoco_excluded_annotations(['@Snippet', '@ClassSubstitution'])

def _get_XX_option_value(vmargs, name, default):
    """
    Gets the value of an ``-XX:`` style HotSpot VM option.

    :param list vmargs: VM arguments to inspect
    :param str name: the name of the option
    :param default: the default value of the option if it's not present in `vmargs`
    :return: the value of the option as specified in `vmargs` or `default`
    """
    for arg in reversed(vmargs):
        if arg == '-XX:-' + name:
            return False
        if arg == '-XX:+' + name:
            return True
        if arg.startswith('-XX:' + name + '='):
            return arg[len('-XX:' + name + '='):]
    return default

def _is_jvmci_enabled(vmargs):
    """
    Determines if JVMCI is enabled according to the given VM arguments and whether JDK > 8.

    :param list vmargs: VM arguments to inspect
    """
    return _get_XX_option_value(vmargs, 'EnableJVMCI', isJDK8)

def _nodeCostDump(args, extraVMarguments=None):
    """list the costs associated with each Node type"""
    import csv, StringIO
    parser = ArgumentParser(prog='mx nodecostdump')
    parser.add_argument('--regex', action='store', help="Node Name Regex", default=False, metavar='<regex>')
    parser.add_argument('--markdown', action='store_const', const=True, help="Format to Markdown table", default=False)
    args, vmargs = parser.parse_known_args(args)
    additionalPrimarySuiteClassPath = '-Dprimary.suite.cp=' + mx.primary_suite().dir
    vmargs.extend([additionalPrimarySuiteClassPath, '-XX:-UseJVMCIClassLoader', 'org.graalvm.compiler.hotspot.NodeCostDumpUtil'])
    out = mx.OutputCapture()
    regex = ""
    if args.regex:
        regex = args.regex
    run_vm(vmargs + _remove_empty_entries(extraVMarguments) + [regex], out=out)
    if args.markdown:
        stringIO = StringIO.StringIO(out.data)
        reader = csv.reader(stringIO, delimiter=';', lineterminator="\n")
        firstRow = True
        maxLen = 0
        for row in reader:
            for col in row:
                maxLen = max(maxLen, len(col))
        stringIO.seek(0)
        for row in reader:
            s = '|'
            if firstRow:
                firstRow = False
                nrOfCols = len(row)
                for col in row:
                    s = s + col + "|"
                print s
                s = '|'
                for _ in range(nrOfCols):
                    s = s + ('-' * maxLen) + '|'
            else:
                for col in row:
                    s = s + col + "|"
            print s
    else:
        print out.data

def _ctw_jvmci_export_args():
    """
    Gets the VM args needed to export JVMCI API required by CTW.
    """
    if isJDK8:
        return ['-XX:-UseJVMCIClassLoader']
    else:
        return ['--add-exports=jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED',
                '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED',
                '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.services=ALL-UNNAMED',
                '--add-exports=jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED']

def _ctw_system_properties_suffix():
    out = mx.OutputCapture()
    out.data = 'System properties for CTW:\n\n'
    args = ['-XX:+EnableJVMCI'] + _ctw_jvmci_export_args()
    args.extend(['-cp', mx.classpath('org.graalvm.compiler.hotspot.test', jdk=jdk),
            '-DCompileTheWorld.Help=true', 'org.graalvm.compiler.hotspot.test.CompileTheWorld'])
    run_java(args, out=out, addDefaultArgs=False)
    return out.data

def ctw(args, extraVMarguments=None):
    """run CompileTheWorld"""

    defaultCtwopts = 'Inline=false'

    parser = ArgumentParser(prog='mx ctw', formatter_class=RawDescriptionHelpFormatter, epilog=_ctw_system_properties_suffix())
    parser.add_argument('--ctwopts', action='store', help='space separated Graal options used for CTW compilations (default: --ctwopts="' + defaultCtwopts + '")', metavar='<options>')
    parser.add_argument('--cp', '--jar', action='store', help='jar or class path denoting classes to compile', metavar='<path>')
    if not isJDK8:
        parser.add_argument('--limitmods', action='store', help='limits the set of compiled classes to only those in the listed modules', metavar='<modulename>[,<modulename>...]')

    configArgs = [a for a in args if a.startswith('-DCompileTheWorld.Config=')]
    args, vmargs = parser.parse_known_args(args)

    if args.ctwopts:
        if configArgs:
            mx.abort('Cannot specify both --ctwopts and -DCompileTheWorld.Config')
        vmargs.append('-DCompileTheWorld.Config=' + re.sub(r'\s+', '#', args.ctwopts))
    elif not configArgs:
        vmargs.append('-DCompileTheWorld.Config=Inline=false')

    # suppress menubar and dock when running on Mac; exclude x11 classes as they may cause VM crashes (on Solaris)
    vmargs = ['-Djava.awt.headless=true'] + vmargs

    if args.cp:
        cp = os.path.abspath(args.cp)
        if not isJDK8 and not _is_jvmci_enabled(vmargs):
            mx.abort('Non-Graal CTW does not support specifying a specific class path or jar to compile')
    else:
        # Default to the CompileTheWorld.SUN_BOOT_CLASS_PATH token
        cp = None

    vmargs.append('-DCompileTheWorld.ExcludeMethodFilter=sun.awt.X11.*.*')

    if _get_XX_option_value(vmargs + _remove_empty_entries(extraVMarguments), 'UseJVMCICompiler', False):
        vmargs.append('-XX:+BootstrapJVMCI')

    mainClassAndArgs = []
    if isJDK8:
        if not _is_jvmci_enabled(vmargs):
            vmargs.append('-XX:+CompileTheWorld')
            if cp is not None:
                vmargs.append('-Xbootclasspath/p:' + cp)
        else:
            if cp is not None:
                vmargs.append('-DCompileTheWorld.Classpath=' + cp)
            vmargs.extend(_ctw_jvmci_export_args() + ['-cp', mx.classpath('org.graalvm.compiler.hotspot.test', jdk=jdk)])
            mainClassAndArgs = ['org.graalvm.compiler.hotspot.test.CompileTheWorld']
    else:
        if _is_jvmci_enabled(vmargs):
            # To be able to load all classes in the JRT with Class.forName,
            # all JDK modules need to be made root modules.
            limitmods = frozenset(args.limitmods.split(',')) if args.limitmods else None
            nonBootJDKModules = [m.name for m in jdk.get_modules() if not m.boot and (limitmods is None or m.name in limitmods)]
            if nonBootJDKModules:
                vmargs.append('--add-modules=' + ','.join(nonBootJDKModules))
            if args.limitmods:
                vmargs.append('-DCompileTheWorld.limitmods=' + args.limitmods)
            if cp is not None:
                vmargs.append('-DCompileTheWorld.Classpath=' + cp)
            vmargs.extend(_ctw_jvmci_export_args() + ['-cp', mx.classpath('org.graalvm.compiler.hotspot.test', jdk=jdk)])
            mainClassAndArgs = ['org.graalvm.compiler.hotspot.test.CompileTheWorld']
        else:
            vmargs.append('-XX:+CompileTheWorld')

    run_vm(vmargs + _remove_empty_entries(extraVMarguments) + mainClassAndArgs)

def verify_jvmci_ci_versions(args):
    """
    Checks that the jvmci versions used in various ci files agree.

    If the ci.hocon files use a -dev version, it allows the travis ones to use the previous version.
    For example, if ci.hocon uses jvmci-0.24-dev, travis may use either jvmci-0.24-dev or jvmci-0.23
    """
    version_pattern = re.compile(r'^(?!\s*#).*jvmci-(?P<version>\d*\.\d*)(?P<dev>-dev)?')

    def _grep_version(files, msg):
        version = None
        dev = None
        last = None
        linenr = 0
        for filename in files:
            for line in open(filename):
                m = version_pattern.search(line)
                if m:
                    new_version = m.group('version')
                    new_dev = bool(m.group('dev'))
                    if (version and version != new_version) or (dev is not None and dev != new_dev):
                        mx.abort(
                            os.linesep.join([
                                "Multiple JVMCI versions found in {0} files:".format(msg),
                                "  {0} in {1}:{2}:    {3}".format(version + ('-dev' if dev else ''), *last),
                                "  {0} in {1}:{2}:    {3}".format(new_version + ('-dev' if new_dev else ''), filename, linenr, line),
                            ]))
                    last = (filename, linenr, line.rstrip())
                    version = new_version
                    dev = new_dev
                linenr += 1
        if not version:
            mx.abort("No JVMCI version found in {0} files!".format(msg))
        return version, dev

    primary_suite = mx.primary_suite()
    hocon_version, hocon_dev = _grep_version(
        glob.glob(join(primary_suite.vc_dir, '*.hocon')) +
        glob.glob(join(primary_suite.dir, 'ci*.hocon')) +
        glob.glob(join(primary_suite.dir, 'ci*/*.hocon')), 'hocon')
    travis_version, travis_dev = _grep_version([join(primary_suite.vc_dir, '.travis.yml')], 'TravisCI')

    if hocon_version != travis_version or hocon_dev != travis_dev:
        versions_ok = False
        if not travis_dev and hocon_dev:
            next_travis_version = [int(a) for a in travis_version.split('.')]
            next_travis_version[-1] += 1
            next_travis_version_str = '.'.join((str(a) for a in next_travis_version))
            if next_travis_version_str == hocon_version:
                versions_ok = True
        if not versions_ok:
            mx.abort("Travis and ci.hocon JVMCI versions do not match: {0} vs. {1}".format(travis_version + ('-dev' if travis_dev else ''), hocon_version + ('-dev' if hocon_dev else '')))
    mx.log('JVMCI versions are ok!')


class UnitTestRun:
    def __init__(self, name, args, tags):
        self.name = name
        self.args = args
        self.tags = tags

    def run(self, suites, tasks, extraVMarguments=None):
        for suite in suites:
            with Task(self.name + ': hosted-product ' + suite, tasks, tags=self.tags) as t:
                if mx_gate.Task.verbose:
                    extra_args = ['--verbose', '--enable-timing']
                else:
                    extra_args = []
                if t: unittest(['--suite', suite, '--fail-fast'] + extra_args + self.args + _remove_empty_entries(extraVMarguments))

class BootstrapTest:
    def __init__(self, name, args, tags, suppress=None):
        self.name = name
        self.args = args
        self.suppress = suppress
        self.tags = tags
        if tags is not None and (type(tags) is not list or all(not isinstance(x, basestring) for x in tags)):
            mx.abort("Gate tag argument must be a list of strings, tag argument:" + str(tags))

    def run(self, tasks, extraVMarguments=None):
        with Task(self.name, tasks, tags=self.tags) as t:
            if t:
                if self.suppress:
                    out = mx.DuplicateSuppressingStream(self.suppress).write
                else:
                    out = None
                run_vm(self.args + ['-XX:+UseJVMCICompiler'] + _remove_empty_entries(extraVMarguments) + ['-XX:-TieredCompilation', '-XX:+BootstrapJVMCI', '-version'], out=out)

class GraalTags:
    bootstrap = ['bootstrap', 'fulltest']
    bootstraplite = ['bootstraplite', 'bootstrap', 'fulltest']
    bootstrapfullverify = ['bootstrapfullverify', 'fulltest']
    test = ['test', 'fulltest']
    benchmarktest = ['benchmarktest', 'fulltest']
    ctw = ['ctw', 'fulltest']
    doc = ['javadoc']

def _remove_empty_entries(a):
    """Removes empty entries. Return value is always a list."""
    if not a:
        return []
    return [x for x in a if x]

def _gate_java_benchmark(args, successRe):
    """
    Runs a Java benchmark and aborts if the benchmark process exits with a non-zero
    exit code or the `successRe` pattern is not in the output of the benchmark process.

    :param list args: the arguments to pass to the VM
    :param str successRe: a regular expression
    """
    out = mx.OutputCapture()
    try:
        run_java(args, out=mx.TeeOutputCapture(out), err=subprocess.STDOUT)
    finally:
        jvmErrorFile = re.search(r'(([A-Z]:|/).*[/\]hs_err_pid[0-9]+\.log)', out.data)
        if jvmErrorFile:
            jvmErrorFile = jvmErrorFile.group()
            mx.log('Dumping ' + jvmErrorFile)
            with open(jvmErrorFile, 'rb') as fp:
                mx.log(fp.read())
            os.unlink(jvmErrorFile)

    if not re.search(successRe, out.data, re.MULTILINE):
        mx.abort('Could not find benchmark success pattern: ' + successRe)

def _is_batik_supported(jdk):
    """
    Determines if Batik runs on the given jdk. Batik's JPEGRegistryEntry contains a reference
    to TruncatedFileException, which is specific to the Sun/Oracle JDK. On a different JDK,
    this results in a NoClassDefFoundError: com/sun/image/codec/jpeg/TruncatedFileException
    """
    try:
        subprocess.check_output([jdk.javap, 'com.sun.image.codec.jpeg.TruncatedFileException'])
        return True
    except subprocess.CalledProcessError:
        mx.warn('Batik uses Sun internal class com.sun.image.codec.jpeg.TruncatedFileException which is not present in ' + jdk.home)
        return False

def _gate_dacapo(name, iterations, extraVMarguments=None):
    vmargs = ['-Xms2g', '-XX:+UseSerialGC', '-XX:-UseCompressedOops', '-Djava.net.preferIPv4Stack=true', '-Dgraal.CompilationFailureAction=ExitVM'] + _remove_empty_entries(extraVMarguments)
    dacapoJar = mx.library('DACAPO').get_path(True)
    if name == 'batik' and not _is_batik_supported(jdk):
        return
    _gate_java_benchmark(vmargs + ['-jar', dacapoJar, name, '-n', str(iterations)], r'^===== DaCapo 9\.12 ([a-zA-Z0-9_]+) PASSED in ([0-9]+) msec =====')

def _gate_scala_dacapo(name, iterations, extraVMarguments=None):
    vmargs = ['-Xms2g', '-XX:+UseSerialGC', '-XX:-UseCompressedOops', '-Dgraal.CompilationFailureAction=ExitVM'] + _remove_empty_entries(extraVMarguments)
    scalaDacapoJar = mx.library('DACAPO_SCALA').get_path(True)
    _gate_java_benchmark(vmargs + ['-jar', scalaDacapoJar, name, '-n', str(iterations)], r'^===== DaCapo 0\.1\.0(-SNAPSHOT)? ([a-zA-Z0-9_]+) PASSED in ([0-9]+) msec =====')


def jvmci_ci_version_gate_runner(tasks):
    # Check that travis and ci.hocon use the same JVMCI version
    with Task('JVMCI_CI_VersionSyncCheck', tasks, tags=[mx_gate.Tags.style]) as t:
        if t: verify_jvmci_ci_versions([])

def compiler_gate_runner(suites, unit_test_runs, bootstrap_tests, tasks, extraVMarguments=None):
    if jdk.javaCompliance >= '9':
        with Task('JDK9_java_base_test', tasks, tags=GraalTags.test) as t:
            if t: java_base_unittest(_remove_empty_entries(extraVMarguments))

    # Run unit tests in hosted mode
    for r in unit_test_runs:
        r.run(suites, tasks, ['-XX:-UseJVMCICompiler'] + _remove_empty_entries(extraVMarguments))

    # Ensure makegraaljdk works
    with Task('MakeGraalJDK', tasks, tags=GraalTags.test) as t:
        if t and isJDK8:
            try:
                makegraaljdk(['-a', 'graaljdk.tar', 'graaljdk'])
            finally:
                if exists('graaljdk'):
                    shutil.rmtree('graaljdk')
                if exists('graaljdk.tar'):
                    os.unlink('graaljdk.tar')

    # Run ctw against rt.jar on hosted
    with Task('CTW:hosted', tasks, tags=GraalTags.ctw) as t:
        if t:
            ctw([
                    '--ctwopts', 'Inline=false CompilationFailureAction=ExitVM', '-esa', '-XX:-UseJVMCICompiler', '-XX:+EnableJVMCI',
                    '-DCompileTheWorld.MultiThreaded=true', '-Dgraal.InlineDuringParsing=false',
                    '-DCompileTheWorld.Verbose=false', '-XX:ReservedCodeCacheSize=300m',
                ], _remove_empty_entries(extraVMarguments))

    # bootstrap tests
    for b in bootstrap_tests:
        b.run(tasks, extraVMarguments)

    # run selected DaCapo benchmarks
    dacapos = {
        'avrora':     1,
        'batik':      1,
        'fop':        8,
        'h2':         1,
        'jython':     2,
        'luindex':    1,
        'lusearch':   4,
        'pmd':        1,
        'sunflow':    2,
        'xalan':      1,
    }
    for name, iterations in sorted(dacapos.iteritems()):
        with Task('DaCapo:' + name, tasks, tags=GraalTags.benchmarktest) as t:
            if t: _gate_dacapo(name, iterations, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler'])

    # run selected Scala DaCapo benchmarks
    scala_dacapos = {
        'actors':     1,
        'apparat':    1,
        'factorie':   1,
        'kiama':      4,
        'scalac':     1,
        'scaladoc':   1,
        'scalap':     1,
        'scalariform':1,
        'scalatest':  1,
        'scalaxb':    1,
        'tmt':        1
    }
    for name, iterations in sorted(scala_dacapos.iteritems()):
        with Task('ScalaDaCapo:' + name, tasks, tags=GraalTags.benchmarktest) as t:
            if t: _gate_scala_dacapo(name, iterations, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler'])

    # ensure -Xbatch still works
    with Task('DaCapo_pmd:BatchMode', tasks, tags=GraalTags.test) as t:
        if t: _gate_dacapo('pmd', 1, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Xbatch'])

    # ensure benchmark counters still work
    with Task('DaCapo_pmd:BenchmarkCounters', tasks, tags=GraalTags.test) as t:
        if t: _gate_dacapo('pmd', 1, _remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Dgraal.LIRProfileMoves=true', '-Dgraal.GenericDynamicCounters=true', '-XX:JVMCICounterSize=10'])

    # ensure -Xcomp still works
    with Task('XCompMode:product', tasks, tags=GraalTags.test) as t:
        if t: run_vm(_remove_empty_entries(extraVMarguments) + ['-XX:+UseJVMCICompiler', '-Xcomp', '-version'])

    with Task('Javadoc', tasks, tags=GraalTags.doc) as t:
        if t: mx.javadoc([])

graal_unit_test_runs = [
    UnitTestRun('UnitTests', [], tags=GraalTags.test),
]

_registers = 'o0,o1,o2,o3,f8,f9,d32,d34' if mx.get_arch() == 'sparcv9' else 'rbx,r11,r10,r14,xmm3,xmm11,xmm14'

_defaultFlags = ['-Dgraal.CompilationWatchDogStartDelay=60.0D']
_assertionFlags = ['-esa']
_graalErrorFlags = ['-Dgraal.CompilationFailureAction=ExitVM']
_graalEconomyFlags = ['-Dgraal.CompilerConfiguration=economy']
_verificationFlags = ['-Dgraal.VerifyGraalGraphs=true', '-Dgraal.VerifyGraalGraphEdges=true', '-Dgraal.VerifyGraalPhasesSize=true', '-Dgraal.VerifyPhases=true']
_coopFlags = ['-XX:-UseCompressedOops']
_gcVerificationFlags = ['-XX:+UnlockDiagnosticVMOptions', '-XX:+VerifyBeforeGC', '-XX:+VerifyAfterGC']
_g1VerificationFlags = ['-XX:-UseSerialGC', '-XX:+UseG1GC']
_exceptionFlags = ['-Dgraal.StressInvokeWithExceptionNode=true']
_registerPressureFlags = ['-Dgraal.RegisterPressure=' + _registers]
_immutableCodeFlags = ['-Dgraal.ImmutableCode=true']

graal_bootstrap_tests = [
    BootstrapTest('BootstrapWithSystemAssertionsFullVerify', _defaultFlags + _assertionFlags + _verificationFlags + _graalErrorFlags, tags=GraalTags.bootstrapfullverify),
    BootstrapTest('BootstrapWithSystemAssertions', _defaultFlags + _assertionFlags + _graalErrorFlags, tags=GraalTags.bootstraplite),
    BootstrapTest('BootstrapWithSystemAssertionsNoCoop', _defaultFlags + _assertionFlags + _coopFlags + _graalErrorFlags, tags=GraalTags.bootstrap),
    BootstrapTest('BootstrapWithGCVerification', _defaultFlags + _gcVerificationFlags + _graalErrorFlags, tags=GraalTags.bootstrap, suppress=['VerifyAfterGC:', 'VerifyBeforeGC:']),
    BootstrapTest('BootstrapWithG1GCVerification', _defaultFlags + _g1VerificationFlags + _gcVerificationFlags + _graalErrorFlags, tags=GraalTags.bootstrap, suppress=['VerifyAfterGC:', 'VerifyBeforeGC:']),
    BootstrapTest('BootstrapWithSystemAssertionsEconomy', _defaultFlags + _assertionFlags + _graalEconomyFlags + _graalErrorFlags, tags=GraalTags.bootstrap),
    BootstrapTest('BootstrapWithSystemAssertionsExceptionEdges', _defaultFlags + _assertionFlags + _exceptionFlags + _graalErrorFlags, tags=GraalTags.bootstrap),
    BootstrapTest('BootstrapWithSystemAssertionsRegisterPressure', _defaultFlags + _assertionFlags + _registerPressureFlags + _graalErrorFlags, tags=GraalTags.bootstrap),
    BootstrapTest('BootstrapWithSystemAssertionsImmutableCode', _defaultFlags + _assertionFlags + _immutableCodeFlags + ['-Dgraal.VerifyPhases=true'] + _graalErrorFlags, tags=GraalTags.bootstrap)
]

def _graal_gate_runner(args, tasks):
    compiler_gate_runner(['compiler', 'truffle'], graal_unit_test_runs, graal_bootstrap_tests, tasks, args.extra_vm_argument)
    jvmci_ci_version_gate_runner(tasks)

class ShellEscapedStringAction(argparse.Action):
    """Turns a shell-escaped string into a list of arguments.
       Note that it appends the result to the destination.
    """
    def __init__(self, option_strings, nargs=None, **kwargs):
        if nargs is not None:
            raise ValueError("nargs not allowed")
        super(ShellEscapedStringAction, self).__init__(option_strings, **kwargs)

    def __call__(self, parser, namespace, values, option_string=None):
        # do not override existing values
        old_values = getattr(namespace, self.dest)
        setattr(namespace, self.dest, (old_values if old_values else []) + shlex.split(values))

mx_gate.add_gate_runner(_suite, _graal_gate_runner)
mx_gate.add_gate_argument('--extra-vm-argument', action=ShellEscapedStringAction, help='add extra vm arguments to gate tasks if applicable')

def _unittest_vm_launcher(vmArgs, mainClass, mainClassArgs):
    run_vm(vmArgs + [mainClass] + mainClassArgs)

def _unittest_config_participant(config):
    vmArgs, mainClass, mainClassArgs = config
    cpIndex, cp = mx.find_classpath_arg(vmArgs)
    if cp:
        cp = _uniqify(cp.split(os.pathsep))
        if isJDK8:
            # Remove entries from class path that are in Graal or on the boot class path
            redundantClasspathEntries = set()
            for dist in [entry.dist() for entry in _jvmci_classpath]:
                redundantClasspathEntries.update((d.output_dir() for d in dist.archived_deps() if d.isJavaProject()))
                redundantClasspathEntries.add(dist.path)
            cp = os.pathsep.join([e for e in cp if e not in redundantClasspathEntries])
            vmArgs[cpIndex] = cp
        else:
            redundantClasspathEntries = set()
            for dist in [entry.dist() for entry in _jvmci_classpath] + _bootclasspath_appends:
                redundantClasspathEntries.update(mx.classpath(dist, preferProjects=False, jdk=jdk).split(os.pathsep))
                redundantClasspathEntries.update(mx.classpath(dist, preferProjects=True, jdk=jdk).split(os.pathsep))
                if hasattr(dist, 'overlaps'):
                    for o in dist.overlaps:
                        path = mx.distribution(o).classpath_repr()
                        if path:
                            redundantClasspathEntries.add(path)

            # Remove entries from the class path that are in the deployed modules
            cp = [classpathEntry for classpathEntry in cp if classpathEntry not in redundantClasspathEntries]
            vmArgs[cpIndex] = os.pathsep.join(cp)

            # JVMCI is dynamically exported to Graal when JVMCI is initialized. This is too late
            # for the junit harness which uses reflection to find @Test methods. In addition, the
            # tests widely use JVMCI classes so JVMCI needs to also export all its packages to
            # ALL-UNNAMED.
            jvmci = [m for m in jdk.get_modules() if m.name == 'jdk.internal.vm.ci'][0]
            vmArgs.extend(['--add-exports=' + jvmci.name + '/' + p + '=jdk.internal.vm.compiler,ALL-UNNAMED' for p in jvmci.packages])

    if isJDK8:
        # Run the VM in a mode where application/test classes can
        # access JVMCI loaded classes.
        vmArgs.append('-XX:-UseJVMCIClassLoader')

    return (vmArgs, mainClass, mainClassArgs)

mx_unittest.add_config_participant(_unittest_config_participant)
mx_unittest.set_vm_launcher('JDK VM launcher', _unittest_vm_launcher, jdk)

def _uniqify(alist):
    """
    Processes given list to remove all duplicate entries, preserving only the first unique instance for each entry.

    :param list alist: the list to process
    :return: `alist` with all duplicates removed
    """
    seen = set()
    return [e for e in alist if e not in seen and seen.add(e) is None]

def _parseVmArgs(args, addDefaultArgs=True):
    args = mx.expand_project_in_args(args, insitu=False)

    argsPrefix = []
    jacocoArgs = mx_gate.get_jacoco_agent_args()
    if jacocoArgs:
        argsPrefix.extend(jacocoArgs)

    # add default graal.options.file
    options_file = join(mx.primary_suite().dir, 'graal.options')
    if exists(options_file):
        argsPrefix.append('-Dgraal.options.file=' + options_file)

    if isJDK8:
        argsPrefix.append('-Djvmci.class.path.append=' + os.pathsep.join((e.get_path() for e in _jvmci_classpath)))
        argsPrefix.append('-Xbootclasspath/a:' + os.pathsep.join([dep.classpath_repr() for dep in _bootclasspath_appends]))
    else:
        deployedDists = [entry.dist() for entry in _jvmci_classpath] + \
                        [e for e in _bootclasspath_appends if e.isJARDistribution()]
        deployedModules = [as_java_module(dist, jdk) for dist in deployedDists]

        # Set or update module path to include Graal and its dependencies as modules
        jdkModuleNames = frozenset([m.name for m in jdk.get_modules()])
        graalModulepath = []
        graalUpgrademodulepath = []

        def _addToModulepath(modules):
            for m in modules:
                if m.jarpath:
                    modulepath = graalModulepath if m.name not in jdkModuleNames else graalUpgrademodulepath
                    if m not in modulepath:
                        modulepath.append(m)

        for deployedModule in deployedModules:
            _addToModulepath(deployedModule.modulepath)
            _addToModulepath([deployedModule])

        # Extend or set --module-path argument
        mpUpdated = False
        for mpIndex in range(len(args)):
            assert not args[mpIndex].startswith('--upgrade-module-path')
            if args[mpIndex] == '--module-path':
                assert mpIndex + 1 < len(args), 'VM option ' + args[mpIndex] + ' requires an argument'
                args[mpIndex + 1] = os.pathsep.join(_uniqify(args[mpIndex + 1].split(os.pathsep) + [m.jarpath for m in graalModulepath]))
                mpUpdated = True
                break
            elif args[mpIndex].startswith('--module-path='):
                mp = args[mpIndex][len('--module-path='):]
                args[mpIndex] = '--module-path=' + os.pathsep.join(_uniqify(mp.split(os.pathsep) + [m.jarpath for m in graalModulepath]))
                mpUpdated = True
                break
        if not mpUpdated:
            argsPrefix.append('--module-path=' + os.pathsep.join([m.jarpath for m in graalModulepath]))

        if graalUpgrademodulepath:
            argsPrefix.append('--upgrade-module-path=' + os.pathsep.join([m.jarpath for m in graalUpgrademodulepath]))

    if '-version' in args:
        ignoredArgs = args[args.index('-version') + 1:]
        if len(ignoredArgs) > 0:
            mx.log("Warning: The following options will be ignored by the VM because they come after the '-version' argument: " + ' '.join(ignoredArgs))

    return jdk.processArgs(argsPrefix + args, addDefaultArgs=addDefaultArgs)

def _check_bootstrap_config(args):
    """
    Issues a warning if `args` denote -XX:+BootstrapJVMCI but -XX:-UseJVMCICompiler.
    """
    bootstrap = False
    useJVMCICompiler = False
    for arg in args:
        if arg == '-XX:+BootstrapJVMCI':
            bootstrap = True
        elif arg == '-XX:+UseJVMCICompiler':
            useJVMCICompiler = True
    if bootstrap and not useJVMCICompiler:
        mx.warn('-XX:+BootstrapJVMCI is ignored since -XX:+UseJVMCICompiler is not enabled')

class StdoutUnstripping:
    """
    A context manager for logging and unstripping the console output for a subprocess
    execution. The logging and unstripping is only attempted if stdout and stderr
    for the execution were not already being redirected and existing *.map files
    were detected in the arguments to the execution.
    """
    def __init__(self, args, out, err, mapFiles=None):
        self.args = args
        self.out = out
        self.err = err
        self.capture = None
        self.mapFiles = mapFiles

    def __enter__(self):
        if mx.get_opts().strip_jars and self.out is None and (self.err is None or self.err == subprocess.STDOUT):
            delims = re.compile('[' + os.pathsep + '=]')
            for a in self.args:
                for e in delims.split(a):
                    candidate = e + '.map'
                    if exists(candidate):
                        if self.mapFiles is None:
                            self.mapFiles = set()
                        self.mapFiles.add(candidate)
            self.capture = mx.OutputCapture()
            self.out = mx.TeeOutputCapture(self.capture)
            self.err = self.out
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        if self.mapFiles:
            try:
                with tempfile.NamedTemporaryFile() as inputFile:
                    with tempfile.NamedTemporaryFile() as mapFile:
                        if len(self.capture.data) != 0:
                            inputFile.write(self.capture.data)
                            inputFile.flush()
                            for e in self.mapFiles:
                                with open(e, 'r') as m:
                                    shutil.copyfileobj(m, mapFile)
                                    mapFile.flush()
                            retraceOut = mx.OutputCapture()
                            proguard_cp = mx.classpath(['PROGUARD_RETRACE', 'PROGUARD'])
                            mx.run([jdk.java, '-cp', proguard_cp, 'proguard.retrace.ReTrace', mapFile.name, inputFile.name], out=retraceOut)
                            if self.capture.data != retraceOut.data:
                                mx.log('>>>> BEGIN UNSTRIPPED OUTPUT')
                                mx.log(retraceOut.data)
                                mx.log('<<<< END UNSTRIPPED OUTPUT')
            except BaseException as e:
                mx.log('Error unstripping output from VM execution with stripped jars: ' + str(e))
        return None

def run_java(args, nonZeroIsFatal=True, out=None, err=None, cwd=None, timeout=None, env=None, addDefaultArgs=True):
    args = ['-XX:+UnlockExperimentalVMOptions', '-XX:+EnableJVMCI'] + _parseVmArgs(args, addDefaultArgs=addDefaultArgs)
    _check_bootstrap_config(args)
    cmd = get_vm_prefix() + [jdk.java] + ['-server'] + args
    with StdoutUnstripping(args, out, err) as u:
        return mx.run(cmd, nonZeroIsFatal=nonZeroIsFatal, out=u.out, err=u.err, cwd=cwd, env=env)

_JVMCI_JDK_TAG = 'jvmci'

class GraalJVMCI9JDKConfig(mx.JDKConfig):
    """
    A JDKConfig that configures Graal as the JVMCI compiler.
    """
    def __init__(self):
        mx.JDKConfig.__init__(self, jdk.home, tag=_JVMCI_JDK_TAG)

    def run_java(self, args, **kwArgs):
        return run_java(args, **kwArgs)

class GraalJDKFactory(mx.JDKFactory):
    def getJDKConfig(self):
        return GraalJVMCI9JDKConfig()

    def description(self):
        return "JVMCI JDK with Graal"

mx.addJDKFactory(_JVMCI_JDK_TAG, mx.JavaCompliance('9'), GraalJDKFactory())

def run_vm(args, nonZeroIsFatal=True, out=None, err=None, cwd=None, timeout=None, debugLevel=None, vmbuild=None):
    """run a Java program by executing the java executable in a JVMCI JDK"""
    return run_java(args, nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd, timeout=timeout)

class GraalArchiveParticipant:
    def __init__(self, dist, isTest=False):
        self.dist = dist
        self.isTest = isTest

    def __opened__(self, arc, srcArc, services):
        self.services = services
        self.arc = arc

    def __add__(self, arcname, contents):
        if arcname.startswith('META-INF/providers/'):
            if self.isTest:
                # The test distributions must not have their @ServiceProvider
                # generated providers converted to real services otherwise
                # bad things can happen such as InvocationPlugins being registered twice.
                pass
            else:
                provider = arcname[len('META-INF/providers/'):]
                for service in contents.strip().split(os.linesep):
                    assert service
                    self.services.setdefault(service, []).append(provider)
            return True
        elif arcname.endswith('_OptionDescriptors.class'):
            if self.isTest:
                mx.warn('@Option defined in test code will be ignored: ' + arcname)
            else:
                # Need to create service files for the providers of the
                # jdk.internal.vm.ci.options.Options service created by
                # jdk.internal.vm.ci.options.processor.OptionProcessor.
                provider = arcname[:-len('.class'):].replace('/', '.')
                self.services.setdefault('org.graalvm.compiler.options.OptionDescriptors', []).append(provider)
        return False

    def __addsrc__(self, arcname, contents):
        return False

    def __closing__(self):
        pass

mx.add_argument('--vmprefix', action='store', dest='vm_prefix', help='prefix for running the VM (e.g. "gdb --args")', metavar='<prefix>')
mx.add_argument('--gdb', action='store_const', const='gdb --args', dest='vm_prefix', help='alias for --vmprefix "gdb --args"')
mx.add_argument('--lldb', action='store_const', const='lldb --', dest='vm_prefix', help='alias for --vmprefix "lldb --"')

def sl(args):
    """run an SL program"""
    mx.get_opts().jdk = 'jvmci'
    mx_truffle.sl(args)

def java_base_unittest(args):
    """tests whether graal compiler runs on JDK9 with limited set of modules"""
    jlink = mx.exe_suffix(join(jdk.home, 'bin', 'jlink'))
    if not exists(jlink):
        raise mx.JDKConfigException('jlink tool does not exist: ' + jlink)
    basejdk_dir = join(_suite.get_output_root(), 'jdkbase')
    basemodules = 'java.base,jdk.internal.vm.ci,jdk.unsupported'
    if exists(basejdk_dir):
        shutil.rmtree(basejdk_dir)
    mx.run([jlink, '--output', basejdk_dir, '--add-modules', basemodules, '--module-path', join(jdk.home, 'jmods')])
    jdwp = mx.add_lib_suffix(mx.add_lib_prefix('jdwp'))
    shutil.copy(join(jdk.home, 'lib', jdwp), join(basejdk_dir, 'lib', jdwp))
    dt_socket = mx.add_lib_suffix(mx.add_lib_prefix('dt_socket'))
    shutil.copy(join(jdk.home, 'lib', dt_socket), join(basejdk_dir, 'lib', dt_socket))

    if not args:
        args = []

    fakeJavac = join(basejdk_dir, 'bin', 'javac')
    open(fakeJavac, 'a').close()

    basejdk = mx.JDKConfig(basejdk_dir)
    savedJava = jdk.java
    try:
        jdk.java = basejdk.java
        if mx_gate.Task.verbose:
            extra_args = ['--verbose', '--enable-timing']
        else:
            extra_args = []
        mx_unittest.unittest(['--suite', 'compiler', '--fail-fast'] + extra_args + args)
    finally:
        jdk.java = savedJava

def microbench(*args):
    mx.abort("`mx microbench` is deprecated.\n" +
             "Use `mx benchmark jmh-whitebox:*` and `mx benchmark jmh-dist:*` instead!")

def javadoc(args):
    mx.javadoc(args)

def create_archive(srcdir, arcpath, prefix):
    """
    Creates a compressed archive of a given directory.

    :param str srcdir: directory to archive
    :param str arcpath: path of file to contain the archive. The extension of `path`
           specifies the type of archive to create
    :param str prefix: the prefix to apply to each entry in the archive
    """

    def _taradd(arc, filename, arcname):
        arc.add(name=f, arcname=arcname, recursive=False)
    def _zipadd(arc, filename, arcname):
        arc.write(filename, arcname)

    if arcpath.endswith('.zip'):
        arc = zipfile.ZipFile(arcpath, 'w', zipfile.ZIP_DEFLATED)
        add = _zipadd
    elif arcpath.endswith('.tar'):
        arc = tarfile.open(arcpath, 'w')
        add = _taradd
    elif arcpath.endswith('.tgz') or arcpath.endswith('.tar.gz'):
        arc = tarfile.open(arcpath, 'w:gz')
        add = _taradd
    else:
        mx.abort('unsupported archive kind: ' + arcpath)

    for root, _, filenames in os.walk(srcdir):
        for name in filenames:
            f = join(root, name)
            # Make sure files in the image are readable by everyone
            file_mode = os.stat(f).st_mode
            mode = stat.S_IRGRP | stat.S_IROTH | file_mode
            if isdir(f) or (file_mode & stat.S_IXUSR):
                mode = mode | stat.S_IXGRP | stat.S_IXOTH
            os.chmod(f, mode)
            arcname = prefix + os.path.relpath(f, srcdir)
            add(arc, f, arcname)
    arc.close()

def makegraaljdk(args):
    """make a JDK with Graal as the default top level JIT"""
    parser = ArgumentParser(prog='mx makegraaljdk')
    parser.add_argument('-f', '--force', action='store_true', help='overwrite existing GraalJDK')
    parser.add_argument('-a', '--archive', action='store', help='name of archive to create', metavar='<path>')
    parser.add_argument('dest', help='destination directory for GraalJDK', metavar='<path>')
    args = parser.parse_args(args)
    if isJDK8:
        dstJdk = os.path.abspath(args.dest)
        srcJdk = jdk.home
        if exists(dstJdk):
            if args.force:
                shutil.rmtree(dstJdk)
            else:
                mx.abort('Use --force to overwrite existing directory ' + dstJdk)
        mx.log('Creating {} from {}'.format(dstJdk, srcJdk))
        shutil.copytree(srcJdk, dstJdk)

        bootDir = mx.ensure_dir_exists(join(dstJdk, 'jre', 'lib', 'boot'))
        jvmciDir = join(dstJdk, 'jre', 'lib', 'jvmci')
        assert exists(jvmciDir), jvmciDir + ' does not exist'

        if mx.get_os() == 'darwin' or mx.get_os() == 'windows':
            jvmlibDir = join(dstJdk, 'jre', 'lib', 'server')
        else:
            jvmlibDir = join(dstJdk, 'jre', 'lib', mx.get_arch(), 'server')
        jvmlib = join(jvmlibDir, mx.add_lib_prefix(mx.add_lib_suffix('jvm')))
        assert exists(jvmlib), jvmlib + ' does not exist'

        with open(join(jvmciDir, 'compiler-name'), 'w') as fp:
            print >> fp, 'graal'
        vmName = 'Graal'
        mapFiles = set()
        for e in _jvmci_classpath:
            src = basename(e.get_path())
            mx.log('Copying {} to {}'.format(e.get_path(), jvmciDir))
            candidate = e.get_path() + '.map'
            if exists(candidate):
                mapFiles.add(candidate)
            with open(join(dstJdk, 'release'), 'a') as fp:
                d = e.dist()
                s = d.suite
                print >> fp, '{}={}'.format(d.name, s.vc.parent(s.dir))
                vmName = vmName + ':' + s.name + '_' + s.version()
            shutil.copyfile(e.get_path(), join(jvmciDir, src))
        for e in _bootclasspath_appends:
            src = basename(e.classpath_repr())
            mx.log('Copying {} to {}'.format(e.classpath_repr(), bootDir))
            candidate = e.classpath_repr() + '.map'
            if exists(candidate):
                mapFiles.add(candidate)

            with open(join(dstJdk, 'release'), 'a') as fp:
                s = e.suite
                print >> fp, '{}={}'.format(e.name, s.vc.parent(s.dir))
            shutil.copyfile(e.classpath_repr(), join(bootDir, src))

        out = mx.LinesOutputCapture()
        mx.run([jdk.java, '-version'], err=out)
        line = None
        pattern = re.compile(r'(.* )(?:Server|Graal) VM \(build.*')
        for line in out.lines:
            m = pattern.match(line)
            if m:
                with open(join(jvmlibDir, 'vm.properties'), 'w') as fp:
                    # Modify VM name in `java -version` to be Graal along
                    # with a suffix denoting the commit of each Graal jar.
                    # For example:
                    # Java HotSpot(TM) 64-Bit Graal:compiler_88847fb25d1a62977a178331a5e78fa5f8fcbb1a (build 25.71-b01-internal-jvmci-0.34, mixed mode)
                    print >> fp, 'name=' + m.group(1) + vmName
                line = True
                break
        if line is not True:
            mx.abort('Could not find "{}" in output of `java -version`:\n{}'.format(pattern.pattern, os.linesep.join(out.lines)))

        exe = join(dstJdk, 'bin', mx.exe_suffix('java'))
        with StdoutUnstripping(args=[], out=None, err=None, mapFiles=mapFiles) as u:
            mx.run([exe, '-XX:+BootstrapJVMCI', '-version'], out=u.out, err=u.err)
        if args.archive:
            mx.log('Archiving {}'.format(args.archive))
            create_archive(dstJdk, args.archive, basename(args.dest) + '/')
    else:
        mx.abort('Can only make GraalJDK for JDK 8 currently')

mx.update_commands(_suite, {
    'sl' : [sl, '[SL args|@VM options]'],
    'vm': [run_vm, '[-options] class [args...]'],
    'ctw': [ctw, '[-vmoptions|noinline|nocomplex|full]'],
    'nodecostdump' : [_nodeCostDump, ''],
    'verify_jvmci_ci_versions': [verify_jvmci_ci_versions, ''],
    'java_base_unittest' : [java_base_unittest, 'Runs unittest on JDK9 java.base "only" module(s)'],
    'microbench': [microbench, ''],
    'javadoc': [javadoc, ''],
    'makegraaljdk': [makegraaljdk, '[options]'],
})

def mx_post_parse_cmd_line(opts):
    mx.add_ide_envvar('JVMCI_VERSION_CHECK')
    for dist in _suite.dists:
        dist.set_archiveparticipant(GraalArchiveParticipant(dist, isTest=dist.name.endswith('_TEST')))
    add_bootclasspath_append(mx.distribution('sdk:GRAAL_SDK'))
    add_bootclasspath_append(mx.distribution('truffle:TRUFFLE_API'))
    global _vm_prefix
    _vm_prefix = opts.vm_prefix
