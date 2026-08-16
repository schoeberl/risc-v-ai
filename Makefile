.PHONY: rtl ppa-sky130

RTL_DIR := generated
LIBRELANE_ROOT ?= $(HOME)/librelane
SKY130_PDK_ROOT ?= $(HOME)/.ciel
PPA_RUN_TAG ?= 100mhz

rtl:
	sbt "runMain riscvai.Elaborate --target-dir $(RTL_DIR)"

ppa-sky130: rtl
	nix-shell $(LIBRELANE_ROOT)/shell.nix --run \
	  'librelane --pdk-root $(SKY130_PDK_ROOT) --run-tag $(PPA_RUN_TAG) ppa/librelane/config.yaml'
