#
# ----------------------------------------------------------------------------------------------------

# Copyright (c) 2007, 2017, Oracle and/or its affiliates. All rights reserved.
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

from __future__ import print_function

import os
import re
import tarfile
import zipfile
import tempfile
from contextlib import contextmanager
from distutils.dir_util import mkpath, copy_tree, remove_tree # pylint: disable=no-name-in-module
from os.path import join, exists, basename, dirname, islink
# { GR-8964
from shutil import copy2
# from time import strftime, gmtime
import time
# } GR-8964
import functools
import collections

import mx
import mx_compiler
import mx_gate
import mx_unittest
import mx_urlrewrites
from mx_compiler import GraalArchiveParticipant
from mx_compiler import run_java
from mx_gate import Task
from mx_substratevm_benchmark import run_js, host_vm_tuple, output_processors, rule_snippets # pylint: disable=unused-import
from mx_unittest import _run_tests, _VMLauncher

JVM_COMPILER_THREADS = 2 if mx.cpu_count() <= 4 else 4

GRAAL_COMPILER_FLAGS = ['-XX:-UseJVMCIClassLoader', '-XX:+UseJVMCICompiler', '-Dgraal.CompileGraalWithC1Only=false', '-XX:CICompilerCount=' + str(JVM_COMPILER_THREADS),
                        '-Dtruffle.TrustAllTruffleRuntimeProviders=true', # GR-7046
                        '-Dgraal.VerifyGraalGraphs=false', '-Dgraal.VerifyGraalGraphEdges=false', '-Dgraal.VerifyGraalPhasesSize=false', '-Dgraal.VerifyPhases=false']
IMAGE_ASSERTION_FLAGS = ['-H:+VerifyGraalGraphs', '-H:+VerifyGraalGraphEdges', '-H:+VerifyPhases']
suite = mx.suite('substratevm')
svmSuites = [suite]

orig_command_build = mx.command_function('build')

allow_native_image_build = True

def build(args, vm=None):
    if any([opt in args for opt in ['-h', '--help']]):
        orig_command_build(args, vm)

    mx.log('build: Checking SubstrateVM requirements for building ...')

    if not _host_os_supported():
        mx.abort('build: SubstrateVM can be built only on Darwin and Linux platforms')

    global allow_native_image_build
    if '--warning-as-error' in args and '--force-javac' not in args:
        # --warning-as-error with ecj is buggy (GR-3969)
        allow_native_image_build = False
    else:
        allow_native_image_build = True

    orig_command_build(args, vm)

def _host_os_supported():
    return mx.get_os() == 'linux' or mx.get_os() == 'darwin'

def _unittest_config_participant(config):
    vmArgs, mainClass, mainClassArgs = config
    # Run the VM in a mode where application/test classes can
    # access JVMCI loaded classes.
    vmArgs = GRAAL_COMPILER_FLAGS + vmArgs
    return (vmArgs, mainClass, mainClassArgs)

mx_unittest.add_config_participant(_unittest_config_participant)

def classpath(args):
    if not args:
        return [] # safeguard against mx.classpath(None) behaviour
    return mx.classpath(args, jdk=mx_compiler.jdk)

def clibrary_paths():
    return (join(suite.dir, 'clibraries') for suite in svmSuites)

def platform_subdir():
    return mx.get_os() + "-" + mx.get_arch()

def clibrary_libpath():
    return ','.join(join(path, platform_subdir()) for path in clibrary_paths())

def svm_suite():
    return svmSuites[-1]

def svmbuild_dir(suite=None):
    if not suite:
        suite = svm_suite()
    return join(suite.dir, 'svmbuild')

def suite_native_image_root(suite=None):
    if not suite:
        suite = svm_suite()
    return join(svmbuild_dir(suite), 'native-image-root')

def remove_existing_symlink(target_path):
    if islink(target_path):
        os.remove(target_path)
    return target_path

def relsymlink(target_path, dest_path):
    os.symlink(os.path.relpath(target_path, dirname(dest_path)), dest_path)

