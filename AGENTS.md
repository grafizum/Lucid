# Working on this repository

This repository is owned by **grafizum** (the company account) and edited from the
private **MatrixZockt** account. `main` is protected, and every change goes through a
pull request that grafizum approves.

## The flow

1. Branch off `main`: `git switch -c <fix|feat|docs|chore>/<topic>`.
2. Commit as MatrixZockt with a message that says **why** the change is made.
3. Push the branch: `git push -u origin <branch>`.
4. Open the pull request: `gh pr create --base main --head <branch> --title "…" --body "…"`.
   The body states the change, the reason, and what was actually checked — never a check
   that did not run.
5. Review as the owner: `gh auth switch --hostname github.com --user grafizum`, read the
   diff with `gh pr diff`, then `gh pr review <number> --approve`.
6. Merge as the owner: `gh pr merge <number> --squash --delete-branch`.
7. Switch back: `gh auth switch --hostname github.com --user MatrixZockt`.

## What the branch protection means

- A direct push to `main` is rejected: *changes must be made through a pull request*.
- One approving review is required, and a new push dismisses the previous approval.
- Merge commits are not allowed, so history stays linear (squash or rebase).
- Force pushes to `main` and deleting `main` are blocked.
- Admin enforcement is off, so grafizum can still push a hotfix directly — only when the
  owner explicitly asks for one.

## Rules

- Do not disable or bypass branch protection to land a change.
- Do not approve unverified work; if a change is untested, open the PR and say so.
- One topic per pull request.
- Never change visibility, collaborators, secrets or repository ownership on your own.
