local common = import "common.libsonnet";
local CompilerCommonBuild = common.CompilerCommonBuild;
local target = common.Target;

{
  # The `MxBenchCmdTemplate` is a template for creating an instance of an `mx benchmark` command line.
  # It expects a `results_file` as a parameter.
  # Via the `build` entry it can access properties of its enclosing `MxBenchmarkBuild`.
  local MxBenchCmdTemplate = {
      results_file: error "no results file",
      build:: error "no build config available",
      # The mx benchmark argumetns, i.e., everything before the first `--`.
      mx_cmd:: ["mx", "--kill-with-sigquit", "benchmark", "--results-file", self.results_file, "--machine-name", self.build.machine_name, self.build.bench_suite],
      # The suite arguments, i.e., all flags after the first and before the second `--`.
      # This includes for instance flags passed to the virtual machine.
      suite_args:: ["-Xmx" + self.build.max_heap_size, "-Xms" + self.build.init_heap_size, "-XX:+PrintConcurrentLocks", "--jvm-config=" + self.build.jvm_config, "--jvm=" + self.build.jvm],
      # Flags passed to the benchmark harness, i.e., everything after the last `--`.
      benchmark_args:: [],
      cmd:: self.mx_cmd + ["--"] + self.suite_args + ["--"] + self.benchmark_args,
  },
  MxBenchmarkBuild:: CompilerCommonBuild + target.Bench {
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
    # reference to the top level of the build
    local build = self,
    # The `bench_cmd_template` is a template for creating an instance of an `mx benchmark` command line.
    # This template can be used by sub-classes to customize the benchmark command.
    # See `MxBenchCmdTemplate` for more details.
    bench_cmd_template:: MxBenchCmdTemplate {
        build: build,
    },
    # The `bench_cmd_factory()` used to create an instance of an `mx benchmark` command line using
    # `bench_cmd_template`. It expects a dictionary as a parameter containing all the arguments needed
    # by `bench_cmd_template`.
    bench_cmd_factory(args):: (self.bench_cmd_template + args).cmd,
    run: [
      self.bench_cmd_factory({
        results_file: build.bench_results_files[0],
      }),
    ],
    teardown: [["bench-uploader.py", "--url", self.bench_server_url, file] for file in self.bench_results_files],
    name: "bench-%s-%s-%s" % [self.jvm, self.jvm_config, self.bench_suite_name],
  },
}
