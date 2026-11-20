#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M6 Q 域资源完整性静态扫描（Q1-Q8）。

只读 src/main/resources 与 src/main/java，不启动服务端、不跑 gradle、不写任何文件。
只用 Python 标准库。

用法（Windows，项目根目录下）::

    .\\run\\py-embed\\python.exe -I -X utf8 tools/check_resources.py
    .\\run\\py-embed\\python.exe -I -X utf8 tools/check_resources.py --strict
    .\\run\\py-embed\\python.exe -I -X utf8 tools/check_resources.py --verbose

退出码：
    0 = 没有 ERROR（可能存在 WARN/INFO，表示需要人工裁决或在扫描范围外）
    1 = 存在 ERROR
    --strict 时，存在 WARN 也返回 1。

设计原则：
    * 每条结论都给证据：文件相对路径 + JSON path / key。
    * `minecraft:` / 其他模组（sable）的引用无法在本仓库静态验证，记为 OOS（out-of-scope），
      不报成 bug；OOS 汇总在每节末尾，不逐条刷屏。
    * 覆盖 2026-11-21 美术集成时的两类已知致命渲染 bug：
        - 模型 `textures` 的键名误写成 `#xxx`（MC 不报错，但整面退化成紫黑块）
        - 模型元素 rotation.angle 用了 MC 不接受的任意角度（进游戏 JsonParseException）
      另外还查 elements 越界、JSON 重复键等会静默/报错导致模型加载失败的写法。
