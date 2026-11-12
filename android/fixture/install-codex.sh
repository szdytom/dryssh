#!/bin/sh
set -eu
version=0.167.0
checksum=9d64836106d5b374e90ee4454c7ebcde7ca7106558bd7e41a34b9f26ebda251a
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
curl -fL "https://github.com/openai/codex/releases/download/rust-v$version/codex-x86_64-unknown-linux-musl.tar.gz" -o "$temporary/codex.tar.gz"
printf '%s  %s\n' "$checksum" "$temporary/codex.tar.gz" | sha256sum -c -
tar -xzf "$temporary/codex.tar.gz" -C "$temporary"
podman cp "$temporary/codex-x86_64-unknown-linux-musl" dryssh-fixture:/usr/local/bin/codex
podman exec dryssh-fixture chmod +x /usr/local/bin/codex
podman exec dryssh-fixture codex --version