def native_image_layout(dists, subdir, native_image_root, debug_gr_8964=False):
    if not dists:
        return
    dest_path = join(native_image_root, subdir)
    # Cleanup leftovers from previous call
    if exists(dest_path):
        if debug_gr_8964:
            mx.log('[mx_substratevm.native_image_layout: remove_tree: ' + dest_path + ']')
        remove_tree(dest_path)
    mkpath(dest_path)
    # Create symlinks to conform with native-image directory layout scheme
    # GR-8964: Copy the jar instead of symlinking to it.
    def symlink_jar(jar_path):
        if debug_gr_8964:
            dest_jar = join(dest_path, basename(jar_path))
            if debug_gr_8964:
                mx.log('[mx_substratevm.native_image_layout.symlink_jar: copy2' + \
                    '\n  src: ' + jar_path + \
                    '\n  dst: ' + dest_jar)
            copy2(jar_path, dest_jar)
            dest_stat = os.stat(dest_jar)
            if debug_gr_8964:
                mx.log('      ' + \
                    ' .st_mode: ' + oct(dest_stat.st_mode) + \
                    ' .st_mtime: ' + time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(dest_stat.st_mtime)) + ']')
        else:
            relsymlink(jar_path, join(dest_path, basename(jar_path)))
    for dist in dists:
        mx.logv('Add ' + type(dist).__name__ + ' '  + str(dist) + ' to ' + dest_path)
        symlink_jar(dist.path)
        if not dist.isLibrary():
            symlink_jar(dist.sourcesPath)

def native_image_extract(dists, subdir, native_image_root):
    target_dir = join(native_image_root, subdir)
    for distribution in dists:
        mx.logv('Add dist {} to {}'.format(distribution, target_dir))
        if distribution.path.endswith('tar'):
            compressedFile = tarfile.open(distribution.path, 'r:')
        elif distribution.path.endswith('jar') or distribution.path.endswith('zip'):
            compressedFile = zipfile.ZipFile(distribution.path)
        else:
            raise mx.abort('Unsupported compressed file ' + distribution.path)
        compressedFile.extractall(target_dir)

def native_image_option_properties(option_kind, option_flag, native_image_root):
    target_dir = join(native_image_root, option_kind, option_flag)
    target_path = remove_existing_symlink(join(target_dir, 'native-image.properties'))

    option_properties = None
    for svm_suite in svmSuites:
        candidate = join(svm_suite.mxDir, option_kind + '-' + option_flag + '.properties')
        if exists(candidate):
            option_properties = candidate

    if option_properties:
        mx.logv('Add symlink to ' + str(option_properties))
        mkpath(target_dir)
        relsymlink(option_properties, target_path)

flag_suitename_map = collections.OrderedDict([
    ('llvm', ('sulong', ['SULONG', 'SULONG_LAUNCHER'], ['SULONG_LIBS', 'SULONG_DOC'])),
    ('js', ('graal-js', ['GRAALJS', 'TREGEX', 'GRAALJS_LAUNCHER', 'ICU4J'], ['ICU4J-DIST'], 'js')),
    ('ruby', ('truffleruby', ['TRUFFLERUBY', 'TRUFFLERUBY-LAUNCHER', 'TRUFFLERUBY-SHARED', 'TRUFFLERUBY-ANNOTATIONS'], ['TRUFFLERUBY-ZIP'])),
    ('python', ('graalpython', ['GRAALPYTHON', 'GRAALPYTHON-LAUNCHER', 'GRAALPYTHON-ENV'], ['GRAALPYTHON-ZIP']))
])

class ToolDescriptor:
    def __init__(self, image_deps=None, builder_deps=None, native_deps=None):
        """
        By adding a new ToolDescriptor entry in the tools_map a new --tool:<keyname>
        option is made available to native-image and also makes the tool available as
        tool:<keyname> in a native-image properties file Requires statement.  The tool is
        represented in the <native_image_root>/tools/<keyname> directory. If a
        corresponding tools-<keyname>.properties file exists in mx.substratevm it will get
        symlinked as <native_image_root>/tools/<keyname>/native-image.properties so that
        native-image will use these options whenever the tool is requested. Image_deps and
        builder_deps (see below) are also represented as symlinks to JAR files in
        <native_image_root>/tools/<keyname> (<native_image_root>/tools/<keyname>/builder).

        :param image_deps: list dependencies that get added to the image-cp (the classpath
        of the application you want to compile into an image) when using the tool.
        :param builder_deps: list dependencies that get added to the image builder-cp when
        using the tool. Builder-cp adds to the classpath that contains the image-generator
        itself. This might be necessary, e.g. when custom substitutions are needed to be
        able to compile classes on the image-cp. Another possible reason is when the image
        builder needs to prepare things prior to image building and doing so needs
        additional classes (see junit tool).
        :param native_deps: list native dependencies that should be extracted to
        <native_image_root>/tools/<keyname>.
        """
        self.image_deps = image_deps if image_deps else []
        self.builder_deps = builder_deps if builder_deps else []
        self.native_deps = native_deps if native_deps else []

