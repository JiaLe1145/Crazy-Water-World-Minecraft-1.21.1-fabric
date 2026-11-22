package dev.nbw.cfg;

import java.util.List;

/**
 * 03 文档定义的 JSON 结构（arena / format / lobby）。
 * 这里刻意用公开字段的普通类，便于 Gson 直接映射，且字段名与文档一一对应，
 * 地图作者改配置文件时能对着 03 文档逐字核对。
 */
public final class ConfigModels {

	private ConfigModels() {
	}

	public static final class Arena {
		public int schemaVersion;
		public String id;
		public String dimension;
		// ---- 地图注册表元数据（2026-11-08，参照 RicordRailExpress 的地图注册表）----
		/** 显示名（可空，缺省用 id）。 */
		public String displayName;
		/** 描述（可空）。 */
		public String description;
		/** 是否进入随机池（缺省 true）。 */
		public Boolean canSelect;
		/** 随机池权重（缺省 1；<=0 不进池）。 */
		public Integer weight;
		/** 该图允许的最少/最多玩家数（缺省：最少 0 跟随赛制，最多 -1 不限制）。 */
		public Integer minPlayers;
		public Integer maxPlayers;
		/** 支持哪些赛制（空列表 = 全部）。 */
		public List<String> formats;
		public Bounds bounds;
		public Sea sea;
		public List<RingJson> rings;
		public Spawns spawns;
		public Loot loot;
		public List<Hazard> hazards;
		public Limits limits;
		/** 模板复制式地图重置（2026-11-08，参照 RicordRailExpress 的 resetTemplateArea/resetPasteArea）。 */
		public Reset reset;

		public static final class Reset {
			/** 模板区（复原源）。 */
			public Bounds resetTemplateArea;
			/** 贴合区（复原目标）。 */
			public Bounds resetPasteArea;
			/** true = 跳过重置。 */
			public boolean noReset;
			/** true = 全量复制（本轮实现下默认就是全量，字段保留语义）。 */
			public boolean mustCopy;
		}

		public static final class Bounds {
			public int minX, minY, minZ, maxX, maxY, maxZ;
		}

		public static final class Sea {
			public int centerX, centerZ;
			public int radius;
			public int waterLevel;
			public int generateY;
		}

		public static final class RingJson {
			public double fromRadius;
			public double toRadius;
			public double lootMultiplier;
			public int tier;
		}

		public static final class Spawns {
			public List<TeamSpawn> teams;
			public Auto auto;

			public static final class TeamSpawn {
				public int teamIndex;
				public int x, y, z;
				public String facing;
			}

			public static final class Auto {
				public String strategy;
				public int minSeparation;
			}
		}

		public static final class Loot {
			public List<Exclude> exclude;
			public String candidates;
			public Prefill prefill;

			public static final class Exclude {
				public int minX, minZ, maxX, maxZ;
				public String reason;
			}

			public static final class Prefill {
				public boolean enabled;
				public int count;
				public List<Integer> radiusBand;
			}
		}

		public static final class Hazard {
			public String type;
			public int minX, minZ, maxX, maxZ;
		}

		public static final class Limits {
			public int maxRaftArea;
			public int maxBlocksPerRaft;
			public int maxFacilities;
			public List<Integer> buildYRange;
			/**
			 * 开局发给每队的初始筏<b>边长</b>（甲板铺 side×side，4 → 16 格）。
			 * <p>可选：缺省 0 表示用 {@code RaftSpawner.DEFAULT_SIDE}。
			 * 用边长而不是半径：4×4 这种偶数边长用半径表达不出来（半径 2 会铺成 5×5），
			 * 而格子具体怎么铺由 {@code domain/raft/RaftLayout} 唯一决定。
			 * <p>之所以放进 arena 而不是 format——筏子多大属于"这张图"的空间事实，
			 * 而赛制只管时间与人数（03 §2 的字段归属）。
			 */
			public int initialRaftSide;

			/**
			 * 初始筏的 X 向格数（宽度）。0 = 用 {@link #initialRaftSide}。
			 * <p>2026-11-23 用户把初始筏定为 **4x5**：宽度 4、长度 5。拆成两个字段是为了
			 * 不再正方形贯穿全线——{@code RaftLayout} 的取格、物资箱、落位格按 width/length 各取一轴。
			 */
			public int initialRaftWidth;

			/** 初始筏的 Z 向格数（长度）。0 = 宽度 + 1（即 4x5 里的 5）。 */
			public int initialRaftLength;
			/** 初始筏的材质，缺省 oak_planks。材质决定质量（#sable:light → 0.5/格）。 */
			public String initialRaftBlock;
		}
	}

	public static final class Format {
		public int schemaVersion;
		public String id;
		public int teams;
		public int teamSize;
		public int minPlayers;
		public Duration duration;
		public Border border;
		public LootJson loot;
		public EventsJson events;

		public static final class Duration {
			public List<Integer> phases;
			public int targetTotal;
		}

		public static final class Border {
			public List<StageJson> stages;

			public static final class StageJson {
				public int at;
				public double radiusScale;
				public double damage;
				public int warn;
			}
		}

		public static final class LootJson {
			public String tierBias;
			public int respawnIntervalSec;
			/** 每轮补几个箱子（02 §4：quick 3 / standard 4 / big 5）；缺省 4。 */
			public Integer respawnBatch;
			/** 同屏最大漂浮物（02 §4：quick 16 / standard 24 / big 32）；缺省 24。 */
			public Integer maxFloating;
		}

		public static final class EventsJson {
			public List<String> pool;
			public List<Integer> intervalSec;
		}
	}

	public static final class Lobby {
		public int schemaVersion;
		public String dimension;
		public Point spawn;
		public Arena.Bounds bounds;
		public Point enrollNpc;
		public List<TeamFlag> teamFlags;
		public Point spectateAnchor;
		public Rules rules;

		public static final class Point {
			public int x, y, z;
			public float yaw;
			public float pitch;
		}

		public static final class TeamFlag {
			public int teamIndex;
			public int x, y, z;
		}

		public static final class Rules {
			public boolean pvp;
			public boolean griefing;
			public boolean hunger;
			public boolean flyAllowed;
		}
	}

	/**
	 * 武器表（09 §2 + §4）的 JSON 结构。字段与 weapons.json 一一对应，
	 * 数值口径：damage 单位 HP（♥×2）、cooldownTicks 单位 tick（秒×20）、
	 * knockback 无量纲、coreMultiplier 为对筏心破拆倍率（本轮只随表下发不接判定）。
	 */
	public static final class Weapon {
		public String id;
		public String kind;
		public double damage;
		public int cooldownTicks;
		public double knockback;
		public double coreMultiplier = 1.0;
	}

	/** weapons.json 的顶层结构。 */
	public static final class WeaponsFile {
		public int schemaVersion;
		public java.util.List<Weapon> weapons;
	}
}
