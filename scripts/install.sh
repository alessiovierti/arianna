#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPOSITORY_ROOT"

case "$(uname -s)" in
  Darwin)
    if [[ -z "${JAVA_HOME:-}" ]]; then
      JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    fi
    PACKAGE_PATH="$REPOSITORY_ROOT/build/autonomous-package/learn.app"
    INSTALL_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/arianna"
    INSTALL_PATH="$INSTALL_ROOT/learn.app"
    LAUNCHER_PATH="$INSTALL_PATH/Contents/MacOS/learn"
    ;;
  Linux)
    PACKAGE_PATH="$REPOSITORY_ROOT/build/autonomous-package/learn"
    INSTALL_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/arianna"
    INSTALL_PATH="$INSTALL_ROOT/learn"
    LAUNCHER_PATH="$INSTALL_PATH/bin/learn"
    ;;
  *)
    echo "Unsupported operating system: $(uname -s)" >&2
    echo "Installers for macOS and Linux are currently supported." >&2
    exit 1
    ;;
esac

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 21 is required to install or update Arianna." >&2
  echo "Set JAVA_HOME to a JDK 21 installation and run this script again." >&2
  exit 1
fi

if ! "$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1p' | grep -q '"21\.'; then
  echo "JAVA_HOME must point to JDK 21 (found: $JAVA_HOME)." >&2
  exit 1
fi

export JAVA_HOME
echo "Building Arianna with JDK 21..."
./gradlew packageAutonomous --no-daemon --console=plain

if [[ ! -e "$PACKAGE_PATH" ]]; then
  echo "Autonomous package was not created: $PACKAGE_PATH" >&2
  exit 1
fi

INSTALL_BIN="$HOME/.local/bin"
mkdir -p "$INSTALL_ROOT" "$INSTALL_BIN"

STAGING_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/arianna-install.XXXXXX")"
trap 'rm -rf "$STAGING_ROOT"' EXIT

BUNDLE_NAME="$(basename "$INSTALL_PATH")"
cp -R "$PACKAGE_PATH" "$STAGING_ROOT/$BUNDLE_NAME"
rm -rf "$INSTALL_PATH"
mv "$STAGING_ROOT/$BUNDLE_NAME" "$INSTALL_PATH"

COMMAND_PATH="$INSTALL_BIN/learn"
if [[ -e "$COMMAND_PATH" || -L "$COMMAND_PATH" ]]; then
  rm -f "$COMMAND_PATH"
fi
ln -s "$LAUNCHER_PATH" "$COMMAND_PATH"

echo
echo "Arianna installed at: $INSTALL_PATH"
echo "Command: $COMMAND_PATH"
if [[ ":${PATH}:" != *":$INSTALL_BIN:"* ]]; then
  echo "Add $INSTALL_BIN to PATH to run 'learn' directly:"
  echo "  export PATH=\"$INSTALL_BIN:\$PATH\""
fi
echo "Verify with: $COMMAND_PATH --version"