tools_map = {
    'truffle' : ToolDescriptor(builder_deps=['truffle:TRUFFLE_NFI'], native_deps=['truffle:TRUFFLE_NFI_NATIVE']),
    'native-image' : ToolDescriptor(image_deps=['substratevm:SVM_DRIVER']),
    'junit' : ToolDescriptor(builder_deps=['mx:JUNIT_TOOL', 'JUNIT', 'HAMCREST']),
    'nfi' : ToolDescriptor(), # just an alias for truffle (to be removed soon)
    'chromeinspector' : ToolDescriptor(image_deps=['tools:CHROMEINSPECTOR']),
    'profiler' : ToolDescriptor(image_deps=['tools:TRUFFLE_PROFILER']),
}

def native_image_path(native_image_root):
    native_image_name = 'native-image'
    native_image_dir = join(native_image_root, platform_subdir(), 'bin')
    return join(native_image_dir, native_image_name)

def remove_option_prefix(text, prefix):
    if text.lower().startswith(prefix.lower()):
        return True, text[len(prefix):]
    return False, text

def extract_target_name(arg, kind):
    target_name, target_value = None, None
    is_kind, option_tail = remove_option_prefix(arg, '--' + kind + ':')
    if is_kind:
        target_name, _, target_value = option_tail.partition('=')
    return target_name, target_value

def native_image_extract_dependencies(args):
    deps = []
    for arg in args:
        tool_name = extract_target_name(arg, 'tool')[0]
        if tool_name in tools_map:
            tool_descriptor = tools_map[tool_name]
            deps += tool_descriptor.builder_deps
            deps += tool_descriptor.image_deps
            deps += tool_descriptor.native_deps
        language_flag = extract_target_name(arg, 'language')[0]
        if language_flag in flag_suitename_map:
            language_entry = flag_suitename_map[language_flag]
            language_suite_name = language_entry[0]
            language_deps = language_entry[1]
            deps += [language_suite_name + ':' + dep for dep in language_deps]
            language_native_deps = language_entry[2]
            deps += [language_suite_name + ':' + dep for dep in language_native_deps]
    return deps

def native_image_symlink_path(native_image_root, platform_specific=True):
    symlink_path = join(svm_suite().dir, basename(native_image_path(native_image_root)))
    if platform_specific:
        symlink_path += '-' + platform_subdir()
    return symlink_path

def bootstrap_native_image(native_image_root, svmDistribution, graalDistribution, librarySupportDistribution):
    if not allow_native_image_build:
        mx.logv('Detected building with ejc + --warning-as-error -> suppress bootstrap_native_image')
        return

    bootstrap_command = list(GRAAL_COMPILER_FLAGS)
    bootstrap_command += locale_US_args()
    bootstrap_command += substratevm_version_args()
    bootstrap_command += ['-Dgraalvm.version=dev']

    builder_classpath = classpath(svmDistribution)
    imagecp_classpath = classpath(svmDistribution + ['substratevm:SVM_DRIVER'])
    bootstrap_command += [
        '-cp', builder_classpath,
        'com.oracle.svm.hosted.NativeImageGeneratorRunner',
        '-imagecp', imagecp_classpath,
        '-H:CLibraryPath=' + clibrary_libpath()
    ]

    bootstrap_command += [
        '-H:Path=' + dirname(native_image_path(native_image_root)),
        '-H:Class=com.oracle.svm.driver.NativeImage',
        '-H:Name=' + basename(native_image_path(native_image_root)),
        '-H:-ParseRuntimeOptions'
    ]

    if mx._opts.strip_jars:
        bootstrap_command += ['-H:-VerifyNamingConventions']

    run_java(bootstrap_command)
    mx.logv('Built ' + native_image_path(native_image_root))

    def names_to_dists(dist_names):
        return [mx.dependency(dist_name) for dist_name in dist_names]

    def native_image_layout_dists(subdir, dist_names):
        native_image_layout(names_to_dists(dist_names), subdir, native_image_root)

    def native_image_extract_dists(subdir, dist_names):
        native_image_extract(names_to_dists(dist_names), subdir, native_image_root)

    # Create native-image layout for sdk parts
    native_image_layout_dists(join('lib', 'boot'), ['sdk:GRAAL_SDK'])
    native_image_layout_dists(join('lib', 'graalvm'), ['sdk:LAUNCHER_COMMON'])

    # Create native-image layout for compiler & jvmci parts
    native_image_layout_dists(join('lib', 'jvmci'), graalDistribution)
    jdk_config = mx.get_jdk()
    jvmci_path = join(jdk_config.home, 'jre', 'lib', 'jvmci')
    for symlink_name in os.listdir(jvmci_path):
        relsymlink(join(jvmci_path, symlink_name), join(native_image_root, 'lib', 'jvmci', symlink_name))

    # Create native-image layout for truffle parts
    native_image_layout_dists(join('lib', 'truffle'), ['truffle:TRUFFLE_API'])

    # Create native-image layout for tools parts
    for tool_name in tools_map:
        tool_descriptor = tools_map[tool_name]
        native_image_layout_dists(join('tools', tool_name, 'builder'), tool_descriptor.builder_deps)
        native_image_layout_dists(join('tools', tool_name), tool_descriptor.image_deps)
        native_image_extract_dists(join('tools', tool_name), tool_descriptor.native_deps)
        native_image_option_properties('tools', tool_name, native_image_root)

    # Create native-image layout for svm parts
    svm_subdir = join('lib', 'svm')
    native_image_layout_dists(svm_subdir, librarySupportDistribution)
    native_image_layout_dists(join(svm_subdir, 'builder'), svmDistribution + ['substratevm:POINTSTO', 'substratevm:OBJECTFILE'])
    for clibrary_path in clibrary_paths():
        copy_tree(clibrary_path, join(native_image_root, join(svm_subdir, 'clibraries')))

    # Create platform-specific symlink to native-image in svm_suite().dir
    symlink_path = remove_existing_symlink(native_image_symlink_path(native_image_root))
    relsymlink(native_image_path(native_image_root), symlink_path)
    # Finally create default symlink to native-image in svm_suite().dir
    symlink_path = remove_existing_symlink(native_image_symlink_path(native_image_root, platform_specific=False))
    relsymlink(native_image_path(native_image_root), symlink_path)