"""

from __future__ import annotations

import argparse
import itertools
import json
import re
import sys
import zlib
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

MODID = "nbw"
# MC 方块/物品模型 rotation.angle 只接受这 5 档（BlockModel.Deserializer）。
ALLOWED_ROTATION_ANGLES = {-45.0, -22.5, 0.0, 22.5, 45.0}
# 「仅系统/管理员放置」的物品口径（M6-Q-RESOURCES §6.1 B 档）。
# 这些物品设计上就不该在局内被玩家拿到，所以 Q4 的「无配方+无运行期来源」对它们是**预期**，
# 记 INFO 而不是 WARN。新增条目必须同时在 docs/dev/M6-Q-RESOURCES.md §6.1 里写清理由。
SYSTEM_ONLY_ITEMS = {
    "raft_core": "开局由 RaftSpawner.generateForTeams 直接放方块，物品形态仅供 dev",
    "oar": "桨台自查出「问题③」后只剩装饰，推进功能已删除，局内明确不给（NbwContent 注释已写）",
}
# 1.21 方块模型元素坐标硬上限（JsonParseException: "position ... out of range"）。
ELEMENT_MIN = -16.0
ELEMENT_MAX = 32.0
# 已知原版配方类型（用于抓 typo；未知类型只 WARN，因为数据包/模组可扩展）。
VANILLA_RECIPE_TYPES = {
    "minecraft:crafting_shaped", "minecraft:crafting_shapeless",
    "minecraft:crafting_special_armordye", "minecraft:crafting_special_bannerduplicate",
    "minecraft:crafting_special_bookcloning", "minecraft:crafting_special_firework_rocket",
    "minecraft:crafting_special_firework_star", "minecraft:crafting_special_firework_star_fade",
    "minecraft:crafting_special_mapcloning", "minecraft:crafting_special_mapextending",
    "minecraft:crafting_special_repairitem", "minecraft:crafting_special_shielddecoration",
    "minecraft:crafting_special_shulkerboxcoloring", "minecraft:crafting_special_suspiciousstew",
    "minecraft:crafting_special_tippedarrow", "minecraft:crafting_transmute",
    "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking",
    "minecraft:stonecutting", "minecraft:smithing_transform", "minecraft:smithing_trim",
}
# 1.21 战利品表 entry type（由 data/minecraft/loot_table 的叶子类型决定）。
KNOWN_LOOT_ENTRY_TYPES = {
    "minecraft:item", "minecraft:tag", "minecraft:empty", "minecraft:loot_table",
    "minecraft:group", "minecraft:alternatives", "minecraft:sequence",
    "minecraft:dynamic", "minecraft:composite",
}
# Sable 文档化的物理属性键（docs/dev/M0-TEST-PLAN.md:139、docs/dev/M2-Sable适配层.md:113、sable wiki）。
SABLE_PHYSICS_PROPERTIES = {
    "sable:mass": "number",
    "sable:inertia": "number3",
    "sable:volume": "number",
    "sable:restitution": "number",
    "sable:friction": "number",
    "sable:fragile": "bool",
    "sable:floating_material": "string",
    "sable:floating_scale": "number",
}
# 明确的原版声音资源根目录（Q8 只用得到 Code API 里的 SoundEvents 常量名）。
SURVIVAL_PHASE_NOTE = (
    "RoundManager.java:390 在 PLAY 阶段把参赛者切为 SURVIVAL；BuildService 允许在可建造阶段破方块。"
)


# ---------------------------------------------------------------------------
# 基础结构
# ---------------------------------------------------------------------------


class Colors:
    ERROR = "ERROR"
    WARN = "WARN"
    INFO = "INFO"


@dataclass
class Finding:
    level: str          # ERROR / WARN / INFO
    domain: str         # Q1..Q8 / JSON / REG
    message: str
    file: str = ""      # 相对项目根的路径
    loc: str = ""       # JSON path / Java 行号 / key

    def render(self) -> str:
        where = self.file
        if self.loc:
            where = f"{where} @ {self.loc}" if where else self.loc
        return f"[{self.level}] ({self.domain}) {where}: {self.message}" if where \
            else f"[{self.level}] ({self.domain}) {self.message}"


@dataclass
class OutOfScope:
    domain: str
    message: str
    file: str = ""
    loc: str = ""


@dataclass
class Scanner:
    root: Path
    findings: list[Finding] = field(default_factory=list)
    oos: list[OutOfScope] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    # 注册表清单
    registered_blocks: set[str] = field(default_factory=set)
    registered_items: set[str] = field(default_factory=set)
    block_items: set[str] = field(default_factory=set)      # 有 BlockItem 的方块
    melee_weapons: list[str] = field(default_factory=list)  # 由 weapons.json 注册
    block_class: dict[str, str] = field(default_factory=dict)   # 方块 id -> java 类名
    requires_tool: set[str] = field(default_factory=set)         # 用了 .requiresTool() 的方块
    lang: dict[str, dict[str, str]] = field(default_factory=dict)

    # ---------------- 路径与工具 ----------------

    @property
    def res(self) -> Path:
        return self.root / "src" / "main" / "resources"

    @property
    def assets(self) -> Path:
        return self.res / "assets" / MODID

    @property
    def data(self) -> Path:
        return self.res / "data"

    @property
    def java(self) -> Path:
        return self.root / "src" / "main" / "java"

    def rel(self, p: Path) -> str:
        try:
            return p.resolve().relative_to(self.root.resolve()).as_posix()
        except ValueError:
            return str(p)

    def add(self, level: str, domain: str, message: str, file: str = "", loc: str = "") -> None:
        self.findings.append(Finding(level, domain, message, file, loc))

    def error(self, domain: str, message: str, file: str = "", loc: str = "") -> None:
        self.add(Colors.ERROR, domain, message, file, loc)

    def warn(self, domain: str, message: str, file: str = "", loc: str = "") -> None:
        self.add(Colors.WARN, domain, message, file, loc)

    def info(self, domain: str, message: str, file: str = "", loc: str = "") -> None:
        self.add(Colors.INFO, domain, message, file, loc)

    def skip(self, domain: str, message: str, file: str = "", loc: str = "") -> None:
        self.oos.append(OutOfScope(domain, message, file, loc))

    def read_text(self, path: Path) -> str | None:
        try:
            return path.read_text(encoding="utf-8-sig")
        except UnicodeDecodeError as exc:
            self.error("JSON", f"文件不是合法 UTF-8：{exc}", self.rel(path))
            return None
        except OSError as exc:
            self.error("JSON", f"读不到文件：{exc}", self.rel(path))
            return None

    def load_json(self, path: Path, domain: str = "JSON"):
        """读 JSON；重复键记 WARN（Gson 后值覆盖前值，静默丢配置）。

        返回解析后的对象；失败返回 None 并记 ERROR。
        """
        text = self.read_text(path)
        if text is None:
            return None
        dups: list[str] = []

        def hook(pairs):
            obj = {}
            for key, val in pairs:
                if key in obj:
                    dups.append(key)
                obj[key] = val
            return obj

        try:
            obj = json.loads(text, object_pairs_hook=hook)
        except json.JSONDecodeError as exc:
            self.error(domain, f"JSON 解析失败：{exc.msg}（第 {exc.lineno} 行第 {exc.colno} 列）",
                       self.rel(path))
            return None
        if dups:
            self.warn(domain, "JSON 有重复键（解析器取最后值）：" + ", ".join(sorted(set(dups))),
                      self.rel(path))
        return obj

    @staticmethod
    def png_ok(path: Path) -> str | None:
        """纯标准库校验 PNG：头 + 全部 chunk CRC + IDAT 可解压 + 像素数据长度。

        <p>**2026-11-21 加强**：原来只看文件头 33 字节和 IHDR 尺寸，于是
        `water_pistol.png`（82 字节、像素数据被截断）被判为"没问题"，
        而客户端 `NativeImage.read` 直接抛异常、游戏里显示紫黑格 ——
        日志只有一句 `Using missing texture, unable to load nbw:item/water_pistol`。
        "文件存在且头对"根本不够，必须把 zlib 解一遍并对像素长度。
        """
        try:
            data = path.read_bytes()
        except OSError as exc:
            return f"读不到文件：{exc}"
        if len(data) < 33:
            return f"文件只有 {len(data)} 字节，不像 PNG"
        if data[:8] != b"\x89PNG\r\n\x1a\n":
            return "PNG 文件头签名不对（不是合法 PNG）"
        if data[12:16] != b"IHDR":
            return "PNG 首个 chunk 不是 IHDR"
        width = int.from_bytes(data[16:20], "big")
        height = int.from_bytes(data[20:24], "big")
        if width <= 0 or height <= 0:
            return f"PNG 尺寸非法：{width}x{height}"
        bit_depth = data[24]
        color_type = data[25]
        if color_type not in (0, 2, 3, 4, 6):
            return f"PNG 颜色类型非法：{color_type}"
        # 逐个 chunk 校验 CRC，并收集 IDAT
        off = 8
        idat = b""
        saw_iend = False
        while off + 8 <= len(data):
            length = int.from_bytes(data[off:off + 4], "big")
            ctype = data[off + 4:off + 8]
            body = data[off + 8:off + 8 + length]
            if len(body) < length:
                return f"{ctype!r} chunk 数据被截断（声明 {length}，实际 {len(body)}）"
            crc_at = off + 8 + length
            if crc_at + 4 > len(data):
                return f"{ctype!r} chunk 缺 CRC（文件在 {crc_at} 处截断）"
            crc = int.from_bytes(data[crc_at:crc_at + 4], "big")
            if zlib.crc32(ctype + body) & 0xFFFFFFFF != crc:
                return f"{ctype!r} chunk CRC 不匹配（文件损坏）"
            if ctype == b"IDAT":
                idat += body
            elif ctype == b"IEND":
                saw_iend = True
                break
            off = crc_at + 4
        if not saw_iend:
            return "没有 IEND（PNG 未写完，多半是被截断）"
        try:
            raw = zlib.decompress(idat)
        except zlib.error as exc:
            return f"IDAT 解压失败：{exc}"
        channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
        stride = (width * channels * bit_depth + 7) // 8
        need = height * (1 + stride)
        if len(raw) < need:
            return (f"像素数据不足：{len(raw)} < {need}"
                    f"（{width}x{height} bd={bit_depth} ct={color_type}）")
        return None

    @staticmethod
    def png_size(path: Path) -> tuple[int, int] | None:
        try:
            with path.open("rb") as fp:
                data = fp.read(24)
            if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
                return None
            return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
        except OSError:
            return None

    # ---------------- Java 侧：注册表提取 ----------------

    def extract_registrations(self) -> None:
        """从 NbwContent.java 提取方块/物品注册 id，从 data/nbw/weapons.json 提取近战武器。"""
        content_path = self.java / "dev" / "nbw" / "content" / "NbwContent.java"
        text = self.read_text(content_path)
        if text is None:
            return
        nore = self.rel(content_path)
        consts = dict(re.findall(r'public static final String\s+(\w+)\s*=\s*"([^"]+)"\s*;', text))
        if not consts:
            self.error("REG", "没在 NbwContent.java 里提取到 `public static final String` 常量，"
                              "注册表清单不可信", nore)
        for reg, bucket in (("BLOCK", self.registered_blocks), ("ITEM", self.registered_items)):
            pattern = (r'Registry\.register\(Registries\.' + reg +
                       r',\s*Identifier\.of\(NBW\.MOD_ID,\s*([A-Z][A-Z0-9_]*)\)')
            matches = re.findall(pattern, text)
            if not matches:
                self.error("REG", f"没在 NbwContent.java 里提取到任何 {reg} 注册，"
                                  f"扫描结论会大量误报", nore)
            for const in matches:
                rid = consts.get(const)
                if rid is None:
                    self.error("REG", f"注册用了未声明的常量 {const}", nore)
                    continue
                bucket.add(rid)
        # 方块 id -> Java 类名（用于 blockstate 属性交叉校验）+ 是否 requiresTool
        block_regs = list(re.finditer(
            r'Registry\.register\(Registries\.BLOCK,\s*'
            r'Identifier\.of\(NBW\.MOD_ID,\s*([A-Z][A-Z0-9_]*)\)', text))
        for i, m in enumerate(block_regs):
            const = m.group(1)
            rid = consts.get(const)
            end = block_regs[i + 1].start() if i + 1 < len(block_regs) else len(text)
            chunk = text[m.start():end]
            cm = re.search(r'new\s+(\w+)\(', chunk)
            if rid and cm:
                self.block_class[rid] = cm.group(1)
            if rid and ".requiresTool()" in chunk:
                self.requires_tool.add(rid)
        # 有 BlockItem 的方块：ITEM 注册用的常量名与 BLOCK 相同
        block_consts = set(re.findall(
            r'Registry\.register\(Registries\.BLOCK,\s*Identifier\.of\(NBW\.MOD_ID,\s*([A-Z][A-Z0-9_]*)\)',
            text))
        item_consts = set(re.findall(
            r'Registry\.register\(Registries\.ITEM,\s*Identifier\.of\(NBW\.MOD_ID,\s*([A-Z][A-Z0-9_]*)\)',
            text))
        for const in block_consts & item_consts:
            rid = consts.get(const)
            if rid:
                self.block_items.add(rid)
        # 近战武器：NbwWeapons.register() 只注册 weapons.json 里 kind=melee 的条目
        weapons_path = self.data / MODID / "weapons.json"
        weapons = self.load_json(weapons_path, "REG")
        if not isinstance(weapons, dict) or not isinstance(weapons.get("weapons"), list):
            self.error("REG", "武器表结构不对（缺少 weapons 数组）", self.rel(weapons_path))
            return
        seen: set[str] = set()
        pending_melee: list[str] = []
        errors_before = sum(1 for f in self.findings if f.level == Colors.ERROR)
        for idx, spec in enumerate(weapons["weapons"]):
            loc = f"weapons[{idx}]"
            if not isinstance(spec, dict):
                self.error("REG", "武器条目不是对象", self.rel(weapons_path), loc)
                continue
            wid = spec.get("id")
            kind = spec.get("kind")
            if not isinstance(wid, str) or not re.fullmatch(r"[a-z0-9_]{1,32}", wid):
                self.error("REG", f"武器 id 不合法：{wid!r}（只允许 [a-z0-9_]{{1,32}}）",
                           self.rel(weapons_path), loc)
                continue
            if wid in seen:
                self.error("REG", f"武器 id 重复：{wid}", self.rel(weapons_path), loc)
            seen.add(wid)
            if not isinstance(kind, str) or kind.strip().upper() not in {"MELEE", "RANGED", "TOOL"}:
                self.error("REG", f"武器 {wid} 的 kind 不合法：{kind!r}", self.rel(weapons_path), loc)
                continue
            try:
                if float(spec.get("damage", 0)) <= 0:
                    self.error("REG", f"武器 {wid} 的 damage 必须 > 0", self.rel(weapons_path), loc)
                if int(spec.get("cooldownTicks", 0)) <= 0:
                    self.error("REG", f"武器 {wid} 的 cooldownTicks 必须 > 0",
                               self.rel(weapons_path), loc)
                if float(spec.get("knockback", 0)) < 0:
                    self.error("REG", f"武器 {wid} 的 knockback 不得为负", self.rel(weapons_path), loc)
                if float(spec.get("coreMultiplier", 0)) < 1.0:
                    self.error("REG", f"武器 {wid} 的 coreMultiplier 必须 >= 1.0",
                               self.rel(weapons_path), loc)
            except (TypeError, ValueError):
                self.error("REG", f"武器 {wid} 的数值字段类型不对", self.rel(weapons_path), loc)
                continue
            if kind.strip().upper() == "MELEE":
                pending_melee.append(wid)
        errors_after = sum(1 for f in self.findings if f.level == Colors.ERROR)
        if errors_after > errors_before:
            # NbwWeapons.register() 只要发现一条 spec 错误就整表不注册（见该文件 44-48 行）
            self.error("REG", "weapons.json 校验失败 → NbwWeapons.register() 会提前返回，"
                              "所有近战武器一件都不注册（这些物品在客户端/战利品里全部缺失）",
                       self.rel(weapons_path))
        else:
            self.melee_weapons = pending_melee
            self.registered_items.update(pending_melee)
        if not self.registered_items:
            self.error("REG", "注册表清单为空，后续静态校验不可信（先修 REG 错误）")

    def extract_block_properties(self) -> dict[str, list[tuple[str, list[str]]]]:
        """从 content/*.java 提取每个方块类的属性（名字 + 合法值列表）。

        返回 {方块 id: [(prop, [value...]), ...]}；解析不出来的方块给 None 并由调用方 WARN。
        结果缓存：Q2 / Q7 都要用，别重复读盘。
        """
        cached = getattr(self, "_block_props_cache", None)
        if cached is not None:
            return cached
        content_dir = self.java / "dev" / "nbw" / "content"
        java_text: dict[str, str] = {}
        if content_dir.is_dir():
            for path in sorted(content_dir.glob("*.java")):
                text = self.read_text(path)
                if text is not None:
                    java_text[path.stem] = text
        result: dict[str, list[tuple[str, list[str]]]] = {}
        for block_id, cls in sorted(self.block_class.items()):
            text = java_text.get(cls)
            if text is None:
                result[block_id] = []   # 类文件不在 content/ 下：保守按"无属性"处理
                continue
            props: list[tuple[str, list[str]]] = []
            for m in re.finditer(
                    r'public static final BooleanProperty\s+(\w+)\s*=\s*'
                    r'BooleanProperty\.of\("([^"]+)"\)', text):
                props.append((m.group(2), ["true", "false"]))
            for m in re.finditer(
                    r'public static final DirectionProperty\s+(\w+)\s*=\s*'
                    r'DirectionProperty\.of\("([^"]+)"(?:,\s*Direction\.Type\.(\w+))?\)', text):
                dtype = m.group(3) or "ALL"
                if dtype == "HORIZONTAL":
                    values = ["north", "east", "south", "west"]
                elif dtype == "VERTICAL":
                    values = ["up", "down"]
                else:
                    values = ["north", "east", "south", "west", "up", "down"]
                props.append((m.group(2), values))
            for m in re.finditer(
                    r'public static final EnumProperty<(\w+)>\s+(\w+)\s*=\s*'
                    r'EnumProperty\.of\("([^"]+)",\s*(\w+)\.class\)', text):
                enum_name, prop_name = m.group(1), m.group(3)
                body = self._find_enum_body(text, enum_name)
                values: list[str] = []
                if body is not None:
                    values = [v for _c, v in re.findall(r'([A-Z][A-Z0-9_]*)\("([^"]*)"\)', body)]
                    if not values:
                        values = [c.lower() for c in re.findall(r'\b([A-Z][A-Z0-9_]*)\b', body)]
                if not values:
                    self.warn("Q2", f"方块 {block_id} 的枚举属性 {prop_name}（枚举 {enum_name}）"
                                    f"没解析出合法值，variant 值校验会跳过", self.rel(
                                        content_dir / f"{cls}.java"))
                props.append((prop_name, values))
            result[block_id] = props
        self._block_props_cache = result
        return result

    @staticmethod
    def _find_enum_body(text: str, enum_name: str) -> str | None:
        m = re.search(r'enum\s+' + re.escape(enum_name) + r'\s*(?:implements\s+[^{]+)?\{', text)
        if not m:
            return None
        depth = 1
        start = m.end()
        for i in range(start, len(text)):
            ch = text[i]
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    return text[start:i]
        return None

    # =======================================================================
    # Q1 模型引用的贴图存在
    # =======================================================================
    def check_q1(self) -> None:
        models_dir = self.assets / "models"
        textures_dir = self.assets / "textures"
        if not models_dir.is_dir():
            self.error("Q1", "assets/nbw/models/ 不存在，Q1 无法扫描", self.rel(models_dir))
            return
        model_files = sorted(models_dir.rglob("*.json"))
        loaded: dict[str, dict | None] = {}
        for path in model_files:
            mid = path.relative_to(models_dir).with_suffix("").as_posix()
            obj = self.load_json(path, "Q1")
            loaded[mid] = obj if isinstance(obj, dict) else None
        texture_vars: dict[str, int] = {}
        referenced_textures: set[str] = set()
        oos_textures: dict[str, int] = {}
        chain_errors: set[tuple[str, str]] = set()
        reported_missing: set[tuple[str, str]] = set()     # (模型, 贴图路径) 只报一次
        reported_bad_png: set[str] = set()
        reported_undef_var: set[tuple[str, str]] = set()

        def resolve_chain(mid: str, seen: tuple[str, ...] = ()) -> list[str]:
            """返回该模型 + nbw parent 链（子在前）。minecraft parent 记为 OOS 中断。"""
            if mid in seen:
                self.error("Q1", "模型 parent 链成环：" + " -> ".join(seen + (mid,)),
                           self.rel(models_dir / (mid + ".json")), "parent")
                return []
            obj = loaded.get(mid)
            if obj is None:
                return []
            chain = [mid]
            parent = obj.get("parent")
            if parent is None:
                return chain
            if not isinstance(parent, str):
                self.error("Q1", f"parent 不是字符串：{parent!r}",
                           self.rel(models_dir / (mid + ".json")), "parent")
                return chain
            ns, path = self._split_id(parent, default_ns="minecraft")
            if ns != MODID:
                self.skip("Q1", f"parent {parent}（非 {MODID} 命名空间）", self.rel(
                    models_dir / (mid + ".json")), "parent")
                return chain
            if path not in loaded:
                if (mid, path) not in chain_errors:
                    chain_errors.add((mid, path))
                    self.error("Q1", f"parent 指向的模型文件不存在：assets/{MODID}/models/{path}.json",
                               self.rel(models_dir / (mid + ".json")), "parent")
                return chain
            return chain + resolve_chain(path, seen + (mid,))

        def effective_textures(mid: str) -> dict[str, str]:
            merged: dict[str, str] = {}
            chain = resolve_chain(mid)
            for node in reversed(chain):   # 从祖先往子覆盖
                node_obj = loaded.get(node) or {}
                tex = node_obj.get("textures")
                if isinstance(tex, dict):
                    for key, val in tex.items():
                        merged[str(key)] = val if isinstance(val, str) else repr(val)
            return merged

        def check_texture_value(mid: str, file_path: Path, loc: str, value, eff: dict[str, str],
                                seen_vars: tuple[str, ...]) -> None:
            if not isinstance(value, str):
                self.error("Q1", f"贴图引用不是字符串：{value!r}", self.rel(file_path), loc)
                return
            if value.startswith("#"):
                var = value[1:]
                texture_vars[var] = texture_vars.get(var, 0) + 1
                if not var:
                    self.error("Q1", "空的纹理变量引用 '#'", self.rel(file_path), loc)
                    return
                if var in seen_vars:
                    self.error("Q1", "纹理变量互相引用成环："
                               + " -> ".join("#" + v for v in seen_vars + (var,)),
                               self.rel(file_path), loc)
                    return
                if var not in eff:
                    if (mid, var) not in reported_undef_var:
                        reported_undef_var.add((mid, var))
                        self.error("Q1", f"纹理变量 #{var} 在本模型及 nbw parent 链里都没有定义",
                                   self.rel(file_path), loc)
                    return
                check_texture_value(mid, file_path, loc + f" -> #{var}", eff[var], eff,
                                    seen_vars + (var,))
                return
            ns, path = self._split_id(value, default_ns="minecraft")
            if ns != MODID:
                oos_textures[value] = oos_textures.get(value, 0) + 1
                return
            png = textures_dir / (path + ".png")
            referenced_textures.add(path + ".png")
            if not png.is_file():
                if (mid, path) not in reported_missing:
                    reported_missing.add((mid, path))
                    self.error("Q1", f"贴图文件不存在：assets/{MODID}/textures/{path}.png",
                               self.rel(file_path), loc)
                return
            bad = self.png_ok(png)
            if bad and self.rel(png) not in reported_bad_png:
                reported_bad_png.add(self.rel(png))
                self.error("Q1", f"贴图文件损坏：{bad}", self.rel(png))

        for mid, obj in sorted(loaded.items()):
            if obj is None:
                continue
            file_path = models_dir / (mid + ".json")
            if not isinstance(obj.get("textures", {}), dict):
                self.error("Q1", "textures 不是对象", self.rel(file_path), "textures")
            eff = effective_textures(mid)
            # 1) textures 块的键 / 值
            tex = obj.get("textures")
            if isinstance(tex, dict):
                for key, val in tex.items():
                    loc = f"textures.{key}"
                    if str(key).startswith("#"):
                        self.error("Q1", f"textures 的键名不能带 '#'（面引用才带）：{key!r}；"
                                         f"这会让整面静默退化成紫黑块",
                                   self.rel(file_path), loc)
                    check_texture_value(mid, file_path, loc, val, eff, ())
            # 2) elements[].faces.*.texture
            elements = obj.get("elements")
            if elements is not None and not isinstance(elements, list):
                self.error("Q1", "elements 不是数组", self.rel(file_path), "elements")
                elements = None
            for ei, element in enumerate(elements or []):
                if not isinstance(element, dict):
                    self.error("Q1", "element 不是对象", self.rel(file_path), f"elements[{ei}]")
                    continue
                self._check_element_geometry(file_path, f"elements[{ei}]", element)
                faces = element.get("faces")
                if not isinstance(faces, dict):
                    self.error("Q1", "faces 不是对象", self.rel(file_path), f"elements[{ei}].faces")
                    continue
                for face, fdef in faces.items():
                    floc = f"elements[{ei}].faces.{face}"
                    if not isinstance(fdef, dict):
                        self.error("Q1", "face 不是对象", self.rel(file_path), floc)
                        continue
                    if "texture" not in fdef:
                        self.error("Q1", "face 缺少 texture", self.rel(file_path), floc)
                        continue
                    check_texture_value(mid, file_path, floc + ".texture", fdef["texture"], eff, ())
                    uv = fdef.get("uv")
                    if uv is not None and (not isinstance(uv, list) or len(uv) != 4
                                           or not all(isinstance(x, (int, float)) for x in uv)):
                        self.error("Q1", "face.uv 必须是 4 个数字", self.rel(file_path),
                                   floc + ".uv")
                    cullface = fdef.get("cullface")
                    if cullface is not None and cullface not in {
                            "north", "east", "south", "west", "up", "down"}:
                        self.error("Q1", f"face.cullface 非法：{cullface!r}"
                                         f"（只能是 6 个方向）", self.rel(file_path),
                                   floc + ".cullface")
                    tint = fdef.get("tintindex")
                    if tint is not None and (not isinstance(tint, int) or isinstance(tint, bool)):
                        self.error("Q1", "face.tintindex 必须是整数", self.rel(file_path),
                                   floc + ".tintindex")
            # 3) 物品模型必须有贴图来源（layer0 或 parent 提供），否则图标紫黑
            if mid.startswith("item/"):
                self._check_item_model_has_texture(file_path, obj, eff, mid)
        # 4) 已注册物品必须有 models/item/<id>.json（缺 = 物品栏紫黑/缺失模型）
        registered_all = self.registered_items | self.registered_blocks
        for item_id in sorted(registered_all):
            p = models_dir / "item" / f"{item_id}.json"
            if not p.is_file():
                self.error("Q1", f"已注册物品 nbw:{item_id} 没有物品模型 assets/{MODID}/models/item/"
                                 f"{item_id}.json（物品栏/手持会显示缺失模型）", self.rel(p))
        # 5) 已注册方块必须有 models/block/<id>.json（blockstate 检查在 Q2，但这里先给提示）
        for block_id in sorted(self.registered_blocks):
            p = models_dir / "block" / f"{block_id}.json"
            if not p.is_file():
                self.info("Q1", f"方块 nbw:{block_id} 没有同名 models/block/{block_id}.json"
                                f"（可能引用其它模型，Q2 会以 blockstate 为准）", self.rel(p))
        # 6) 未被任何模型引用的贴图（INFO，占位/预留不算 bug）
        if textures_dir.is_dir():
            all_png = {p.relative_to(textures_dir).as_posix()
                       for p in textures_dir.rglob("*.png")}
            unused = sorted(all_png - referenced_textures)
            if unused:
                self.info("Q1", f"有 {len(unused)} 张贴图未被任何 nbw 模型引用："
                                + ", ".join(unused[:12]) + (" ..." if len(unused) > 12 else ""))
        self.notes.append(
            f"Q1 扫描 {len(model_files)} 个模型；纹理变量引用 {sum(texture_vars.values())} 处；"
            f"本仓库内引用贴图 {len(referenced_textures)} 张；"
            f"非 nbw 命名空间贴图引用 {sum(oos_textures.values())} 处（OOS）；"
            f"非 nbw parent {sum(1 for o in self.oos if o.domain == 'Q1' and 'parent' in o.message)} 处")
        if oos_textures:
            self.skip("Q1", f"{len(oos_textures)} 个非 nbw 贴图引用（如 "
                            + ", ".join(sorted(oos_textures)[:5]) + "）无法在本仓库静态验证")

    def _check_element_geometry(self, file_path: Path, loc: str, element: dict) -> None:
        """元素几何：from/to 三个数、from<=to、坐标在 MC 允许范围内；rotation.angle 仅 5 档。"""
        for key in ("from", "to"):
            val = element.get(key)
            if not isinstance(val, list) or len(val) != 3 or \
                    not all(isinstance(x, (int, float)) for x in val):
                self.error("Q1", f"{key} 必须是 3 个数字", self.rel(file_path), f"{loc}.{key}")
                continue
            for axis, coord in zip("xyz", val):
                if coord < ELEMENT_MIN or coord > ELEMENT_MAX:
                    self.error("Q1", f"{key}.{axis}={coord} 超出 MC 方块模型允许范围 "
                                     f"[{ELEMENT_MIN:.0f}, {ELEMENT_MAX:.0f}]（模型加载会抛 "
                                     f"position out of range）", self.rel(file_path), f"{loc}.{key}")
        frm, to = element.get("from"), element.get("to")
        if isinstance(frm, list) and isinstance(to, list) and len(frm) == len(to) == 3:
            for axis, a, b in zip("xyz", frm, to):
                if isinstance(a, (int, float)) and isinstance(b, (int, float)) and a > b:
                    self.error("Q1", f"from.{axis}={a} 大于 to.{axis}={b}（元素为负尺寸）",
                               self.rel(file_path), loc)
        rotation = element.get("rotation")
        if rotation is not None:
            if not isinstance(rotation, dict):
                self.error("Q1", "rotation 不是对象", self.rel(file_path), f"{loc}.rotation")
                return
            axis = rotation.get("axis")
            if axis not in {"x", "y", "z"}:
                self.error("Q1", f"rotation.axis 必须是 x/y/z，收到 {axis!r}",
                           self.rel(file_path), f"{loc}.rotation.axis")
            angle = rotation.get("angle")
            if not isinstance(angle, (int, float)):
                self.error("Q1", "rotation.angle 不是数字", self.rel(file_path),
                           f"{loc}.rotation.angle")
            elif float(angle) not in ALLOWED_ROTATION_ANGLES:
                self.error("Q1", f"rotation.angle={angle} 不是 MC 允许的 5 档之一"
                                 f"（-45/-22.5/0/22.5/45）；进游戏会 JsonParseException 整模型加载失败",
                           self.rel(file_path), f"{loc}.rotation.angle")
            origin = rotation.get("origin")
            if origin is not None and (not isinstance(origin, list) or len(origin) != 3):
                self.error("Q1", "rotation.origin 必须是 3 个数字", self.rel(file_path),
                           f"{loc}.rotation.origin")

    def _check_item_model_has_texture(self, file_path: Path, obj: dict, eff: dict[str, str],
                                      mid: str) -> None:
        """物品模型的直接贴图来源：parent 是 minecraft:item/generated/handheld 时必须有 layer0。"""
        parent = obj.get("parent")
        own_tex = obj.get("textures") if isinstance(obj.get("textures"), dict) else {}
        if parent in {"minecraft:item/generated", "item/generated",
                      "minecraft:item/handheld", "item/handheld"}:
            if "layer0" not in own_tex:
                self.error("Q1", f"物品模型 parent={parent} 但没有定义 textures.layer0，图标会紫黑",
                           self.rel(file_path), "textures.layer0")
            return
        # 继承自 block 模型 / 其它模型的：最终仍应解析出某些贴图引用
        if not own_tex and not eff:
            self.error("Q1", "物品模型没有任何贴图来源（自身无 textures，parent 链也取不到）",
                       self.rel(file_path))

    # =======================================================================
    # Q2 blockstate 指向的模型存在
    # =======================================================================
    def check_q2(self) -> None:
        blockstates_dir = self.assets / "blockstates"
        models_dir = self.assets / "models"
        if not blockstates_dir.is_dir():
            self.error("Q2", "assets/nbw/blockstates/ 不存在", self.rel(blockstates_dir))
            return
        files = sorted(blockstates_dir.glob("*.json"))
        state_ids = {p.stem for p in files}
        block_props = self.extract_block_properties()
        model_refs = 0
        oos_models = 0
        for block_id in sorted(self.registered_blocks):
            if block_id not in state_ids:
                self.error("Q2", f"已注册方块 nbw:{block_id} 没有 blockstate "
                                 f"assets/{MODID}/blockstates/{block_id}.json（客户端显示缺失模型）",
                           self.rel(blockstates_dir / f"{block_id}.json"))
        for path in files:
            block_id = path.stem
            if block_id not in self.registered_blocks:
                self.info("Q2", f"blockstate 对应的方块 nbw:{block_id} 不在已注册列表"
                                f"（孤儿资源或注册提取漏了）", self.rel(path))
            obj = self.load_json(path, "Q2")
            if not isinstance(obj, dict):
                continue
            variants: list[tuple[str, dict]] = []
            if "variants" in obj:
                variants_obj = obj["variants"]
                if not isinstance(variants_obj, dict):
                    self.error("Q2", "variants 不是对象", self.rel(path), "variants")
                else:
                    for key, val in variants_obj.items():
                        if isinstance(val, dict):
                            variants.append((key, val))
                        elif isinstance(val, list):
                            for i, sub in enumerate(val):
                                if isinstance(sub, dict):
                                    variants.append((key, sub))
                                else:
                                    self.error("Q2", "variants 的列表元素不是对象",
                                               self.rel(path), f"variants[{key}][{i}]")
                        else:
                            self.error("Q2", "variant 值不是对象/数组", self.rel(path),
                                       f"variants[{key}]")
            if "multipart" in obj:
                multipart = obj["multipart"]
                if not isinstance(multipart, list):
                    self.error("Q2", "multipart 不是数组", self.rel(path), "multipart")
                else:
                    for i, part in enumerate(multipart):
                        if not isinstance(part, dict):
                            self.error("Q2", "multipart 元素不是对象", self.rel(path),
                                       f"multipart[{i}]")
                            continue
                        apply = part.get("apply")
                        if isinstance(apply, dict):
                            variants.append((f"multipart[{i}].apply", apply))
                        elif isinstance(apply, list):
                            for j, sub in enumerate(apply):
                                if isinstance(sub, dict):
                                    variants.append((f"multipart[{i}].apply[{j}]", sub))
                                else:
                                    self.error("Q2", "multipart.apply 列表元素不是对象",
                                               self.rel(path), f"multipart[{i}].apply[{j}]")
                        else:
                            self.error("Q2", "multipart 元素缺少 apply 对象", self.rel(path),
                                       f"multipart[{i}]")
            if not variants and "variants" not in obj and "multipart" not in obj:
                self.error("Q2", "blockstate 既没有 variants 也没有 multipart", self.rel(path))
            for key, variant in variants:
                model = variant.get("model")
                loc = f"variants[{key}].model" if not key.startswith("multipart") \
                    else f"{key}.model"
                # BlockModelRotation 只接受 0/90/180/270；其它值会让该 variant 加载失败。
                if not key.startswith("multipart"):
                    for rot in ("x", "y"):
                        rv = variant.get(rot)
                        if rv is not None and (not isinstance(rv, int) or
                                               isinstance(rv, bool) or rv not in (0, 90, 180, 270)):
                            self.error("Q2", f"variant {rot}={rv!r} 非法（BlockModelRotation 只接受 "
                                             f"0/90/180/270；该 variant 会加载失败）",
                                       self.rel(path), f"variants[{key}].{rot}")
                    uvlock = variant.get("uvlock")
                    if uvlock is not None and not isinstance(uvlock, bool):
                        self.error("Q2", "variant uvlock 必须是布尔", self.rel(path),
                                   f"variants[{key}].uvlock")
                    weight = variant.get("weight")
                    if weight is not None and (not isinstance(weight, int) or
                                               isinstance(weight, bool) or weight <= 0):
                        self.error("Q2", "variant weight 必须是正整数", self.rel(path),
                                   f"variants[{key}].weight")
                if not isinstance(model, str):
                    self.error("Q2", "variant 缺少字符串 model", self.rel(path), loc)
                    continue
                model_refs += 1
                # MC 的 Identifier 解析：不带命名空间时默认 minecraft:
                ns, mpath = self._split_id(model, default_ns="minecraft")
                if ":" not in model:
                    self.warn("Q2", f"模型引用 {model!r} 没写命名空间，MC 会按 "
                                    f"minecraft:{model} 解析（多半是想写 nbw:…）；"
                                    f"本仓库无法静态验证 minecraft 模型",
                              self.rel(path), loc)
                    oos_models += 1
                    continue
                if ns != MODID:
                    oos_models += 1
                    continue
                target = models_dir / (mpath + ".json")
                if not target.is_file():
                    self.error("Q2", f"blockstate 指向的模型不存在：{model} "
                                     f"（应为 assets/{MODID}/models/{mpath}.json）",
                               self.rel(path), loc)
            self._check_blockstate_variants(path, block_id, obj, block_props.get(block_id, []))
        crossed = sum(1 for b in self.registered_blocks if block_props.get(b) is not None
                      and b in self.block_class)
        with_props = {b: [n for n, _v in v] for b, v in block_props.items() if v}
        self.notes.append(f"Q2 扫描 {len(files)} 个 blockstate，校验 {model_refs} 个模型引用"
                          f"（其中非 nbw 模型 {oos_models} 处 OOS）；"
                          f"variant↔Java 属性交叉校验 {crossed}/{len(self.registered_blocks)} 个方块"
                          f"（带属性的：{with_props}）")
        if oos_models:
            self.skip("Q2", f"{oos_models} 个 minecraft: 模型引用无法在本仓库静态验证")

    def _check_blockstate_variants(self, path: Path, block_id: str, obj: dict,
                                   props: list[tuple[str, list[str]]]) -> None:
        """把 blockstate 的 variant 键与 Java 里该方块真实属性做交叉校验。

        - 键里出现方块没有的属性 -> blockstate 加载报 Unknown property（整块模型失效）
        - 某属性的合法值不在键里用 -> 该状态没有 variant（显示缺失模型）
        """
        if block_id not in self.block_class:
            return
        prop_map = {name: values for name, values in props}
        variants_obj = obj.get("variants")
        if not isinstance(variants_obj, dict):
            return   # multipart：条件用 when，本轮不交叉校验
        # 解析每个 variant 键为 {prop: value}
        parsed: list[dict[str, str]] = []
        for key in variants_obj:
            if key == "":
                parsed.append({})
                continue
            cond: dict[str, str] = {}
            ok = True
            for part in str(key).split(","):
                if "=" not in part:
                    self.error("Q2", f"variant 键 {key!r} 不是 prop=value 形式", self.rel(path),
                               f"variants[{key}]")
                    ok = False
                    break
                k, v = part.split("=", 1)
                cond[k.strip()] = v.strip()
            if ok:
                parsed.append(cond)
        # 1) 未知属性名 / 非法值
        for cond in parsed:
            for k, v in cond.items():
                if k not in prop_map:
                    self.error("Q2", f"variant 用了方块没有的属性 {k!r}（该方块属性："
                                     f"{sorted(prop_map) or '无'}）；blockstate 会加载失败",
                               self.rel(path), f"variants[{self._cond_str(cond)}]")
                elif prop_map[k] and v not in prop_map[k]:
                    self.error("Q2", f"variant 属性 {k}={v!r} 不是合法值（合法："
                                     f"{prop_map[k]}）", self.rel(path),
                               f"variants[{self._cond_str(cond)}]")
        # 2) 全覆盖：每个属性组合都能命中至少一个 variant 键
        names = [name for name, values in props if values]
        if not names:
            if parsed and any(parsed):
                pass   # 无属性方块却写了条件：上面未知属性检查已报
            elif not parsed:
                self.error("Q2", "方块无属性，但 blockstate 里没有任何 variant", self.rel(path))
            return
        value_lists = [prop_map[n] for n in names]
        missing: list[str] = []
        for combo in itertools.product(*value_lists):
            full = dict(zip(names, combo))
            hit = False
            for cond in parsed:
                if all(full.get(k) == v for k, v in cond.items()) and \
                        set(cond) <= set(names):
                    hit = True
                    break
            if not hit:
                missing.append(",".join(f"{k}={v}" for k, v in full.items()))
        if missing:
            preview = "; ".join(missing[:6]) + (" ..." if len(missing) > 6 else "")
            self.error("Q2", f"方块 {len(names)} 个属性共 {len(missing)} 个状态没有对应 variant"
                             f"（客户端会报 Unable to load variant / 显示缺失模型）：{preview}",
                       self.rel(path))

    @staticmethod
    def _cond_str(cond: dict[str, str]) -> str:
        return ",".join(f"{k}={v}" for k, v in cond.items())

    # =======================================================================
    # Q3 语言键
    # =======================================================================
    def check_q3(self) -> None:
        lang_dir = self.assets / "lang"
        for code in ("zh_cn", "en_us"):
            path = lang_dir / f"{code}.json"
            obj = self.load_json(path, "Q3")
            if obj is None:
                continue
            if not isinstance(obj, dict):
                self.error("Q3", "语言文件顶层不是对象", self.rel(path))
                continue
            lang = {}
            for key, val in obj.items():
                if not isinstance(val, str):
                    self.error("Q3", f"翻译值不是字符串：{val!r}", self.rel(path), key)
                    continue
                if not val.strip():
                    self.warn("Q3", "翻译值为空", self.rel(path), key)
                lang[key] = val
            self.lang[code] = lang
        if "zh_cn" not in self.lang or "en_us" not in self.lang:
            self.error("Q3", "zh_cn / en_us 至少一个语言文件缺失或解析失败，"
                             "无法做键集合一一对应校验", self.rel(lang_dir))
            return
        zh, en = self.lang["zh_cn"], self.lang["en_us"]
        missing_en = sorted(set(zh) - set(en))
        missing_zh = sorted(set(en) - set(zh))
        for key in missing_en:
            self.error("Q3", "en_us 缺少这个键", self.rel(lang_dir / "en_us.json"), key)
        for key in missing_zh:
            self.error("Q3", "zh_cn 缺少这个键", self.rel(lang_dir / "zh_cn.json"), key)
        # 已注册内容的翻译键：BlockItem 用 block.nbw.<id>，普通物品用 item.nbw.<id>
        for block_id in sorted(self.registered_blocks):
            key = f"block.{MODID}.{block_id}"
            self._require_lang_key(key, f"已注册方块 nbw:{block_id}", lang_dir)
        for item_id in sorted(self.registered_items):
            if item_id in self.block_items:
                key = f"block.{MODID}.{item_id}"   # BlockItem 的翻译键是 block.*
            else:
                key = f"item.{MODID}.{item_id}"
            self._require_lang_key(key, f"已注册物品 nbw:{item_id}", lang_dir)
        # 占位符一致性（%1$s / %s 等），不一致会在死亡消息格式化时炸客户端
        ph_re = re.compile(r"%(?:\d+\$)?[a-zA-Z]")
        for key in sorted(set(zh) & set(en)):
            pz, pe = set(ph_re.findall(zh[key])), set(ph_re.findall(en[key]))
            if pz != pe:
                self.warn("Q3", f"中英占位符不一致：zh={sorted(pz)} en={sorted(pe)}",
                          self.rel(lang_dir), key)
        # 注册内容之外的键：可能是死亡消息/HUD 等，列 INFO 让人核对是否死键
        known = {f"block.{MODID}.{i}" for i in self.registered_blocks} | \
                {f"item.{MODID}.{i}" for i in self.registered_items if i not in self.block_items} | \
                {f"block.{MODID}.{i}" for i in self.block_items}
        extra = sorted((set(zh) | set(en)) - known)
        if extra:
            damage_types = {p.stem for p in (self.data).rglob("damage_type/*.json")} \
                if self.data.is_dir() else set()
            msg = f"{len(extra)} 个非注册物品/方块键：" + ", ".join(extra)
            dead = [k for k in extra if k.startswith("death.attack.")]
            if dead:
                dt = dead[0].split("death.attack.", 1)[1]
                if dt not in damage_types:
                    msg += (f"（其中 {dead[0]} 没有对应 data/**/damage_type/{dt}.json，"
                            f"仅当代码走原版 death.attack 格式化时才可能是死键）")
            self.info("Q3", msg)
        self.notes.append(f"Q3 zh_cn={len(zh)} 键 / en_us={len(en)} 键；"
                          f"已注册方块 {len(self.registered_blocks)} / 物品 {len(self.registered_items)}")

    def _require_lang_key(self, key: str, what: str, lang_dir: Path) -> None:
        for code, lang in self.lang.items():
            if key not in lang:
                self.error("Q3", f"{what} 缺少翻译键 {key}", self.rel(lang_dir / f"{code}.json"), key)

    # =======================================================================
    # Q4 配方
    # =======================================================================
    def check_q4(self) -> None:
        recipe_dirs = [self.data / MODID / "recipe", self.data / "minecraft" / "recipe"]
        recipe_results: set[str] = set()
        crafted_items: set[str] = set()
        total = 0
        barrier_files: list[Path] = []
        for rdir in recipe_dirs:
            if not rdir.is_dir():
                self.info("Q4", f"配方目录不存在：{self.rel(rdir)}")
                continue
            for path in sorted(rdir.glob("*.json")):
                total += 1
                obj = self.load_json(path, "Q4")
                if not isinstance(obj, dict):
                    continue
                rtype = obj.get("type")
                if not isinstance(rtype, str):
                    self.error("Q4", "配方缺少字符串 type", self.rel(path), "type")
                elif rtype not in VANILLA_RECIPE_TYPES:
                    self.warn("Q4", f"未知配方类型 {rtype!r}（可能是 typo；未知类型只告警不判错）",
                              self.rel(path), "type")
                # result
                result = obj.get("result")
                result_ids = self._collect_ids(result)
                if not result_ids:
                    self.warn("Q4", "配方没有可解析的 result id", self.rel(path), "result")
                for rid in result_ids:
                    self._check_ref_id("Q4", rid, "result", path)
                    ns, _ = self._split_id(rid)
                    if ns == MODID:
                        recipe_results.add(rid.split(":", 1)[1])
                # ingredients（shapeless/shaped/smelting...）
                for idv in self._collect_ids(obj.get("ingredients"), keys=("item", "id", "tag")):
                    self._check_ref_id("Q4", idv, "ingredients", path)
                for field_name in ("template", "base", "addition", "cooking", "key"):
                    for idv in self._collect_ids(obj.get(field_name), keys=("item", "id", "tag")):
                        self._check_ref_id("Q4", idv, field_name, path)
                # 原版配方移除护栏（data/minecraft）：必须是 barrier 不可达配方
                if path.parent.name == "minecraft" or "minecraft/recipe/" in path.as_posix():
                    barrier_files.append(path)
                    self._check_barrier_recipe(path, obj)
                if isinstance(obj.get("result"), dict):
                    count = obj["result"].get("count", 1)
                    if not isinstance(count, int) or count <= 0:
                        self.warn("Q4", f"result.count 不是正整数：{count!r}", self.rel(path),
                                  "result.count")
        # 配方产出物必须在注册表里（nbw:*）
        for rid in sorted(recipe_results):
            if rid not in self.registered_items:
                self.error("Q4", f"配方产出了未注册的物品 nbw:{rid}", self.rel(
                    self.data / MODID / "recipe"))
        for item in recipe_results:
            crafted_items.add(item)
        if total == 0:
            self.warn("Q4", "没有扫描到任何配方文件")
        if barrier_files:
            expected = 15
            if len(barrier_files) != expected:
                self.warn("Q4", f"data/minecraft/recipe 覆盖配方 {len(barrier_files)} 个，"
                                f"文档（M2-RESULTS.md:1175 / M6 交接）写的是 {expected} 个，需核对",
                          self.rel(barrier_files[0].parent))
        # 物品获取路径分析（脚本只能给证据，最终口径需人工裁决）
        # 注意：data/nbw/loot_table/*.json 当前没被任何代码引用（见 Q5），
        # 所以「只出现在 loot table 里」不算实际可达来源，单独标出来。
        sources = self._collect_item_sources()
        no_recipe = sorted(self.registered_items - crafted_items)
        unreachable: list[str] = []
        for item_id in no_recipe:
            src = sources.get(item_id, {})
            live = sorted(src.get("live", []))
            dead = sorted(src.get("dead", []))
            if live:
                dead_hint = f"（另有仅存在于未引用 loot table 的来源：{'；'.join(dead)}）" if dead else ""
                self.info("Q4", f"nbw:{item_id} 无合成配方，但有实际可达来源：{'；'.join(live)}"
                                + dead_hint)
            elif dead:
                unreachable.append(item_id)
                self.warn("Q4", f"nbw:{item_id} 无配方；唯一「来源」是未被代码引用的 loot table"
                                f"（{'；'.join(dead)}），当前实际不可获得（需人工裁决）")
            else:
                unreachable.append(item_id)
                if item_id in SYSTEM_ONLY_ITEMS:
                    self.info("Q4", f"nbw:{item_id} 无配方也无局内来源 —— 已声明为「仅系统放置」："
                                    f"{SYSTEM_ONLY_ITEMS[item_id]}")
                else:
                    self.warn("Q4", f"nbw:{item_id} 既无配方、也未在代码/资源里找到任何获取路径"
                                    f"（需人工裁决：是「明确不可合成/仅系统放置」还是漏了来源）")
        # 4) 实际可达掉落池里的 id 必须都已注册（否则补给箱会开出空槽/AIR）
        for item_id, why in self._collect_live_loot_ids():
            if item_id not in self.registered_items:
                self.error("Q4", f"补给箱掉落池引用了未注册的物品 nbw:{item_id}（{why}）；"
                                 f"运行时会开出空物品", self.rel(
                                     self.java / "dev" / "nbw" / "domain" / "loot" / "LootRoll.java"))
        self.notes.append(
            f"Q4 扫描 {total} 个配方；自定义配方产出 nbw 物品 {len(recipe_results)} 种；"
            f"注册物品 {len(self.registered_items)} 种中无配方 {len(no_recipe)} 种")
        # 原版武器配方护栏摘要
        self.info("Q4", f"原版武器配方移除护栏：data/minecraft/recipe 共 {len(barrier_files)} 个"
                        f" barrier 覆盖配方（应为 15）")

    def _collect_live_loot_ids(self) -> list[tuple[str, str]]:
        """补给箱真正会开出的 nbw 物品 id（LootRoll 池 + 悬赏奖励），用于注册表校验。"""
        found: dict[str, str] = {}
        lootroll = self.java / "dev" / "nbw" / "domain" / "loot" / "LootRoll.java"
        text = self.read_text(lootroll) if lootroll.is_file() else None
        if text:
            for item in re.findall(r'new Item\("nbw:([a-z0-9_]+)"', text):
                found.setdefault(item, "LootRoll 稀有度池")
        events = self.java / "dev" / "nbw" / "round" / "EventService.java"
        etext = self.read_text(events) if events.is_file() else None
        if etext:
            for arr in re.findall(r'EPIC_WEAPONS\s*=\s*\{([^}]*)\}', etext):
                for wid in re.findall(r'"([a-z0-9_]+)"', arr):
                    found.setdefault(wid, "悬赏奖励 EPIC_WEAPONS")
        return sorted(found.items())

    def _check_barrier_recipe(self, path: Path, obj: dict) -> None:
        """data/minecraft/recipe 的覆盖配方必须是「barrier -> barrier」不可达配方。

        护栏目的：把原版武器合成改成不可达，而不是改出可用配方。若有人把它改活，
        这条护栏就失效了 —— 这种错误不会崩服，只会在局内悄悄给回原版武器。
        """
        ids: list[str] = []
        result = obj.get("result")
        if isinstance(result, dict):
            rid = result.get("id") or result.get("item")
            ids.extend([rid] if isinstance(rid, str) else [])
        for field_name in ("ingredients", "template", "base", "addition"):
            ids.extend(self._collect_ids(obj.get(field_name), keys=("item", "id", "tag")))
        bad = [i for i in ids if i != "minecraft:barrier"]
        if bad:
            self.warn("Q4", f"原版配方覆盖文件里出现非 barrier 引用 {bad}；"
                            f"护栏可能被改活（原版武器又能合成了）", self.rel(path))
        if not ids:
            self.warn("Q4", "原版配方覆盖文件没有任何可解析的物品引用", self.rel(path))

    def _loot_table_code_refs(self) -> dict[str, list[str]]:
        """扫 src/main/java，找出对 loot_table 的引用（table 文件名 -> 引用位置）。

        当前项目 0 引用：补给箱内容由 LootRoll 直接生成。这里不写死结论，
        将来有人把 loot table 接回去，Q4/Q5 的「不可达来源」判断会自动更新。
        """
        cache = getattr(self, "_loot_refs_cache", None)
        if cache is not None:
            return cache
        result: dict[str, list[str]] = {}
        loot_dir = self.data / MODID / "loot_table"
        stems = [p.stem for p in sorted(loot_dir.glob("*.json"))] if loot_dir.is_dir() else []
        if stems and self.java.is_dir():
            for path in sorted(self.java.rglob("*.java")):
                text = self.read_text(path)
                if not text:
                    continue
                for stem in stems:
                    if re.search(r'"' + re.escape(stem) + r'"', text) or \
                            re.search(r'loot_table[^\n]*' + re.escape(stem) + r'\b', text):
                        result.setdefault(stem, []).append(self.rel(path))
        self._loot_refs_cache = result
        return result

    def _collect_item_sources(self) -> dict[str, dict[str, list[str]]]:
        """扫描 Java 源码里把 nbw 物品塞进玩家背包/箱子的路径（仅作为证据，不判对错）。

        返回 {item_id: {"live": [...], "dead": [...]}}：
        - live = 代码里实际执行的发放/生产/开局物资箱/配方随机池
        - dead = 只写在 data/nbw/loot_table/*.json 里、但没有任何代码引用的表
        """
        sources: dict[str, dict[str, list[str]]] = {}

        def note(item: str, kind: str, why: str, file: str) -> None:
            bucket = sources.setdefault(item, {"live": [], "dead": []})
            entry = f"{why}（{file}）"
            if entry not in bucket[kind]:
                bucket[kind].append(entry)

        # 1) LootRoll 稀有度池（补给箱实际用的就是它）
        lootroll = self.java / "dev" / "nbw" / "domain" / "loot" / "LootRoll.java"
        text = self.read_text(lootroll) if lootroll.is_file() else None
        if text:
            for item in re.findall(r'new Item\("nbw:([a-z0-9_]+)"', text):
                note(item, "live", "补给箱稀有度池 LootRoll", self.rel(lootroll))
        # 2) 战利品表：被代码引用才算可达
        loot_refs = self._loot_table_code_refs()
        loot_dir = self.data / MODID / "loot_table"
        if loot_dir.is_dir():
            for path in sorted(loot_dir.glob("*.json")):
                obj = self.load_json(path, "Q4")
                kind = "live" if path.stem in loot_refs else "dead"
                for name in self._collect_names(obj):
                    if name.startswith("nbw:"):
                        note(name.split(":", 1)[1], kind,
                             f"战利品表 {path.stem}", self.rel(path))
        # 3) 代码里的 new ItemStack(NbwContent.<field>) / NbwWeapons.item("id")
        content = self.java / "dev" / "nbw" / "content" / "NbwContent.java"
        ctext = self.read_text(content) if content.is_file() else None
        field_to_id: dict[str, str] = {}
        if ctext:
            consts = dict(re.findall(
                r'public static final String\s+(\w+)\s*=\s*"([^"]+)"\s*;', ctext))
            for m in re.finditer(
                    r'(\w+)\s*=\s*Registry\.register\(Registries\.ITEM,\s*'
                    r'Identifier\.of\(NBW\.MOD_ID,\s*([A-Z][A-Z0-9_]*)\)', ctext):
                field_map = consts.get(m.group(2))
                if field_map:
                    field_to_id[m.group(1)] = field_map
        for path in sorted(self.java.rglob("*.java")) if self.java.is_dir() else []:
            text = self.read_text(path)
            if not text:
                continue
            rel = self.rel(path)
            for field in re.findall(r'new ItemStack\(NbwContent\.(\w+)', text):
                item = field_to_id.get(field)
                if item:
                    note(item, "live", "代码发放/生产", rel)
            for item in re.findall(r'NbwWeapons\.item\("([a-z0-9_]+)"\)', text):
                note(item, "live", "代码发放", rel)
            for item in re.findall(r'EPIC_WEAPONS\s*=\s*\{([^}]*)\}', text):
                for wid in re.findall(r'"([a-z0-9_]+)"', item):
                    note(wid, "live", "悬赏奖励 EPIC_WEAPONS", rel)
        return sources


    # =======================================================================
    # Q5 战利品表
    # =======================================================================
    def check_q5(self) -> None:
        loot_dir = self.data / MODID / "loot_table"
        if not loot_dir.is_dir():
            # 2026-11-21：4 张死表已删除（内容由 domain/loot/LootRoll 生成，见 M3-RESULTS 与
            # M6-Q-RESOURCES §6.1）。这里从 WARN 降为 INFO：目录不存在是当前正确状态。
            self.info("Q5", "本仓库没有战利品表目录（补给箱内容由 LootRoll 稀有度池生成，"
                            "不再走原版 loot table）", self.rel(loot_dir))
            return
        tables = sorted(loot_dir.glob("*.json"))
        refs: dict[str, list[str]] = {}
        for path in tables:
            obj = self.load_json(path, "Q5")
            if not isinstance(obj, dict):
                continue
            if not isinstance(obj.get("type"), str):
                self.warn("Q5", "战利品表缺少字符串 type（默认 minecraft:generic 也能跑，"
                                "但补给箱用 chest 类型更合适）", self.rel(path), "type")
            pools = obj.get("pools")
            if pools is not None and not isinstance(pools, list):
                self.error("Q5", "pools 不是数组", self.rel(path), "pools")
                continue
            for pi, pool in enumerate(pools or []):
                if not isinstance(pool, dict):
                    self.error("Q5", "pool 不是对象", self.rel(path), f"pools[{pi}]")
                    continue
                rolls = pool.get("rolls")
                if not isinstance(rolls, (int, float, dict)):
                    self.warn("Q5", f"pools[{pi}].rolls 不是数字/对象提供器：{rolls!r}",
                              self.rel(path), f"pools[{pi}].rolls")
                entries = pool.get("entries")
                if not isinstance(entries, list) or not entries:
                    self.error("Q5", "pool 没有 entries 数组（空池会刷不出任何东西）",
                               self.rel(path), f"pools[{pi}].entries")
                    continue
                self._walk_loot_entries(path, f"pools[{pi}].entries", entries, refs)
            # 顺带把表里所有 nbw: 物品名交给 Q5 的注册表校验
        # 表与表之间的嵌套引用
        for target, wheres in sorted(refs.items()):
            if target.startswith(MODID + ":"):
                tp = loot_dir / (target.split(":", 1)[1] + ".json")
                if not tp.is_file():
                    for where in wheres:
                        self.error("Q5", f"嵌套引用的战利品表不存在：{target}",
                                   where.split(" @ ")[0], where.split(" @ ")[1])
        # 代码/资源里谁引用了这些表
        java_refs = self._loot_table_code_refs()
        unreferenced = [p for p in tables if p.stem not in java_refs]
        if unreferenced:
            self.warn(
                "Q5",
                "以下战利品表在 src/main/java 里没有任何引用（当前补给箱内容其实由 "
                "LootRoll 稀有度池直接生成，不是原版 loot table）："
                + ", ".join(self.rel(p) for p in unreferenced)
                + "；docs/dev/M3-RESULTS.md:53 仍写 setLootTable(nbw:sea_crate)，属文档/实现漂移，"
                  "需人工裁决「接回去还是删掉」")
        self.notes.append(f"Q5 扫描 {len(tables)} 张战利品表；代码引用 {len(java_refs)} 张"
                          + (f"（{sorted(java_refs)}）" if java_refs else "（0 张，全部为死资源）"))

    def _walk_loot_entries(self, path: Path, loc: str, entries: list, refs: dict[str, list[str]]) -> None:
        for i, entry in enumerate(entries):
            eloc = f"{loc}[{i}]"
            if not isinstance(entry, dict):
                self.error("Q5", "entry 不是对象", self.rel(path), eloc)
                continue
            etype = entry.get("type")
            if not isinstance(etype, str):
                self.error("Q5", "entry 缺少字符串 type", self.rel(path), eloc + ".type")
            elif etype not in KNOWN_LOOT_ENTRY_TYPES:
                self.warn("Q5", f"未知 loot entry type {etype!r}（可能 typo）",
                          self.rel(path), eloc + ".type")
            name = entry.get("name")
            if isinstance(name, str):
                self._check_ref_id("Q5", name, eloc + ".name", path)
                if etype == "minecraft:loot_table":
                    refs.setdefault(name, []).append(f"{self.rel(path)} @ {eloc}")
            for nested_key in ("children", "entries"):
                nested = entry.get(nested_key)
                if isinstance(nested, list):
                    self._walk_loot_entries(path, f"{eloc}.{nested_key}", nested, refs)
            # functions 的 id 校验
            funcs = entry.get("functions")
            for fi, func in enumerate(funcs if isinstance(funcs, list) else []):
                if not isinstance(func, dict):
                    continue
                fid = func.get("function")
                if isinstance(fid, str) and not fid.startswith("minecraft:"):
                    self.warn("Q5", f"非原版战利品函数 {fid!r}（本仓库没有注册自定义函数的代码，"
                                    f"疑似 typo）", self.rel(path), f"{eloc}.functions[{fi}]")

    # =======================================================================
    # Q6 标签
    # =======================================================================
    def check_q6(self) -> None:
        tag_files: list[Path] = []
        if self.data.is_dir():
            for pattern in ("tags/**/*.json", "tag/**/*.json"):
                tag_files.extend(p for p in sorted(self.data.rglob(pattern))
                                 if p not in tag_files)
        if not tag_files:
            self.notes.append("Q6 未发现任何标签 JSON（data/**/tags/**）")
            tool_blocks = sorted(self.requires_tool)
            self.warn(
                "Q6",
                f"项目没有任何自定义方块/物品标签。但已注册的 {len(self.registered_blocks)} 个方块里"
                f" {len(tool_blocks)} 个用了 .requiresTool()（" + ", ".join(tool_blocks) + "），"
                "却没有 #minecraft:mineable/* 标签：按原版 harvest 规则，生存模式下任何工具都收不到"
                "掉落（破方块=物品消失）。"
                + SURVIVAL_PHASE_NOTE
                + " 设施方块是否该可拆回属人工裁决；若「不该拆回」则可忽略本条。"
                  "（Sable sub-level 上的破坏是否绕过原版 harvest 判定无法静态验证，需实机确认）"
            )
            return
        registry_map = {
            "block": (self.registered_blocks, "方块"),
            "item": (self.registered_items, "物品"),
            "entity_type": (set(), "实体类型"),
            "fluid": (set(), "流体"),
            "game_event": (set(), "游戏事件"),
            "damage_type": (set(), "伤害类型"),
        }
        total = 0
        for path in tag_files:
            rel = self.rel(path)
            parts = path.relative_to(self.data).parts   # (ns, tags|tag, registry, ...)
            if len(parts) < 3:
                continue
            registry_dir = parts[2]                     # tags/ 后第一个目录 = 注册表名
            known, human = registry_map.get(registry_dir, (None, registry_dir))
            obj = self.load_json(path, "Q6")
            if not isinstance(obj, dict):
                continue
            if "values" not in obj:
                self.error("Q6", "tag 文件缺少 values 数组", rel)
                continue
            values = obj["values"]
            if not isinstance(values, list):
                self.error("Q6", "values 不是数组", rel, "values")
                continue
            total += 1
            if known is None:
                self.info("Q6", f"未知标签注册表目录 {registry_dir!r}，跳过 id 校验", rel)
                continue
            for vidx, entry in enumerate(values):
                loc = f"values[{vidx}]"
                if isinstance(entry, dict):
                    entry_id = entry.get("id")
                    required = entry.get("required")
                    if not isinstance(required, bool) and required is not None:
                        self.warn("Q6", "tag 条目 required 不是布尔", rel, loc + ".required")
                else:
                    entry_id = entry
                if not isinstance(entry_id, str):
                    self.error("Q6", "tag 条目 id 不是字符串", rel, loc)
                    continue
                if entry_id.startswith("#"):
                    target_ns, target_path = self._split_id(entry_id[1:], default_ns="minecraft")
                    if target_ns == MODID:
                        target_file = (self.data / target_ns / "tags" / registry_dir /
                                       (target_path + ".json"))
                        if not target_file.is_file():
                            self.error("Q6", f"嵌套引用的标签不存在：{entry_id}", rel, loc)
                    else:
                        self.skip("Q6", f"嵌套标签 {entry_id}（非 nbw）", rel, loc)
                    continue
                entry_ns, entry_path = self._split_id(entry_id, default_ns="minecraft")
                if entry_ns != MODID:
                    self.skip("Q6", f"原版/外部条目 {entry_id}", rel, loc)
                    continue
                if entry_path not in known:
                    self.error("Q6", f"tag 引用了未注册的{human} nbw:{entry_path}", rel, loc)
        self.notes.append(f"Q6 扫描 {len(tag_files)} 个标签文件（{total} 个可解析）；"
                          f"非 nbw 条目引用记为 OOS")

    # =======================================================================
    # Q7 物理属性
    # =======================================================================
    def check_q7(self) -> None:
        files = sorted(self.data.rglob("physics_block_properties/*.json")) \
            if self.data.is_dir() else []
        if not files:
            self.warn("Q7", "未发现任何 physics_block_properties JSON")
            return
        total = 0
        for path in files:
            rel = self.rel(path)
            obj = self.load_json(path, "Q7")
            if not isinstance(obj, dict):
                continue
            total += 1
            selector = obj.get("selector")
            if not isinstance(selector, str):
                self.error("Q7", "缺少字符串 selector", rel, "selector")
            else:
                self._check_physics_selector(path, selector)
                self._check_physics_overrides(path, selector, obj.get("overrides"))
            priority = obj.get("priority")
            if priority is not None and (not isinstance(priority, int) or isinstance(priority, bool)):
                self.error("Q7", f"priority 必须是整数（缺省 1000），收到 {priority!r}",
                           rel, "priority")
            self._check_physics_properties(path, "properties", obj.get("properties"))
            extras = set(obj) - {"selector", "priority", "properties", "overrides"}
            if extras:
                self.warn("Q7", f"物理属性文件有未识别字段 {sorted(extras)}（拼错会被 codec 静默忽略）",
                          rel)
        self.notes.append(f"Q7 扫描 {total} 个物理属性定义文件")

    def _check_physics_selector(self, path: Path, selector: str) -> None:
        rel = self.rel(path)
        if selector.startswith("#"):
            ns, tag_path = self._split_id(selector[1:], default_ns="minecraft")
            if ns == MODID:
                target = self.data / MODID / "tags" / "block" / (tag_path + ".json")
                if not target.is_file():
                    self.error("Q7", f"selector 指向的方块标签不存在：{selector}"
                                     f"（应为 data/{MODID}/tags/block/{tag_path}.json；"
                                     f"未知标签时 Sable 只打 error 日志，物理属性不生效）",
                               rel, "selector")
            else:
                self.skip("Q7", f"selector 用非 nbw 标签 {selector}", rel, "selector")
        else:
            ns, block_path = self._split_id(selector, default_ns="minecraft")
            if ns == MODID:
                if block_path not in self.registered_blocks:
                    self.error("Q7", f"selector 指向未注册方块 nbw:{block_path}",
                               rel, "selector")
            else:
                self.skip("Q7", f"selector 用非 nbw 方块 {selector}", rel, "selector")

    def _check_physics_overrides(self, path: Path, selector: str, overrides) -> None:
        if overrides is None:
            return
        rel = self.rel(path)
        if not isinstance(overrides, dict):
            self.error("Q7", "overrides 不是对象", rel, "overrides")
            return
        props = self._physics_selector_props(selector)
        for cond, sub in overrides.items():
            loc = f"overrides[{cond}]"
            if not isinstance(sub, dict):
                self.error("Q7", "override 值不是属性对象", rel, loc)
                continue
            self._check_physics_properties(path, loc, sub)
            if props is not None:
                prop_map = {name: values for name, values in props}
                for part in str(cond).split(","):
                    if "=" not in part:
                        self.error("Q7", f"override 条件 {cond!r} 不是 prop=value 形式", rel, loc)
                        break
                    k, v = part.split("=", 1)
                    if k.strip() not in prop_map:
                        self.error("Q7", f"override 条件用了方块没有的属性 {k.strip()!r}"
                                         f"（该方块属性：{sorted(prop_map) or '无'}）", rel, loc)
                    elif prop_map[k.strip()] and v.strip() not in prop_map[k.strip()]:
                        self.error("Q7", f"override 条件 {k.strip()}={v.strip()!r} 不是合法状态值",
                                   rel, loc)

    def _physics_selector_props(self, selector: str):
        if selector.startswith("#"):
            return None
        ns, block_path = self._split_id(selector, default_ns="minecraft")
        if ns != MODID:
            return None
        return self.extract_block_properties().get(block_path)

    def _check_physics_properties(self, path: Path, loc: str, props) -> None:
        rel = self.rel(path)
        if props is None:
            self.error("Q7", f"{loc} 缺失（Sable codec 要求 properties 对象）", rel, loc)
            return
        if not isinstance(props, dict):
            self.error("Q7", f"{loc} 不是对象", rel, loc)
            return
        for key, val in props.items():
            vloc = f"{loc}.{key}"
            kind = SABLE_PHYSICS_PROPERTIES.get(key)
            if kind is None:
                self.warn("Q7", f"未知物理属性键 {key!r}（Sable 文档里的键："
                                + ", ".join(sorted(SABLE_PHYSICS_PROPERTIES)) + "；拼错会被 codec 拒绝）",
                          rel, vloc)
                continue
            if kind == "number":
                ok = isinstance(val, (int, float)) and not isinstance(val, bool)
                if not ok:
                    self.error("Q7", f"{key} 必须是数字，收到 {val!r}（codec decode 失败，"
                                     f"该定义不会生效）", rel, vloc)
            elif kind == "number3":
                ok = isinstance(val, list) and len(val) == 3 and \
                    all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in val)
                if not ok:
                    self.error("Q7", f"{key} 必须是 3 个数字的数组，收到 {val!r}", rel, vloc)
            elif kind == "bool":
                if not isinstance(val, bool):
                    self.error("Q7", f"{key} 必须是布尔，收到 {val!r}", rel, vloc)
            elif kind == "string":
                if not isinstance(val, str):
                    self.error("Q7", f"{key} 必须是字符串 id，收到 {val!r}", rel, vloc)

    # =======================================================================
    # Q8 声音
    # =======================================================================
    def check_q8(self) -> None:
        sounds_json = self.assets / "sounds.json"
        java_sound_uses: dict[str, list[str]] = {}
        for path in sorted(self.java.rglob("*.java")) if self.java.is_dir() else []:
            text = self.read_text(path)
            if not text:
                continue
            for m in re.finditer(r'\bSoundEvents\.([A-Z][A-Z0-9_]*)', text):
                java_sound_uses.setdefault(m.group(1), []).append(
                    f"{self.rel(path)}:{text[:m.start()].count(chr(10)) + 1}")
            if re.search(r'Registries\.SOUND_EVENT|SoundEvent\.of\(|Registry\.register\(\s*'
                         r'Registries\.SOUND_EVENT', text):
                self.error("Q8", "代码注册了自定义 SoundEvent，但本扫描器没有"
                                 "对应的 sounds.json 资源校验分支（需人工看）", self.rel(path))
        if not sounds_json.is_file():
            self.notes.append(
                f"Q8 无 assets/nbw/sounds.json；代码引用原版 SoundEvents "
                f"{len(java_sound_uses)} 种（{', '.join(sorted(java_sound_uses))}），按文档结论为"
                f"「无自定义声音、复用原版」")
            if not java_sound_uses:
                self.info("Q8", "项目既无自定义 sounds.json，代码里也没有任何 SoundEvents 引用")
            return
        obj = self.load_json(sounds_json, "Q8")
        if not isinstance(obj, dict):
            return
        for event, defn in obj.items():
            loc = f"[{event}]"
            if not isinstance(defn, dict):
                self.error("Q8", "声音事件定义不是对象", self.rel(sounds_json), loc)
                continue
            sounds = defn.get("sounds")
            if not isinstance(sounds, list) or not sounds:
                self.error("Q8", "声音事件缺少 sounds 数组", self.rel(sounds_json), loc)
                continue
            for si, snd in enumerate(sounds):
                sloc = f"{loc}.sounds[{si}]"
                name = snd if isinstance(snd, str) else (
                    snd.get("name") if isinstance(snd, dict) else None)
                if not isinstance(name, str):
                    self.error("Q8", "sounds 条目缺少 name", self.rel(sounds_json), sloc)
                    continue
                ns, spath = self._split_id(name, default_ns=MODID)
                if ns != MODID:
                    self.skip("Q8", f"声音引用 {name}（非 nbw）", self.rel(sounds_json), sloc)
                    continue
                ogg = self.res / "assets" / ns / "sounds" / (spath + ".ogg")
                if not ogg.is_file():
                    self.error("Q8", f"声音文件不存在：assets/{ns}/sounds/{spath}.ogg",
                               self.rel(sounds_json), sloc)
        self.notes.append(f"Q8 有自定义 sounds.json，已校验引用；代码引用 SoundEvents "
                          f"{len(java_sound_uses)} 种")

    # =======================================================================
    # 顺手：所有 JSON 可解析 / fabric.mod.json
    # =======================================================================
    def check_json_sweep(self) -> None:
        files: list[Path] = []
        for root in (self.res / "assets" / MODID, self.res / "data" / MODID,
                     self.res / "data" / "minecraft"):
            if root.is_dir():
                files.extend(sorted(root.rglob("*.json")))
        fabric = self.res / "fabric.mod.json"
        if fabric.is_file():
            files.append(fabric)
        ok = 0
        for path in files:
            if self.load_json(path, "JSON") is not None:
                ok += 1
        self.notes.append(f"JSON 全量解析：{ok}/{len(files)} 个文件通过（重复键另告警）")
        # fabric.mod.json 关键字段
        if not fabric.is_file():
            self.error("JSON", "fabric.mod.json 不存在", self.rel(fabric))
            return
        obj = self.load_json(fabric, "JSON")
        if not isinstance(obj, dict):
            return
        rel = self.rel(fabric)
        if obj.get("id") != MODID:
            self.error("JSON", f"fabric.mod.json 的 id 必须是 {MODID!r}，实际 {obj.get('id')!r}",
                       rel, "id")
        version = obj.get("version")
        if version != "${version}":
            self.error("JSON", f"fabric.mod.json 的 version 应为 ${{version}}（由 gradle 注入），"
                               f"实际 {version!r}", rel, "version")
        entrypoints = obj.get("entrypoints") or {}
        for kind in ("main", "client"):
            eps = entrypoints.get(kind) or []
            if not isinstance(eps, list) or not eps:
                self.warn("JSON", f"fabric.mod.json 缺少 {kind} 入口点", rel, f"entrypoints.{kind}")
                continue
            for ep in eps:
                cls = ep if isinstance(ep, str) else (
                    ep.get("value") if isinstance(ep, dict) else None)
                if not isinstance(cls, str):
                    self.error("JSON", f"{kind} 入口点不是字符串/对象", rel, f"entrypoints.{kind}")
                    continue
                java_file = self.java / (cls.replace(".", "/") + ".java")
                if not java_file.is_file():
                    self.error("JSON", f"{kind} 入口点类不存在：{cls}"
                                       f"（应为 {self.rel(java_file)}）", rel,
                               f"entrypoints.{kind}")
        depends = obj.get("depends") or {}
        if not isinstance(depends, dict):
            self.error("JSON", "depends 不是对象", rel, "depends")
        else:
            for required in ("fabricloader", "minecraft", "java"):
                if required not in depends:
                    self.warn("JSON", f"depends 缺少 {required}", rel, "depends")
            if depends.get("minecraft") != "1.21.1":
                self.warn("JSON", f"minecraft 依赖不是 1.21.1：{depends.get('minecraft')!r}",
                          rel, "depends.minecraft")

    # -----------------------------------------------------------------------
    # 公共小工具
    # -----------------------------------------------------------------------
    @staticmethod
    def _split_id(value: str, default_ns: str = "minecraft") -> tuple[str, str]:
        if ":" in value:
            ns, path = value.split(":", 1)
            return ns, path
        return default_ns, value

    def _check_ref_id(self, domain: str, ref, loc: str, path: Path) -> None:
        if not isinstance(ref, str):
            return
        ns, rid = self._split_id(ref, default_ns="minecraft")
        if ns != MODID:
            self.skip(domain, f"引用 {ref}（非 nbw 命名空间，无法静态验证）", self.rel(path), loc)
            return
        if rid not in self.registered_items and rid not in self.registered_blocks:
            self.error(domain, f"引用了未注册的 id nbw:{rid}", self.rel(path), loc)
        elif rid not in self.registered_items:
            # 在方块注册表里但不在物品注册表：配方/掉落要的是 Item，这是真问题
            self.error(domain, f"nbw:{rid} 只注册为方块、没有对应物品，不能作为物品/战利品使用",
                       self.rel(path), loc)

    @staticmethod
    def _collect_ids(node, keys: tuple[str, ...] = ("item", "id")) -> list[str]:
        """从任意 JSON 子树里收集物品 id 字符串（用于配方/战利品校验）。"""
        out: list[str] = []
        if isinstance(node, str):
            if ":" in node:   # 只把带命名空间的字符串当 id，避免误收 key 字符串
                out.append(node)
        elif isinstance(node, list):
            for item in node:
                out.extend(Scanner._collect_ids(item, keys))
        elif isinstance(node, dict):
            for key, val in node.items():
                if key in keys and isinstance(val, str):
                    out.append(val)
                else:
                    out.extend(Scanner._collect_ids(val, keys))
        return out

    @staticmethod
    def _collect_names(node) -> list[str]:
        out: list[str] = []
        if isinstance(node, dict):
            for key, val in node.items():
                if key in ("name", "id") and isinstance(val, str) and ":" in val:
                    out.append(val)
                else:
                    out.extend(Scanner._collect_names(val))
        elif isinstance(node, list):
            for item in node:
                out.extend(Scanner._collect_names(item))
        return out


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


