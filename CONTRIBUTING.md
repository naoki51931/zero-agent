# Safe update workflow for Zero Agent

Concurrent edits to main can cause GitHub SHA conflicts. Never force-push main or blindly overwrite another change.

## Before each edit
1. Fetch the latest main branch and the exact target file.
2. Make the smallest possible change to that latest file; preserve unrelated changes.
3. Use the current file blob SHA when updating via GitHub Contents API.
4. If the API returns a 409/422 conflict, fetch main and the file again, reapply the intended change, and retry only after checking for overlapping edits.
5. For multi-file or long-running work, create a feature branch from the current main HEAD and open a pull request. Sync the branch with main before merging.
6. Run Android CI and merge only when checks pass.
7. Avoid multiple writers editing the same file at once. Never silently discard either side of a real content conflict.

## Local Git workflow
```bash
git status
git fetch origin
git switch main
git pull --ff-only origin main
git switch -c feature/short-description
# make changes, test and commit
git push -u origin feature/short-description
# open PR, update branch if needed, merge after CI
```

## Release
Android Actions builds on main are serialized by workflow concurrency. New pushes cancel obsolete runs. A successful main build updates the rolling latest-build release. This prevents concurrent release jobs but does not eliminate source merge conflicts.