class BootstrapNativeImage(mx.Project):
    def __init__(self, suite, name, deps, workingSets, theLicense=None, **kwargs):
        super(BootstrapNativeImage, self).__init__(suite, name, "", [], deps, workingSets, suite.dir, theLicense)
        self.native_image_root = suite_native_image_root(suite)
        self.buildDependencies = kwargs.pop('buildDependencies', [])
        self.graalDistribution = kwargs.pop('graal', [])
        self.svmDistribution = kwargs.pop('svm', [])
        self.buildDependencies += self.svmDistribution
        self.librarySupportDistribution = kwargs.pop('svmSupport', [])
        self.buildDependencies += self.librarySupportDistribution

    def getResults(self):
        return None

    def getBuildTask(self, args):
        return NativeImageBootstrapTask(args, self)

    def output_files(self):
        return [native_image_path(self.native_image_root)]

class NativeImageBootstrapTask(mx.ProjectBuildTask):
    def __init__(self, args, project):
        super(NativeImageBootstrapTask, self).__init__(args, min(8, mx.cpu_count()), project)
        self._newestOutput = None

    def _allow_bootstrapping(self):
        return self.subject.suite == svm_suite()

    def __str__(self):
        prefix = "Bootstrapping " if self._allow_bootstrapping() else "Skip bootstrapping "
        return prefix + self.subject.name

    def _shouldRebuildNativeImage(self):
        # Only bootstrap native-image for the most-specific svm_suite
        if not self._allow_bootstrapping():
            return False, 'Skip bootstrapping'

        # Only bootstrap if it doesn't already exist
        symlink_path = native_image_symlink_path(self.subject.native_image_root)
        if exists(symlink_path):
            mx.log('Suppressed rebuilding native-image (delete ' + basename(symlink_path) + ' to allow rebuilding).')
            return False, 'Already bootstrapped'

        return True, ''

    def build(self):
        shouldRebuild = self._shouldRebuildNativeImage()[0]
        if not shouldRebuild:
            return

        bootstrap_native_image(
            native_image_root=self.subject.native_image_root,
            svmDistribution=self.subject.svmDistribution,
            graalDistribution=self.subject.graalDistribution,
            librarySupportDistribution=self.subject.librarySupportDistribution
        )

    def clean(self, forBuild=False):
        if forBuild:
            return

        native_image_root = self.subject.native_image_root
        if exists(native_image_root):
            remove_tree(native_image_root)
        remove_existing_symlink(native_image_symlink_path(native_image_root))
        remove_existing_symlink(native_image_symlink_path(native_image_root, platform_specific=False))

    def needsBuild(self, newestInput):
        shouldRebuild, reason = self._shouldRebuildNativeImage()
        if not shouldRebuild:
            return False, reason

        witness = self.newestOutput()
        if not self._newestOutput or witness.isNewerThan(self._newestOutput):
            self._newestOutput = witness
        if not witness.exists():
            return True, witness.path + ' does not exist'
        if newestInput and witness.isOlderThan(newestInput):
            return True, '{} is older than {}'.format(witness, newestInput)
        return False, 'output is up to date'

    def newestOutput(self):
        return mx.TimeStampFile(native_image_path(self.subject.native_image_root))

