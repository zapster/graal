#!/bin/bash
set -i
set -e
BASE=$(dirname $0)
jsonnet $BASE/hocon_bridge.jsonnet > $BASE/hocon_bridge.json
