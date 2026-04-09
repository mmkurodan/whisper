#!/bin/bash
set -e

git add .
git commit -m "update"
git push

echo "=== Changes pushed and GitHub Actions triggered ==="