def truffle_language_ensure(language_flag, version=None, native_image_root=None, early_exit=False, extract=True, debug_gr_8964=False):
    """
    Ensures that we have a valid suite for the given language_flag, by downloading a binary if necessary
    and providing the suite distribution artifacts in the native-image directory hierachy (via symlinks).
    :param language_flag: native-image language_flag whose truffle-language we want to use
    :param version: if not specified and no TRUFFLE_<LANG>_VERSION set latest binary deployed master revision gets downloaded
    :param native_image_root: the native_image_root directory where the the artifacts get installed to
    :return: language suite for the given language_flag
    """
    if not native_image_root:
        native_image_root = suite_native_image_root()

    version_env_var = 'TRUFFLE_' + language_flag.upper() + '_VERSION'
    if not version and os.environ.has_key(version_env_var):
        version = os.environ[version_env_var]

    if language_flag not in flag_suitename_map:
        mx.abort('No truffle-language uses language_flag \'' + language_flag + '\'')

    language_dir = join('languages', language_flag)
    if early_exit and exists(join(native_image_root, language_dir)):
        mx.logv('Early exit mode: Language subdir \'' + language_flag + '\' exists. Skip suite.import_suite.')
        return None

    language_entry = flag_suitename_map[language_flag]

    language_suite_name = language_entry[0]
    language_repo_name = language_entry[3] if len(language_entry) > 3 else None

    urlinfos = [
        mx.SuiteImportURLInfo(mx_urlrewrites.rewriteurl('https://curio.ssw.jku.at/nexus/content/repositories/snapshots'),
                              'binary',
                              mx.vc_system('binary'))
    ]

    failure_warning = None
    if not version and not mx.suite(language_suite_name, fatalIfMissing=False):
        # If no specific version requested use binary import of last recently deployed master version
        repo_suite_name = language_repo_name if language_repo_name else language_suite_name
        repo_url = mx_urlrewrites.rewriteurl('https://github.com/graalvm/{0}.git'.format(repo_suite_name))
        version = mx.SuiteImport.resolve_git_branchref(repo_url, 'binary', abortOnError=False)
        if not version:
            failure_warning = 'Resolving \'binary\' against ' + repo_url + ' failed'

    language_suite = suite.import_suite(
        language_suite_name,
        version=version,
        urlinfos=urlinfos,
        kind=None,
        in_subdir=bool(language_repo_name)
    )

    if not language_suite:
        if failure_warning:
            mx.warn(failure_warning)
        mx.abort('Binary suite not found and no local copy of ' + language_suite_name + ' available.')

    if not extract:
        if not exists(join(native_image_root, language_dir)):
            mx.abort('Language subdir \'' + language_flag + '\' should already exist with extract=False')
        return language_suite

    language_suite_depnames = language_entry[1]
    language_deps = language_suite.dists + language_suite.libs
    language_deps = [dep for dep in language_deps if dep.name in language_suite_depnames]
    native_image_layout(language_deps, language_dir, native_image_root, debug_gr_8964=debug_gr_8964)

    language_suite_nativedistnames = language_entry[2]
    language_nativedists = [dist for dist in language_suite.dists if dist.name in language_suite_nativedistnames]
    native_image_extract(language_nativedists, language_dir, native_image_root)

    option_properties = join(language_suite.mxDir, 'native-image.properties')
    target_path = remove_existing_symlink(join(native_image_root, language_dir, 'native-image.properties'))
    if exists(option_properties):
        mx.logv('Add symlink to ' + str(option_properties))
        relsymlink(option_properties, target_path)
    else:
        native_image_option_properties('languages', language_flag, native_image_root)
    return language_suite

def locale_US_args():
    return ['-Duser.country=US', '-Duser.language=en']

def substratevm_version_args():
    return ['-Dsubstratevm.version=' + ','.join(s.version() + ':' + s.name for s in svmSuites)]

class Tags(set):
    def __getattr__(self, name):
        if name in self:
            return name
        raise AttributeError

GraalTags = Tags([
    'helloworld',
    'js',
    'ruby',
    'sulong',
    'python',
])

