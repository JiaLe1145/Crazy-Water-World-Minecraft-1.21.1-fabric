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
		public Bounds bounds;
		public Sea sea;
		public List<RingJson> rings;
		public Spawns spawns;
		public Loot loot;
		public List<Hazard> hazards;
		public Limits limits;

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
}
