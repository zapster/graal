# java
local JavaDownloads = {
  java7: { name: "oraclejdk", version: "7", platformspecific: true },
  java8: { name: "labsjdk", version: "8u121-jvmci-0.26", platformspecific: true },
  java9: { name: "labsjdk", version: "9-20170429-102349", platformspecific: true },
  java9EA: { name: "labsjdk", version: "9-ea+168", platformspecific: true },
  java8Debug: { name: "labsjdk", version: "8u121-jvmci-0.26-fastdebug", platformspecific: true },
};
local Java8 = {
  downloads+: {
    EXTRA_JAVA_HOMES: {
      pathlist: [JavaDownloads.java7],
    },
    JAVA_HOME: JavaDownloads.java8,
  },
  name+: "-java8",
};
# operation system
local Linux = {
  packages+: {
    git: ">=1.8.3",
    mercurial: ">=2.2",
    "pip:astroid": "==1.1.0",
    "pip:pylint": "==1.1.0",
  },
  capabilities+: ["linux"],
  name+: "-linux",
};
# architecture mixins
local AMD64 = {
  capabilities+: ["amd64"],
  name+: "-amd64",
};
# capability mixins
local NoFrequencyScaling = {
  capabilities+: ["no_frequency_scaling"],
};
local Tmpfs10G = {
  capabilities+: ["tmpfs10g"],
};
local HwLoc = {
  num_nodes:: error "number of nodes not specified",
  nodes:: std.range(0, self.num_nodes - 1),
  packages+: {
    hwloc: ">=1.9",
  },
  cmd+: {
      node:: error "no node",
      results_file: "node%d-results-file.json" % [self.node],
      outfile:: "${LOGDIR}/node%s" % self.node,
      cmd_prefix:: ["hwloc-bind", "--cpubind", "node:" + self.node, "--membind", "node:" + self.node, "--"],
      cmd_suffix:: ["|", "tee", "-a", self.outfile],
      mx_cmd+: ["--machine-node", "%s" % [self.node]],
      cmd: self.cmd_prefix + super.cmd + self.cmd_suffix,
    },
  run:
    [["export", "LOGDIR=${PWD}"]] +
    [self.cmd { node: node }.cmd + ["&"] for node in self.nodes] +
    [["wait"]],
  bench_results_files:: [
      "node%s-results-file.json" % [node] for node in self.nodes
    ],
};
# machine mixins
local X52 = Linux + AMD64 {
  num_nodes:: 2,
  capabilities+: ["x52"],
  max_heap_size: "64g",
  init_heap_size: "64g",
  machine_name: "x52",
  name+: "-" + self.machine_name,
};
# build mixins
local Build = {
  name: error "name undefined",
  targets: [],
  capabilities: [],
  packages: {},
  downloads: {},
  environment: {},
  timelimit: error "timelimit undefined",
  setup: [],
  run: [],
  teardown: [],
  on_success: [],
  on_failure: [],
  verbose: false,
  logs: [],
  catch_files: [],
};
local GraalCommonBuild = Build {
  timelimit: "30:00",
  catch_files+: [
    "Graal diagnostic output saved in (?P<filename>.+\\.zip)",
  ],
};
local CompilerCommonBuild = GraalCommonBuild {
  # TODO make configureable!
  compiler_suite_root:: "./compiler",
  setup: [
    ["cd", self.compiler_suite_root],
  ],
};
local MxBenchCmd = {
    results_file: error "no results file",
    outer:: error "no outer config available",
    mx_cmd:: ["mx", "--kill-with-sigquit", "benchmark", "--results-file", self.results_file, "--machine-name", self.outer.machine_name, self.outer.bench_suite],
    suite_args:: ["-Xmx" + self.outer.max_heap_size, "-Xms" + self.outer.init_heap_size, "-XX:+PrintConcurrentLocks", "--jvm-config=" + self.outer.jvm_config, "--jvm=" + self.outer.jvm],
    benchmark_args:: [],
    cmd:: self.mx_cmd + ["--"] + self.suite_args + ["--"] + self.benchmark_args,
};
local MxBenchmarkBuild = CompilerCommonBuild {
  bench_suite:: error "no benchmark suite defined",
  bench_suite_name:: self.bench_suite,
  jvm_config:: error "no jvm_config defined",
  jvm:: error "no jvm defined",
  max_heap_size:: error "no max heap size",
  init_heap_size:: error "no initial heap size",
  machine_name:: error "no machine name",
  bench_server_url:: "${BENCH_SERVER_URL}",
  bench_results_files:: ["bench-results.json"],
  setup+: [
    ["mx", "build"],
  ],
  local build = self,
  cmd:: MxBenchCmd {
      outer: build,
    },
  run: [
    self.cmd {
      results_file: build.bench_results_files[0],
    }.cmd,
  ],
  teardown: [["bench-uploader.py", "--url", self.bench_server_url, file] for file in self.bench_results_files],
  name: "bench-%s-%s-%s" % [self.jvm, self.jvm_config, self.bench_suite_name],
};
# benchmarks
local Dacapo = {
  targets: ["bench", "post-merge"],
  bench_suite: "dacapo:*",
  bench_suite_name: "dacapo",
};
local DacapoTiming = {
  targets: ["bench", "daily"],
  bench_suite: "dacapo-timing:*",
  bench_suite_name: "dacapo-timing",
};
# host vm configs
local Server = {
  jvm: "server",
};
local Graal = Server {
  # make general
  jvm_config: "graal-core",
};
local TraceRA = Graal {
  jvm_config+: "-tracera",
};

{
  builds: [
    MxBenchmarkBuild + Java8 + vm_config + bench + platform
      for vm_config in [Graal, TraceRA]
        for bench in [Dacapo, DacapoTiming]
          for platform in [NoFrequencyScaling + Tmpfs10G + HwLoc + X52]
  ],
}