@contextmanager
def native_image_context(common_args=None, hosted_assertions=True, debug_gr_8964=False):
    common_args = [] if common_args is None else common_args
    base_args = ['-H:Path=' + svmbuild_dir()]
    if debug_gr_8964:
        base_args += ['-Ddebug_gr_8964=true']
    if mx.get_opts().verbose:
        base_args += ['--verbose']
    if mx.get_opts().very_verbose:
        base_args += ['--verbose-server']
    if hosted_assertions:
        base_args += native_image_context.hosted_assertions
    native_image_cmd = native_image_path(suite_native_image_root())
    def query_native_image(all_args, option):
        out = mx.LinesOutputCapture()
        mx.run([native_image_cmd, '--dry-run'] + all_args, out=out)
        for line in out.lines:
            _, sep, after = line.partition(option)
            if sep:
                return after.split(' ')[0].rstrip()
        return None
    def native_image_func(args, debug_gr_8964=False):
        all_args = base_args + common_args + args
        path = query_native_image(all_args, '-H:Path=')
        name = query_native_image(all_args, '-H:Name=')
        image = join(path, name)
        mx.run([native_image_cmd] + all_args)
        return image
    try:
        mx.run([native_image_cmd, '--server-wipe'])
        yield native_image_func
    finally:
        mx.run([native_image_cmd, '--server-shutdown'])

native_image_context.hosted_assertions = ['-J-ea', '-J-esa']

def svm_gate_body(args, tasks):
    # Debug GR-8964 on Darwin gates
    debug_gr_8964 = (mx.get_os() == 'darwin')
    with native_image_context(IMAGE_ASSERTION_FLAGS, debug_gr_8964=debug_gr_8964) as native_image:
        with Task('image demos', tasks, tags=[GraalTags.helloworld]) as t:
            if t:
                helloworld(native_image)
                cinterfacetutorial(native_image)

        with Task('JavaScript', tasks, tags=[GraalTags.js]) as t:
            if t:
                js = build_js(native_image, debug_gr_8964=debug_gr_8964)
                test_run([js, '-e', 'print("hello:" + Array.from(new Array(10), (x,i) => i*i ).join("|"))'], 'hello:0|1|4|9|16|25|36|49|64|81\n')
                test_js(js, [('octane-richards', 1000, 100, 300)])

        with Task('Ruby', tasks, tags=[GraalTags.ruby]) as t:
            if t:
                ruby = build_ruby(native_image, debug_gr_8964=debug_gr_8964)
                test_ruby([ruby, 'release'])

        with Task('Python', tasks, tags=[GraalTags.python]) as t:
            if t:
                python = build_python(native_image, debug_gr_8964=debug_gr_8964)
                test_python_smoke([python])

        gate_sulong(native_image, tasks)

def native_junit(native_image, unittest_args, build_args=None, run_args=None):
    build_args = build_args if not None else []
    run_args = run_args if not None else []
    junit_native_dir = join(svmbuild_dir(), platform_subdir(), 'junit')
    mkpath(junit_native_dir)
    junit_tmp_dir = tempfile.mkdtemp(dir=junit_native_dir)
    try:
        unittest_deps = []
        def dummy_harness(test_deps, vm_launcher, vm_args):
            unittest_deps.extend(test_deps)
        unittest_file = join(junit_tmp_dir, 'svmjunit.tests')
        _run_tests(unittest_args, dummy_harness, _VMLauncher('dummy_launcher', None, mx_compiler.jdk), ['@Test', '@Parameters'], unittest_file, None, None, None, None)
        extra_image_args = mx.get_runtime_jvm_args(unittest_deps, jdk=mx_compiler.jdk)
        native_image(build_args + extra_image_args + ['--tool:junit=' + unittest_file, '-H:Path=' + junit_tmp_dir])
        unittest_image = join(junit_tmp_dir, 'svmjunit')
        mx.run([unittest_image] + run_args)
    finally:
        remove_tree(junit_tmp_dir)

def gate_sulong(native_image, tasks):

    # Debug GR-8964 on Darwin gates
    debug_gr_8964 = (mx.get_os() == 'darwin')

    with Task('Run SulongSuite tests with SVM image', tasks, tags=[GraalTags.sulong]) as t:
        if t:
            sulong = truffle_language_ensure('llvm', debug_gr_8964=debug_gr_8964)
            lli = native_image(['--language:llvm'])
            sulong.extensions.testLLVMImage(lli, unittestArgs=['--enable-timing'])

    with Task('Run Sulong interop tests with SVM image', tasks, tags=[GraalTags.sulong]) as t:
        if t:
            sulong = truffle_language_ensure('llvm', debug_gr_8964=debug_gr_8964)
            sulong.extensions.runLLVMUnittests(functools.partial(native_junit, native_image, build_args=['--language:llvm']))


