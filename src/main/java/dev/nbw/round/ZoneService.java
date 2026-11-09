package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.domain.ZoneModel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 黑海圈层的运行时（M3-1，03 §4 / 02 §1）：把 {@link ZoneModel} 的纯函数
 * 应用到对局里 —— 圈外每秒伤害、收缩预告、阶段切换公告。
 *
 * <p>与纯函数的分工：{@link ZoneModel} 只管"某秒半径多少、每秒伤害多少、要不要预告"；
 * 这里只做"读秒 → 取数 → 对圈外人扣血 / 发提示"。判定不在这里，可单测的那部分全在 domain。
 *
 * <p>圈心取 {@code arena.sea.centerX/centerZ}；参赛者取 {@link RoundManager#participantIds()}，
 * 所以旁观者/大厅玩家不会被圈外伤害误伤。
 */
public final class ZoneService {

	private static ZoneModel model;
	private static int centerX;
	private static int centerZ;
	private static int lastAnnouncedStage = -1;
	private static int lastWarnSecond = -1;

	private ZoneService() {
	}

	/** PLAY 开始时登记：抓当前圈层模型与圈心。 */
	public static void begin(MinecraftServer server) {
		model = RoundManager.zoneOrNull();
		lastAnnouncedStage = -1;
		lastWarnSecond = -1;
		if (model == null) {
			NBW.LOGGER.warn("[zone] 没有圈层模型，黑海伤害不会生效");
			return;
		}
		var arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			NBW.LOGGER.warn("[zone] 没有 arena.sea，圈心未知");
			model = null;
			return;
		}
		centerX = arena.sea.centerX;
		centerZ = arena.sea.centerZ;
		NBW.LOGGER.info("[zone] 圈层开始 center=({},{}) baseRadius={} stages={} rings={}",
				centerX, centerZ, model.baseRadius(), model.stages().size(), model.rings().size());
	}

	/** PLAY 每 tick：取当前半径/伤害，做预告与圈外结算。 */
	public static void onEndTick(MinecraftServer server) {
		if (model == null) {
			return;
		}
		int ticks = RoundManager.machine().tickInPhase();
		int elapsed = ticks / 20;
		double radius = model.radiusAt(elapsed);

		int stage = currentStage(elapsed);
		if (stage > lastAnnouncedStage) {
			lastAnnouncedStage = stage;
			if (stage > 0) {
				announce(server, String.format(Locale.ROOT, "§c黑海收缩！当前半径 %.0f 格", radius));
			}
		}

		int warn = model.warnCountdownAt(elapsed);
		if (warn > 0 && warn != lastWarnSecond) {
			lastWarnSecond = warn;
			announce(server, "§e黑海将在 " + warn + " 秒后收缩");
		} else if (warn <= 0) {
			lastWarnSecond = -1;
		}

		// 可视化：每 2 秒把「当前半径 + 下次收缩倒计时」推到 actionbar（不刷聊天）
		if (ticks % 40 == 0) {
			String bar = String.format(Locale.ROOT, "§c黑海 半径 %.0f 格%s", radius,
					warn > 0 ? " §e| 下次收缩 " + warn + "s" : "");
			sendActionBar(server, bar);
		}

		double damage = model.damageAt(elapsed);
		if (ticks % 20 == 0 && damage > 0) {
			applyDamage(server, radius, damage);
		}
	}

	private static void applyDamage(MinecraftServer server, double radius, double damage) {
		ServerWorld world = server.getOverworld();
		Set<UUID> ids = RoundManager.participantIds();
		int hit = 0;
		for (UUID id : ids) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p == null || p.isDead()) {
				continue;
			}
			double d = Math.hypot(p.getX() - centerX, p.getZ() - centerZ);
			if (d > radius) {
				// 配置里 damage 的单位是 ♥（02 §2 表：0.5♥/1.0♥/2.0♥/4.0♥），1♥ = 2 HP。
				// 之前直接当 HP 扣，实测只有文档的一半（M3 审计 2026-11-08 修）。
				// 黑海涌动事件期间再 ×1.5（02 §10）。
				p.damage(world.getDamageSources().magic(),
						(float) (damage * 2.0 * EventService.zoneDamageMultiplier()));
				// 可见性：黑海里的「视野压暗」（01 §8：伤害之外还要让人厌恶黑海）
				p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
						net.minecraft.entity.effect.StatusEffects.DARKNESS, 60, 0, false, false, false));
				hit++;
			}
		}
		if (hit > 0) {
			NBW.LOGGER.debug("[zone] 圈外伤害 {} 人，{}/s", hit, damage);
		}
	}

	/** 当前处于第几段（1-based）；还没开始收缩时返回 0。 */
	private static int currentStage(int elapsed) {
		int stage = 0;
		for (ZoneModel.Stage s : model.stages()) {
			if (elapsed >= s.atSecond()) {
				stage = s.index() + 1;
			}
		}
		return stage;
	}

	private static void announce(MinecraftServer server, String msg) {
		NBW.LOGGER.info("[zone] {}", msg);   // 预留证据：探针从日志断言预告/收缩公告
		for (UUID id : RoundManager.participantIds()) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p != null) {
				p.sendMessage(Text.literal(msg), false);
			}
		}
	}

	/** actionbar（`overlay=true`）：只占屏幕底栏，不刷聊天，适合放圈半径这种常驻读数。 */
	private static void sendActionBar(MinecraftServer server, String msg) {
		for (UUID id : RoundManager.participantIds()) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p != null) {
				p.sendMessage(Text.literal(msg), true);
			}
		}
	}

	/** 一行读数：给 {@code /cww zone status} 与探针断言。 */
	public static String status() {
		if (model == null) {
			return "[zone] 未初始化";
		}
		int elapsed = RoundManager.machine().tickInPhase() / 20;
		return String.format(Locale.ROOT,
				"[zone] 进行 %ds 半径=%.1f 圈外伤害=%.2f/s 圈心=(%d,%d) 已收缩阶段=%d",
				elapsed, model.radiusAt(elapsed), model.damageAt(elapsed), centerX, centerZ,
				lastAnnouncedStage);
	}

	/** 局后清理：圈层是局内状态，跨局不许残留。 */
	public static void reset() {
		model = null;
		centerX = 0;
		centerZ = 0;
		lastAnnouncedStage = -1;
		lastWarnSecond = -1;
	}
}
