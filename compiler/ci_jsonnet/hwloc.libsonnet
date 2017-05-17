
{
  HwLoc:: {
    num_nodes:: error "number of nodes not specified",
    nodes:: std.range(0, self.num_nodes - 1),
    packages+: {
      hwloc: ">=1.9",
    },
    bench_cmd_template+: {
        node:: error "no node",
        results_file: "node%d-results-file.json" % [self.node],
        outfile:: "${LOGDIR}/node%s-output.log" % self.node,
        cmd_prefix:: ["hwloc-bind", "--cpubind", "node:" + self.node, "--membind", "node:" + self.node, "--"],
        cmd_suffix:: ["|", "tee", "-a", self.outfile],
        mx_cmd+: ["--machine-node", "%s" % [self.node]],
        cmd: self.cmd_prefix + super.cmd + self.cmd_suffix,
      },
    run:
      [["export", "LOGDIR=${PWD}"]] +
      [self.bench_cmd_factory({ node: node }) + ["&"] for node in self.nodes] +
      [["wait"]],
    bench_results_files:: [
        "node%s-results-file.json" % [node] for node in self.nodes
      ],
    logs+: [
      "*output.log"
      ],
  },
}