def js_image_test(binary, bench_location, name, warmup_iterations, iterations, timeout=None, bin_args=None):
    bin_args = bin_args if bin_args is not None else []
    jsruncmd = [binary] + bin_args + [join(bench_location, 'harness.js'), '--', join(bench_location, name + '.js'),
                                      '--', '--warmup-iterations=' + str(warmup_iterations),
                                      '--iterations=' + str(iterations)]
    mx.log(' '.join(jsruncmd))

    passing = []

    stdoutdata = []
    def stdout_collector(x):
        stdoutdata.append(x)
        mx.log(x.rstrip())
    stderrdata = []
    def stderr_collector(x):
        stderrdata.append(x)
        mx.warn(x.rstrip())

    returncode = mx.run(jsruncmd, cwd=bench_location, out=stdout_collector, err=stderr_collector, nonZeroIsFatal=False, timeout=timeout)

    if returncode == mx.ERROR_TIMEOUT:
        print('INFO: TIMEOUT (> %d): %s' % (timeout, name))
    elif returncode >= 0:
        matches = 0
        for line in stdoutdata:
            if re.match(r'^\S+: *\d+(\.\d+)?\s*$', line):
                matches += 1
        if matches > 0:
            passing = stdoutdata

    if not passing:
        mx.abort('JS benchmark ' + name + ' failed')

def build_js(native_image, debug_gr_8964=False):
    truffle_language_ensure('js', debug_gr_8964=debug_gr_8964)
    return native_image(['--language:js', '--tool:chromeinspector'], debug_gr_8964=debug_gr_8964)

def test_js(js, benchmarks, bin_args=None):
    bench_location = join(suite.dir, '..', '..', 'js-benchmarks')
    for benchmark_name, warmup_iterations, iterations, timeout in benchmarks:
        js_image_test(js, bench_location, benchmark_name, warmup_iterations, iterations, timeout, bin_args=bin_args)

def test_run(cmds, expected_stdout, timeout=10):
    stdoutdata = []
    def stdout_collector(x):
        stdoutdata.append(x)
        mx.log(x.rstrip())
    stderrdata = []
    def stderr_collector(x):
        stderrdata.append(x)
        mx.warn(x.rstrip())
    returncode = mx.run(cmds, out=stdout_collector, err=stderr_collector, nonZeroIsFatal=False, timeout=timeout)
    if ''.join(stdoutdata) != expected_stdout:
        mx.abort('Error: stdout does not match expected_stdout')
    return (returncode, stdoutdata, stderrdata)

def build_python(native_image, debug_gr_8964=False):
    truffle_language_ensure('llvm', debug_gr_8964=debug_gr_8964) # python depends on sulong
    truffle_language_ensure('python', debug_gr_8964=debug_gr_8964)
    return native_image(['--language:python', '--tool:profiler', 'com.oracle.graal.python.shell.GraalPythonMain', 'python'])

def test_python_smoke(args):
    """
    Just a smoke test for now.
    """
    if len(args) != 1:
        mx.abort('mx svm_test_python <python_svm_image_path>')

    out = mx.OutputCapture()
    expected_output = "Hello from Python"
    with tempfile.NamedTemporaryFile() as f:
        f.write("print('%s')\n" % expected_output)
        f.flush()
        os.system("ls -l %s" % args[0])
        os.system("ls -l %s" % f.name)
        exitcode = mx.run([args[0], f.name], nonZeroIsFatal=False, out=out)
        if exitcode != 0:
            mx.abort("Python binary failed to execute: " + out.data)
        if out.data != expected_output + "\n":
            mx.abort("Python smoke test failed")
        mx.log("Python binary says: " + out.data)

def build_ruby(native_image, debug_gr_8964=False):
    truffle_language_ensure('llvm', debug_gr_8964=debug_gr_8964) # ruby depends on sulong
    truffle_language_ensure('ruby', debug_gr_8964=debug_gr_8964)

    # The Ruby image should be under its bin/ dir to find the Ruby home automatically and mimic distributions
    ruby_bin_dir = join(suite_native_image_root(), 'languages', 'ruby', 'bin')
    return native_image(['--language:ruby', '-H:Name=truffleruby', '-H:Path=' + ruby_bin_dir])

def test_ruby(args):
    if len(args) < 1 or len(args) > 2:
        mx.abort('mx svm_test_ruby <ruby_svm_image_path> [<debug_build>=release]')

    aot_bin = args[0]
    debug_build = args[1] if len(args) >= 2 else 'release'

    truffleruby_suite = truffle_language_ensure('ruby', extract=False)

    suite_dir = truffleruby_suite.dir
    distsToExtract = ['TRUFFLERUBY-ZIP', 'TRUFFLERUBY-SPECS']
    lib = join(suite_dir, 'lib')
    if not exists(lib):
        # Binary suite, extract the distributions
        for dist_name in distsToExtract:
            mx.log('Extract distribution {} to {}'.format(dist_name, suite_dir))
            dist = mx.distribution(dist_name)
            with tarfile.open(dist.path, 'r:') as archive:
                archive.extractall(suite_dir)

    mx.command_function('ruby_testdownstream_aot')([aot_bin, 'spec', debug_build])

