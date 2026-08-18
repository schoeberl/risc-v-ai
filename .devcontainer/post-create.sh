#!/usr/bin/env bash
set -euo pipefail

git submodule update --init --recursive
sbt update

printf 'Codespace ready: run "sbt test" or "make coremark".\n'
