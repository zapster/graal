local CompilerCommonBuild = (import "common.libsonnet").CompilerCommonBuild;

{
  #
  local MxBenchCmd = {
      results_file: error "no results file",
      outer:: error "no outer config available",
      mx_cmd:: ["mx", "--kill-with-sigquit", "benchmark", "--results-file", self.results_file, "--machine-name", self.outer.machine_name, self.outer.bench_suite],
      suite_args:: ["-Xmx" + self.outer.max_heap_size, "-Xms" + self.outer.init_heap_size, "-XX:+PrintConcurrentLocks", "--jvm-config=" + self.outer.jvm_config, "--jvm=" + self.outer.jvm],
      benchmark_args:: [],
      cmd:: self.mx_cmd + ["--"] + self.suite_args + ["--"] + self.benchmark_args,
  },
  MxBenchmarkBuild:: CompilerCommonBuild {
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
  },
}
