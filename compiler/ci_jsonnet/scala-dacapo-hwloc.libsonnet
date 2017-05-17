local HwLoc = (import "hwloc.libsonnet").HwLoc;

{
  # Fixes shared resources issue with scala-actors in parallel mode.
  # Work arround by executing actors in serial after the parallel benchmarks
  # have finished.
  ScalaDacapoHwLoc:: HwLoc {
    # reference to the top level of the build
    local build = self,
    # exclude actors per default
    bench_suite: "%s:~actors" % build.bench_suite_name,
    run+:
      [self.bench_cmd_factory({
          node: node,
          # execute only actors
          bench_suite: "%s:actors" % build.bench_suite_name,
          # avoid overwritting parallel results files
          results_file: "serial-node%s-results-file.json" % node,
        })
      for node in self.nodes],
    bench_results_files+::[
        # add extra results files
        "serial-node%s-results-file.json" % [node] for node in self.nodes
      ],
  },
}
