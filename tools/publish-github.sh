#!/usr/bin/env bash
# Publishes the project on GitHub and prints the repository URL for Aniyomi.
#
# Usage (token):
#   GITHUB_TOKEN=ghp_xxx  bash tools/publish-github.sh [repo-name] [owner]
#
# Usage (gh CLI already authenticated):
#   bash tools/publish-github.sh [repo-name]
#
# The script:
#   1. initialises/commits the working tree,
#   2. creates the repository on GitHub if it does not exist yet,
#   3. pushes main (+ the tag v1),
#   4. prints the raw URL of repo/index.min.json to paste in
#      Aniyomi > Parametres > Parcourir > Depots.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO="${1:-csbridge}"
OWNER="${2:-}"
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"

# ---------------------------------------------------------------- 1. commits
if [[ ! -d .git ]]; then
  git init -q
  git branch -M main
fi
git config user.name  >/dev/null 2>&1 || git config user.name  "csbridge"
git config user.email >/dev/null 2>&1 || git config user.email "csbridge@users.noreply.github.com"

git add -A
if git diff --cached --quiet; then
  echo "== nothing to commit"
else
  git commit -q -m "Cloudstream Bridge: extension Aniyomi pour les plugins Cloudstream

- charge les depots Cloudstream (repo.json + pluginLists)
- une source Aniyomi par site installe
- serveurs/extracteurs, sous-titres, reglages du plugin
- compatible Aniyomi (extensions-lib 17) et AniZen (API RxJava)
- pipeline de build sans Android SDK (tools/build-manual.sh)"
  echo "== commit created"
fi

# ------------------------------------------------- 2. create the repository
create_with_token() {
  curl -sS -X POST https://api.github.com/user/repos \
    -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
    -d "{\"name\":\"$REPO\",\"description\":\"Aniyomi extension that runs Cloudstream plugins (.cs3)\",\"private\":false,\"auto_init\":false}"
}

if [[ -n "$TOKEN" ]]; then
  if curl -sS -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" \
       "https://api.github.com/repos/${OWNER:-$REPO-owner-check}" 2>/dev/null | grep -q '^40'; then :; fi
  LOGIN=$(curl -sS -H "Authorization: Bearer $TOKEN" https://api.github.com/user \
          | python3 -c "import sys,json;print(json.load(sys.stdin).get('login',''))" 2>/dev/null || true)
  OWNER="${OWNER:-$LOGIN}"
  if [[ -z "$OWNER" ]]; then echo "cannot read the GitHub account (bad token?)"; exit 1; fi
  STATUS=$(curl -sS -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" \
           "https://api.github.com/repos/$OWNER/$REPO")
  if [[ "$STATUS" == "404" ]]; then
    echo "== creating $OWNER/$REPO"
    create_with_token >/dev/null
  else
    echo "== repository $OWNER/$REPO already exists ($STATUS)"
  fi
  REMOTE="https://${TOKEN}@github.com/$OWNER/$REPO.git"
elif command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  gh repo view "$REPO" >/dev/null 2>&1 || gh repo create "$REPO" --public --source=. --remote=origin --push
  OWNER=$(gh api user --jq .login)
  REMOTE="git@github.com:$OWNER/$REPO.git"
else
  cat <<'MSG'
No GITHUB_TOKEN and no authenticated `gh`. Create the repository yourself:

  1. https://github.com/new  -> name: csbridge (public)
  2. then run:
       git remote add origin https://github.com/<votre-compte>/csbridge.git
       git branch -M main
       git push -u origin main
MSG
  exit 1
fi

# ---------------------------------------------------------------- 3. push
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REMOTE"
else
  git remote add origin "$REMOTE"
fi
git branch -M main
git push -u origin main
git tag -f v1 >/dev/null 2>&1 && git push -f origin v1 || true

OWNER="${OWNER:-$(git remote get-url origin | sed -E 's#.*[:/]([^/]+)/[^/]+(\.git)?$#\1#')}"
cat <<MSG

==================================================================
Published: https://github.com/$OWNER/$REPO

Depot a ajouter dans Aniyomi / AniZen
  Parametres > Parcourir > Depots d'extensions :
  https://raw.githubusercontent.com/$OWNER/$REPO/main/repo/index.min.json

Puis : Parcourir > Extensions > Cloudstream Bridge > Installer.
(Le workflow GitHub Actions construit l'APK a chaque tag v*.)
==================================================================
MSG
