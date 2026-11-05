#!/usr/bin/env bash
# Pack Gradle/Kotlin errors from a build log into one GitHub annotation.
log="$1"
msg=$( { grep -E "^e: |AAPT|Could not (find|resolve)" -A3 "$log" | head -30; grep -E "error:|undefined (reference|symbol)" "$log" | head -15; grep -E "What went wrong" -A30 "$log" | head -60; } \
  | sed -e 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
echo "::error title=Muninn build::${msg:-build failed, no matching lines}"
