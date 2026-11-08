package dev.nbw.survival;

import dev.nbw.NBW;
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
		tick = 0;
		for (UUID id : RoundManager.participantIds()) {
			states.put(id, new State(SurvivalRule.MAX, SurvivalRule.MAX));
		}
		NBW.LOGGER.info("[survival] 开局：{} 人满状态", states.size());
	}

	/** PLAY 每 tick：每秒结算一次衰减 + debuff + HUD。 */
	public static void onEndTick(MinecraftServer srv) {
		if (states.isEmpty()) {
			return;
		}
		if (++tick % 20 != 0) {
			return;
		}
		for (Map.Entry<UUID, State> e : states.entrySet()) {
			ServerPlayerEntity p = srv.getPlayerManager().getPlayer(e.getKey());
			if (p == null || p.isDead()) {
				continue;
			}
			boolean combat = srv.getTicks() - lastDamageTick.getOrDefault(e.getKey(), Integer.MIN_VALUE / 2)
					< COMBAT_WINDOW_TICKS;
			double nDecay = SurvivalRule.decay(
					combat ? SurvivalRule.NUTRITION_PER_MIN_COMBAT : SurvivalRule.NUTRITION_PER_MIN, 20);
			double hDecay = SurvivalRule.decay(SurvivalRule.HYDRATION_PER_MIN, 20);
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
			ServerPlayNetworking.send(p, new StatusPayload((float) nutrition, (float) hydration));
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
	public static String set(MinecraftServer srv, String playerName, double nutrition, double hydration) {
		ServerPlayerEntity p = srv.getPlayerManager().getPlayer(playerName);
		if (p == null) {
			return "找不到玩家 " + playerName;
		}
		states.put(p.getUuid(), new State(SurvivalRule.clamp(nutrition), SurvivalRule.clamp(hydration)));
		sendStatus(p, SurvivalRule.clamp(nutrition), SurvivalRule.clamp(hydration));
		return String.format(Locale.ROOT, "已设 %s 营养=%.1f 水分=%.1f", p.getName().getString(),
				SurvivalRule.clamp(nutrition), SurvivalRule.clamp(hydration));
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
		return String.format(Locale.ROOT, "%s 营养=%.1f(%s) 水分=%.1f(%s)｜人数=%d",
				p.getName().getString(), s.nutrition(),
				SurvivalRule.nutritionLow(s.nutrition()) ? "低" : "正常",
				s.hydration(), SurvivalRule.hydrationLow(s.hydration()) ? "低" : "正常", states.size());
	}

	/** 局后清理：状态与挨打时间都是局内的。 */
	public static void reset() {
		states.clear();
		lastDamageTick.clear();
		tick = 0;
	}
}
