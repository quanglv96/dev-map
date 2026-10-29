#!/bin/sh
# Builds 3 separate git repos from the sample sources, each with branches main and test
# (test is one commit ahead), so DevMap can pull a configured base branch.
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-$here/repos}
for svc in order-service payment-service notification-service; do
  dest="$out/$svc"
  rm -rf "$dest"
  mkdir -p "$dest"
  cp -R "$here/$svc/." "$dest/"
  git -C "$dest" init -q -b main
  git -C "$dest" config core.autocrlf false
  git -C "$dest" add -A
  git -C "$dest" -c user.name=devmap -c user.email=devmap@example.invalid commit -q -m "initial $svc"
  git -C "$dest" checkout -q -b test
  echo "# $svc" > "$dest/README.md"
  git -C "$dest" add README.md
  git -C "$dest" -c user.name=devmap -c user.email=devmap@example.invalid commit -q -m "test: add README"
  git -C "$dest" checkout -q main
  echo "created $dest"
done