mx_gate.add_gate_runner(suite, svm_gate_body)

def cinterfacetutorial(native_image, args=None):
    """Build and run the tutorial for the C interface"""

    args = [] if args is None else args
    tutorial_proj = mx.dependency('com.oracle.svm.tutorial')
    cSourceDir = join(tutorial_proj.dir, 'native')
    buildDir = join(svmbuild_dir(), tutorial_proj.name, 'build')

    # clean / create output directory
    if exists(buildDir):
        remove_tree(buildDir)
    mkpath(buildDir)

    # Build the shared library from Java code
    native_image(['-shared', '-H:Path=' + buildDir, '-H:Name=libcinterfacetutorial',
                  '-H:CLibraryPath=' + tutorial_proj.dir, '-cp', tutorial_proj.output_dir()] + args)

    # Build the C executable
    mx.run(['cc', '-g', join(cSourceDir, 'cinterfacetutorial.c'),
            '-I' + buildDir,
            '-L' + buildDir, '-lcinterfacetutorial',
            '-ldl', '-Wl,-rpath,' + buildDir,
            '-o', join(buildDir, 'cinterfacetutorial')])

    # Start the C executable
    mx.run([buildDir + '/cinterfacetutorial'])

def helloworld(native_image, args=None):
    args = [] if args is None else args

    helloPath = svmbuild_dir()
    mkpath(helloPath)

    # Build an image for the javac compiler, so that we test and gate-check javac all the time.
    # Dynamic class loading code is reachable (used by the annotation processor), so -H:+ReportUnsupportedElementsAtRuntime is a necessary option
    native_image(["-H:Path=" + helloPath, '-cp', mx_compiler.jdk.toolsjar, "com.sun.tools.javac.Main", "javac",
           "-H:+ReportUnsupportedElementsAtRuntime", "-H:IncludeResourceBundles=com.sun.tools.javac.resources.compiler,com.sun.tools.javac.resources.javac,com.sun.tools.javac.resources.version"] + args)

    helloFile = join(helloPath, 'HelloWorld.java')
    output = 'Hello from Substrate VM'
    with open(helloFile, 'w') as fp:
        fp.write('public class HelloWorld { public static void main(String[] args) { System.out.println("' + output + '"); } }')

    # Run the image for javac. Annotation processing must be disabled because it requires dynamic class loading,
    # and we need to set the bootclasspath manually because our build directory does not contain any .jar files.
    mx.run([join(helloPath, 'javac'), "-proc:none", "-bootclasspath", join(mx_compiler.jdk.home, "jre", "lib", "rt.jar"), helloFile])

    native_image(["-H:Path=" + helloPath, '-cp', helloPath, 'HelloWorld'])

    expectedOutput = [output + '\n']
    actualOutput = []
    def _collector(x):
        actualOutput.append(x)
        mx.log(x)

    mx.run([join(helloPath, 'helloworld')], out=_collector)

    if actualOutput != expectedOutput:
        raise Exception('Wrong output: ' + str(actualOutput) + "  !=  " + str(expectedOutput))

orig_command_benchmark = mx.command_function('benchmark')
def benchmark(args):
    if '--jsvm=substratevm' in args:
        truffle_language_ensure('js')
    orig_command_benchmark(args)

def mx_post_parse_cmd_line(opts):
    for dist in suite.dists:
        if not dist.isTARDistribution():
            dist.set_archiveparticipant(GraalArchiveParticipant(dist, isTest=dist.name.endswith('_TEST')))

def native_image_context_run(func, func_args=None):
    func_args = [] if func_args is None else func_args
    with native_image_context() as native_image:
        func(native_image, func_args)

def fetch_languages(args, early_exit=True):
    if args:
        requested = collections.OrderedDict()
        for arg in args:
            language_flag, version_info = extract_target_name(arg, 'language')
            if language_flag:
                version = version_info.partition('version=')[2] if version_info else None
                requested[language_flag] = version
    else:
        requested = collections.OrderedDict((lang, None) for lang in flag_suitename_map)

    for language_flag in requested:
        version = requested[language_flag]
        truffle_language_ensure(language_flag, version, early_exit=early_exit)

mx.update_commands(suite, {
    'build': [build, ''],
    'helloworld' : [lambda args: native_image_context_run(helloworld, args), ''],
    'cinterfacetutorial' : [lambda args: native_image_context_run(cinterfacetutorial, args), ''],
    'fetch-languages': [lambda args: fetch_languages(args, early_exit=False), ''],
    'benchmark': [benchmark, '--vmargs [vmargs] --runargs [runargs] suite:benchname'],
})