def run(root: Path, verbose: bool) -> tuple[list[Finding], list[OutOfScope], list[str]]:
    sc = Scanner(root=root)
    print("=" * 78)
    print("M6 Q 域资源完整性静态扫描（Q1-Q8 + 全量 JSON 解析）")
    print(f"项目根：{root}")
    print(f"资源根：{sc.rel(sc.res)}")
    print("=" * 78)
    if not sc.res.is_dir():
        sc.error("ENV", f"资源根不存在：{sc.rel(sc.res)}")
        return sc.findings, sc.oos, sc.notes

    sc.extract_registrations()
    print(f"[REG] 已注册方块 {len(sc.registered_blocks)} 个：{sorted(sc.registered_blocks)}")
    print(f"[REG] 已注册物品 {len(sc.registered_items)} 个：{sorted(sc.registered_items)}")
    print(f"[REG] 近战武器（weapons.json）：{sc.melee_weapons}")
    print()

    steps = [
        ("Q1 模型引用的贴图存在", sc.check_q1),
        ("Q2 blockstate 指向的模型存在", sc.check_q2),
        ("Q3 语言键 zh_cn / en_us", sc.check_q3),
        ("Q4 配方 / 原版武器护栏", sc.check_q4),
        ("Q5 战利品表", sc.check_q5),
        ("Q6 标签", sc.check_q6),
        ("Q7 物理属性", sc.check_q7),
        ("Q8 声音", sc.check_q8),
        ("JSON 全量解析 / fabric.mod.json", sc.check_json_sweep),
    ]
    for title, fn in steps:
        print(f"--- {title} ---")
        fn()
        domain = title.split()[0]
        ds = [f for f in sc.findings if f.domain == domain]
        if not ds:
            print("  （无发现）")
        for f in ds:
            if verbose or f.level != Colors.INFO:
                print("  " + f.render())
        print()
    return sc.findings, sc.oos, sc.notes


