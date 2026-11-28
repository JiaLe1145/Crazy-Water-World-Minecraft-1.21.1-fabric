package dev.nbw.survival;

import dev.nbw.NBW;
import dev.nbw.domain.survival.HypothermiaRule;
import dev.nbw.domain.survival.SurvivalRule;
import dev.nbw.net.StatusPayload;
import dev.nbw.round.RoundManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 营养/水分的运行时（M4-1）：每人两份 0..100 的状态，按 {@link SurvivalRule} 每秒衰减，
 * 见底时挂 debuff（营养低 → 虚弱+缓慢；水分低 → 禁冲刺），并把状态推给客户端 HUD。
 *
 * <p><b>不致命</b>：这两条到 0 只挂 debuff、不掉血（Gate G4：状态不得单独致死满装备玩家）。
 * 掉落/黑海/受伤仍由别处负责。
 *
 * <p>恢复入口（{@link #recoverNutrition}/{@link #recoverHydration}）留给 M4-2 的食物/淡水物品接；
 * 现在先由 {@code /cww survival set} 供 dev/探针用。
 */
public final class SurvivalService {

	/** 战斗判定窗口：最近 5s 内挨过打就算"战斗中"（营养衰减 1.6/min）。 */
	private static final int COMBAT_WINDOW_TICKS = 100;

	private record State(double nutrition, double hydration) {
	}

	private static final Map<UUID, State> states = new LinkedHashMap<>();
	private static final Map<UUID, Integer> lastDamageTick = new LinkedHashMap<>();
	/** 流血/骨折的到期 tick（受伤系统，M4-3）：流血 2♥/s 掉到 1 血自停；骨折 45s 禁冲刺。 */
	private static final Map<UUID, Integer> bleedingUntil = new LinkedHashMap<>();
	private static final Map<UUID, Integer> fractureUntil = new LinkedHashMap<>();
	/** 失温计时（01 §5 / 02 §8）：每秒喂一次 isWet，连续 10s 后消耗 ×1.5。 */
	private static final Map<UUID, HypothermiaRule.State> wetStates = new LinkedHashMap<>();
	private static MinecraftServer server;
	private static int tick;

	private SurvivalService() {
	}

	/** 挂伤害事件：记"最近挨打"用于战斗衰减；同时挂右键吃东西 → 营养回复。由 {@code NBW.onInitialize} 调用。 */
	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (entity instanceof ServerPlayerEntity sp && taken > 0.0f && !blocked && server != null) {
				lastDamageTick.put(sp.getUuid(), server.getTicks());
			}
		});
		// 吃原版食物 → 营养回复（M4-2 的代理；熟食/生食按饥饿点数放大 8 倍，约等于 02 的 35/15）
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
			if (!world.isClient() && player instanceof ServerPlayerEntity sp) {
				var stack = sp.getStackInHand(hand);
				if (stack.contains(net.minecraft.component.DataComponentTypes.FOOD)) {
					var food = stack.get(net.minecraft.component.DataComponentTypes.FOOD);
					if (food != null) {
						SurvivalService.recoverNutrition(sp, food.nutrition() * 8.0);
					}
				}
			}
			return net.minecraft.util.TypedActionResult.pass(player.getStackInHand(hand));
		});
	}

	/** PLAY 开始：参赛者满营养/满水分。 */
	public static void begin(MinecraftServer srv) {
		server = srv;
		states.clear();
		lastDamageTick.clear();
		bleedingUntil.clear();
		fractureUntil.clear();
		wetStates.clear();
		tick = 0;
		for (UUID id : RoundManager.participantIds()) {
			states.put(id, new State(SurvivalRule.MAX, SurvivalRule.MAX));
		}
		NBW.LOGGER.info("[survival] 开局：{} 人满状态", states.size());
	}

	/** 武器命中：弯刀→流血，铁头锤→骨折（09 §2.1 的特殊效果，M4-3）。 */
	public static void onWeaponHit(ServerPlayerEntity victim, String weaponId) {
		if (server == null || victim == null || weaponId == null) {
			return;
		}
		int now = server.getTicks();
		switch (weaponId) {
			case "cutlass" -> {
				bleedingUntil.put(victim.getUuid(), now + 200);   // 10s，期间每秒掉 2♥，掉到 1 血自停
				victim.sendMessage(net.minecraft.text.Text.literal("§c[cww] 你流血了（找绷带止血）"), true);
			}
			case "warhammer" -> {
				fractureUntil.put(victim.getUuid(), now + 900);   // 45s，禁冲刺
				victim.sendMessage(net.minecraft.text.Text.literal("§c[cww] 你骨折了（45s 无法冲刺）"), true);
			}
			default -> {
			}
		}
	}

	/** PLAY 每 tick：骨折锁冲刺（每 5 tick）；流血 + 营养/水分衰减（每秒）。 */
	public static void onEndTick(MinecraftServer srv) {
		if (states.isEmpty()) {
			return;
		}
		tick++;
		int now = srv.getTicks();
		if (tick % 5 == 0) {
			for (UUID id : states.keySet()) {
				if (fractureUntil.getOrDefault(id, 0) > now) {
					ServerPlayerEntity p = srv.getPlayerManager().getPlayer(id);
					if (p != null && !p.isSpectator()) {
						p.setSprinting(false);   // 骨折：无法冲刺；观战者不参与状态结算
					}
				}
			}
		}
		if (tick % 20 != 0) {
			return;
		}
		for (Map.Entry<UUID, State> e : states.entrySet()) {
			ServerPlayerEntity p = srv.getPlayerManager().getPlayer(e.getKey());
			if (p == null || p.isDead() || p.isSpectator()) {
				continue;   // 观战者不再吃营养/水分/流血结算
			}
			// 流血：2♥/s = 4 HP/s，掉到 1 血自停（Gate G4：不致死）
			if (bleedingUntil.getOrDefault(e.getKey(), 0) > now) {
				float hp = p.getHealth();
				if (hp > 1.0f) {
					p.setHealth(Math.max(1.0f, hp - 4.0f));
				} else {
					bleedingUntil.remove(e.getKey());
				}
			}
			boolean combat = now - lastDamageTick.getOrDefault(e.getKey(), Integer.MIN_VALUE / 2)
					< COMBAT_WINDOW_TICKS;
			// 失温（01 §5 / 02 §8）：连续湿身 10s → 营养/水分消耗 ×1.5；连续干燥 3s 解除。
			HypothermiaRule.State previousWet = wetStates.getOrDefault(e.getKey(), HypothermiaRule.State.none());
			HypothermiaRule.State wet = HypothermiaRule.tick(previousWet, p.isWet());
			wetStates.put(e.getKey(), wet);
			boolean wasHypothermic = HypothermiaRule.active(previousWet);
			boolean isHypothermic = HypothermiaRule.active(wet);
			if (isHypothermic != wasHypothermic) {
				p.sendMessage(net.minecraft.text.Text.literal(isHypothermic
						? "§b[cww] 你失温了：营养/水分消耗 ×1.5（离开水/雨 3s 解除）"
						: "§b[cww] 失温解除"), true);
			}
			double wetMultiplier = HypothermiaRule.drainMultiplier(wet);
			double nDecay = SurvivalRule.decay(
					combat ? SurvivalRule.NUTRITION_PER_MIN_COMBAT : SurvivalRule.NUTRITION_PER_MIN, 20)
					* wetMultiplier;
			double hDecay = SurvivalRule.decay(SurvivalRule.HYDRATION_PER_MIN, 20) * wetMultiplier;
			State s = e.getValue();
			double n = SurvivalRule.clamp(s.nutrition() - nDecay);
			double h = SurvivalRule.clamp(s.hydration() - hDecay);
			e.setValue(new State(n, h));
			applyEffects(p, n, h);
			sendStatus(p, n, h);
		}
	}

	private static void applyEffects(ServerPlayerEntity p, double nutrition, double hydration) {
		if (SurvivalRule.nutritionLow(nutrition)) {
			// 1.5s 一续，避免每 20 tick 断档
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 30, 0, false, false, false));
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 0, false, false, false));
		}
		if (SurvivalRule.hydrationLow(hydration)) {
			p.setSprinting(false);   // 缺水不能冲刺
		}
	}

	private static void sendStatus(ServerPlayerEntity p, double nutrition, double hydration) {
		if (ServerPlayNetworking.canSend(p, StatusPayload.ID)) {
			int now = server == null ? 0 : server.getTicks();
			boolean bleeding = bleedingUntil.getOrDefault(p.getUuid(), 0) > now;
			boolean fracture = fractureUntil.getOrDefault(p.getUuid(), 0) > now;
			boolean hypothermic = HypothermiaRule.active(wetStates.get(p.getUuid()));
			ServerPlayNetworking.send(p, new StatusPayload(true,
					(float) nutrition, (float) hydration, bleeding, fracture, hypothermic));
		}
	}

	/**
	 * 非对局阶段通知客户端收起营养/水分条（2026-11-28 用户要求：未开局看不到两条）。
	 * 只发一次，避免每 tick 广播。
	 */
	public static void hideAll(MinecraftServer srv) {
		if (srv == null) {
			return;
		}
		for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
			if (ServerPlayNetworking.canSend(p, StatusPayload.ID)) {
				ServerPlayNetworking.send(p, new StatusPayload(false, 0.0f, 0.0f,
						false, false, false));
			}
		}
	}

	/** 吃东西：营养 +amount（M4-2 的食物物品接这里）。 */
	public static void recoverNutrition(ServerPlayerEntity p, double amount) {
		State s = states.get(p.getUuid());
		if (s != null) {
			states.put(p.getUuid(), new State(SurvivalRule.clamp(s.nutrition() + amount), s.hydration()));
		}
	}

	/** 喝水：水分 +amount（M4-2 的淡水物品接这里）。 */
	public static void recoverHydration(ServerPlayerEntity p, double amount) {
		State s = states.get(p.getUuid());
		if (s != null) {
			states.put(p.getUuid(), new State(s.nutrition(), SurvivalRule.clamp(s.hydration() + amount)));
		}
	}

	/** dev/命令入口：强制设某人的两项（探针断言用）。返回一行结论。 */
	public static String set(MinecraftServer srv, String playerName, double nutrition, double hydration) {		ServerPlayerEntity p = srv.getPlayerManager().getPlayer(playerName);
		if (p == null) {
			return "找不到玩家 " + playerName;
		}
		states.put(p.getUuid(), new State(SurvivalRule.clamp(nutrition), SurvivalRule.clamp(hydration)));
		sendStatus(p, SurvivalRule.clamp(nutrition), SurvivalRule.clamp(hydration));
		return String.format(Locale.ROOT, "已设 %s 营养=%.1f 水分=%.1f", p.getName().getString(),
				SurvivalRule.clamp(nutrition), SurvivalRule.clamp(hydration));
	}

	/** 医疗箱：止血。 */
	public static void cureBleeding(ServerPlayerEntity p) {
		bleedingUntil.remove(p.getUuid());
	}

	/** 某人的营养是否见底（02 §8：营养 <20 时攀爬失败 1 次）。没有状态时按"不低"处理。 */
	public static boolean isNutritionLow(UUID id) {
		State s = states.get(id);
		return s != null && SurvivalRule.nutritionLow(s.nutrition());
	}

	/** 医疗箱：接骨。 */
	public static void cureFracture(ServerPlayerEntity p) {
		fractureUntil.remove(p.getUuid());
	}

	/** 绷带/夹板：把骨折剩余时间缩短若干秒（02 §3：夹板 −30s）。 */
	public static void reduceFracture(ServerPlayerEntity p, int seconds) {
		Integer until = fractureUntil.get(p.getUuid());
		if (until == null) {
			return;
		}
		int next = until - seconds * 20;
		if (next <= server.getTicks()) {
			fractureUntil.remove(p.getUuid());
		} else {
			fractureUntil.put(p.getUuid(), next);
		}
	}

	/** 医疗箱：治疗（不超过上限）。 */
	public static void heal(ServerPlayerEntity p, float amount) {
		p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + amount));
	}

	/** 一行读数：给 {@code /cww survival}。 */
	public static String status(MinecraftServer srv, String playerName) {
		ServerPlayerEntity p = srv.getPlayerManager().getPlayer(playerName);
		if (p == null) {
			return "找不到玩家 " + playerName;
		}
		State s = states.get(p.getUuid());
		if (s == null) {
			return String.format(Locale.ROOT, "%s 没有生存状态（未开局/已出局）", p.getName().getString());
		}
		int now = srv.getTicks();
		boolean bleeding = bleedingUntil.getOrDefault(p.getUuid(), 0) > now;
		boolean fractured = fractureUntil.getOrDefault(p.getUuid(), 0) > now;
		boolean hypothermic = HypothermiaRule.active(wetStates.get(p.getUuid()));
		return String.format(Locale.ROOT, "%s 营养=%.1f(%s) 水分=%.1f(%s) 血=%.1f 流血=%s 骨折=%s 失温=%s｜人数=%d",
				p.getName().getString(), s.nutrition(),
				SurvivalRule.nutritionLow(s.nutrition()) ? "低" : "正常",
				s.hydration(), SurvivalRule.hydrationLow(s.hydration()) ? "低" : "正常",
				p.getHealth(), bleeding ? "是" : "否", fractured ? "是" : "否",
				hypothermic ? "是" : "否", states.size());
	}

	/** 局后清理：状态与挨打时间都是局内的。 */
	public static void reset() {
		states.clear();
		lastDamageTick.clear();
		bleedingUntil.clear();
		fractureUntil.clear();
		wetStates.clear();
		tick = 0;
	}
}
