#!/usr/bin/env bash
# 重建 libs/ 里的 Sable 依赖（jar 不入库，见 .gitignore 与 04-依赖与运维）。
#
# 为什么需要这个脚本：build.gradle 用 compileOnly files("libs/sable-companion-*.jar")，
# 而那个 jar 本来是 sable 主 jar 的嵌套内容（META-INF/jars/）。
# 不把它解出来，任何碰 sub-level 位姿的代码都编译不过
# （Pose3dc / BoundingBox3ic / JOMLConversion 只存在于 companion jar 里）。
set -euo pipefail

cd "$(dirname "$0")/.."
SABLE="${SABLE_JAR:-dist/sable-fabric-1.21.1-2.0.6.jar}"
mkdir -p libs

if [ ! -f "$SABLE" ]; then
  echo "找不到 $SABLE" >&2
  echo "先从 Modrinth 下载 sable-fabric-1.21.1-2.0.6.jar 放进 dist/（该目录不入库）" >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$SABLE" "META-INF/jars/*" -d "$TMP"

for nested in "$TMP"/META-INF/jars/*.jar; do
  name="$(basename "$nested")"
  # 只取编译期真正需要的 companion；rapier/veil 不参与我们的源码编译，
  # 而且把带 fabric.mod.json 的模组 jar 放进 run/mods 会被重复注册。
  case "$name" in
    sable-companion-*.jar)
      cp "$nested" "libs/$name"
      echo "已提取 libs/$name（仅 compileOnly）"
      ;;
  esac
done

ls -la libs/
