FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
        binutils-riscv64-unknown-elf \
        build-essential \
        gcc-riscv64-unknown-elf \
        make \
        python3 \
        python3-venv \
        verilator \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