def summarize(findings: list[Finding], oos: list[OutOfScope], notes: list[str],
              verbose: bool) -> int:
    print("=" * 78)
    print("OOS（静态无法验证，不计入问题）汇总：")
    by_domain: dict[str, int] = {}
    for o in oos:
        by_domain[o.domain] = by_domain.get(o.domain, 0) + 1
    if not by_domain:
        print("  （无）")
    for domain, count in sorted(by_domain.items()):
        print(f"  {domain}: {count} 条（示例：")
        shown = [o for o in oos if o.domain == domain][:3]
        for o in shown:
            where = o.file + (f" @ {o.loc}" if o.loc else "") if o.file else ""
            print(f"      {where}: {o.message}" if where else f"      {o.message}")
        if count > 3:
            print(f"      ... 其余 {count - 3} 条同类")
        print("  ）")
    print()
    print("扫描统计：")
    for note in notes:
        print("  - " + note)
    print()

    # 按域给结论（固定顺序，PASS 的域也要出现，报告才好逐条填）
    verdict: dict[str, str] = {}
    domain_order = ["REG", "Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "JSON"]
    domains = sorted({f.domain for f in findings},
                     key=lambda d: domain_order.index(d) if d in domain_order else 99)
    for domain in domain_order:
        if domain not in domains:
            domains.append(domain)
    for domain in domains:
        levels = {f.level for f in findings if f.domain == domain}
        if Colors.ERROR in levels:
            verdict[domain] = "FAIL"
        elif Colors.WARN in levels:
            verdict[domain] = "PASS-with-WARN"
        else:
            verdict[domain] = "PASS"
    print("分域结论：")
    for domain in sorted(verdict):
        counts = {level: sum(1 for f in findings if f.domain == domain and f.level == level)
                  for level in (Colors.ERROR, Colors.WARN, Colors.INFO)}
        print(f"  {domain}: {verdict[domain]}  "
              f"(ERROR={counts[Colors.ERROR]}, WARN={counts[Colors.WARN]}, INFO={counts[Colors.INFO]})")
    print()
    errors = [f for f in findings if f.level == Colors.ERROR]
    warns = [f for f in findings if f.level == Colors.WARN]
    infos = [f for f in findings if f.level == Colors.INFO]
    print(f"总计：ERROR={len(errors)}  WARN={len(warns)}  INFO={len(infos)}  "
          f"OOS={len(oos)}")
    if errors:
        print("RESULT: FAIL —— 有 ERROR，见上文 [ERROR] 行")
        return 1
    if warns:
        print("RESULT: PASS（有 WARN 需人工裁决；加 --strict 可让 WARN 也算失败）")
        return 0
    print("RESULT: PASS —— 无 ERROR/WARN")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="M6 Q 域资源完整性静态扫描")
    parser.add_argument("--root", default=None,
                        help="项目根目录（默认取本脚本所在目录的上一级）")
    parser.add_argument("--strict", action="store_true",
                        help="有 WARN 也返回退出码 1")
    parser.add_argument("--verbose", action="store_true",
                        help="INFO 级发现也逐条打印")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve() if args.root else \
        Path(__file__).resolve().parent.parent
    findings, oos, notes = run(root, args.verbose)
    code = summarize(findings, oos, notes, args.verbose)
    if args.strict and code == 0 and any(f.level == Colors.WARN for f in findings):
        print("RESULT[--strict]: FAIL —— 存在 WARN")
        return 1
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
