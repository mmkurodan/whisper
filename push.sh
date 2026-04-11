#!/bin/bash
set -e

git add .
git commit -m "update" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
git push

echo "=== Changes pushed and GitHub Actions triggered ==="
