#!/bin/sh
# 개발용 정면도 렌더: tools/preview.sh [출력.png]
set -e
cd "$(dirname "$0")/.."
JOML=$(find ~/.m2/repository/org/joml/joml -name 'joml-*.jar' | head -1)
OUT=$(mktemp -d)
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$OUT" -cp "$JOML" \
  src/main/java/com/fsixel/deruyrpg/boss/moss/MossModel.java \
  src/main/java/com/fsixel/deruyrpg/boss/moss/MossAnim.java tools/ModelPreview.java tools/AnimCheck.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp "$OUT:$JOML" ModelPreview "${1:-preview.png}"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp "$OUT:$JOML" AnimCheck "${2:-anim.png}"
