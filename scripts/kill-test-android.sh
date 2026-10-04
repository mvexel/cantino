#!/bin/sh
# Process-death tests for the area download (ProcessDeathTest): kills the test
# process with kill -9 mid-download and right after the commit point, then
# checks in a fresh process that the old area survived (or the new one rolled
# forward) and that WorkManager's rerun of the same work finishes the job.
#
# Usage: ANDROID_SERIAL=<device> scripts/kill-test-android.sh
# Needs native libs built (scripts/build-android.sh). Runs both scenarios;
# exits non-zero if any phase fails.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
case "$(uname -s)" in Darwin) default_sdk=$HOME/Library/Android/sdk ;; *) default_sdk=$HOME/Android/Sdk ;; esac
adb="${ANDROID_HOME:-$default_sdk}/platform-tools/adb"
port="${KILL_TEST_PORT:-8765}"
package=lol.osm.cantino.test
runner="$package/androidx.test.runner.AndroidJUnitRunner"
log="${TMPDIR:-/tmp}/cantino-kill-test.$$"
mkdir -p "$log"

python3 "$root/scripts/fake-sliceosm.py" "$port" "$root/tests/fixtures" 2>"$log/server.log" &
server=$!
trap 'kill $server 2>/dev/null || true; "$adb" reverse --remove tcp:$port 2>/dev/null || true' EXIT
"$adb" reverse tcp:"$port" tcp:"$port" >/dev/null

(cd "$root/android" && mise exec -- ./gradlew -q :cantino:installDebugAndroidTest)

instrument() { # phase area -> prints am instrument output
    "$adb" shell am instrument -w -r -e class lol.osm.cantino.ProcessDeathTest \
        -e killPhase "$1" -e port "$port" -e area "$2" "$runner"
}

phase() { # phase area: must pass
    if instrument "$1" "$2" >"$log/$1.txt" 2>&1 && grep -q '^OK (1 test)' "$log/$1.txt"; then
        echo "ok   $1 ($2)"
    else
        echo "FAIL $1 ($2)"; grep -E 'INSTRUMENTATION_STATUS: stack=|Error|Exception' "$log/$1.txt" | head -5
        exit 1
    fi
}

killed() { # phase area: parks itself, gets kill -9
    "$adb" logcat -c
    instrument "$1" "$2" >"$log/$1.txt" 2>&1 &
    waiting=$!
    tries=0
    until "$adb" logcat -d -s CANTINO_KILL:I | grep -q "KILL_ME $2"; do
        tries=$((tries + 1))
        if [ "$tries" -gt 120 ]; then echo "FAIL $1 ($2): never reached the kill point"; exit 1; fi
        sleep 1
    done
    pid=$("$adb" shell pidof "$package" | tr -d '\r')
    "$adb" shell run-as "$package" kill -9 "$pid"
    wait "$waiting" || true
    echo "ok   $1 ($2): killed pid $pid"
}

stamp=$(date +%s)
download="kill-download-$stamp"
phase seed "$download"
killed download "$download"
phase verifyDownload "$download"

commit="kill-commit-$stamp"
phase seed "$commit"
killed commit "$commit"
phase verifyCommit "$commit"

"$adb" logcat -d -s CANTINO_KILL:I | grep scenario || true
echo "all process-death phases passed (logs: $log)"
