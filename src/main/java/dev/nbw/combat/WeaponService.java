package dev.nbw.combat;

import dev.nbw.NBW;
import dev.nbw.arena.BuiltinWeaponsJson;
import dev.nbw.cfg.WeaponConfig;
import dev.nbw.content.WeaponItem;
import dev.nbw.domain.combat.CombatRule;
import dev.nbw.domain.combat.CombatRule.WeaponSpec;
import dev.nbw.round.MetricsService;
import dev.nbw.round.RoundManager;
import dev.nbw.survival.SurvivalService;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PVP 近战武器的适配层（09 §4）：把玩家的一次挥击解析成 {@link CombatRule} 要的输入，
 * 再把判定结果落回世界（放行让原版结算伤害 / 取消超频挥击 + 写审计）。
 *
 * <p><b>判定不在这里。</b>攻速闸门与持有上限在 {@code domain/combat/CombatRule} 那个纯函数里，
 * 本类只做：认出是不是我们的武器、记上次有效攻击 tick、超频取消并审计、按表施加击退、只在 PLAY 生效。
 *
 * <p><b>伤害不在这里改。</b>伤害由物品的 {@code attribute_modifiers} 交给原版结算（{@code NbwWeapons}），
 * 本版本 Fabric 没有"改伤害数值"的钩子（交接 §5.2-6），所以"拦掉再补半刀"是被否掉的路子；
 * 我们这里只做<b>能不能打</b>（超频取消）与<b>打完之后记一笔</b>。伤害的权威性 = 物品属性（服务端注册）+ 攻速（服务端闸门）。
 */
public final class WeaponService {

	/** 玩家 → 上次有效攻击的 tick。超频判定就用它；键按 UUID，玩家重生不换 UUID，所以跨重生也正确。 */
	private static final Map<UUID, Long> lastValidAttack = new HashMap<>();

	/** 审计环形缓冲（最新在前），给 {@code /cww weapon status} 与探针取证用。 */
	private static final Deque<String> audit = new ArrayDeque<>();
	private static final int AUDIT_KEEP = 32;

	/** 累计计数：放行与超频拒绝（跨局运维量，故意不清）。 */
	private static long accepted;
	private static long rejectedOverSpeed;

	/** 当前已加载的武器表（内置默认 + world 覆盖；{@code /cww weapon reload} 会重读）。 */
	private static WeaponConfig.Loaded loaded = new WeaponConfig.Loaded(Map.of(), List.of("未加载"));

	private WeaponService() {
	}

	/** 挂攻击事件并做首次装载；由 {@code NBW.onInitialize} 调用一次。 */
	public static void register() {
		AttackEntityCallback.EVENT.register(WeaponService::onAttack);
		String first = reloadWeapons();
		NBW.LOGGER.info("[combat] 近战武器已挂载：{}", first);
	}

	/** 重读武器表（内置回退 + world 覆盖）。成功返回一行结论，失败返回原因（不抛、不崩）。 */
	public static String reloadWeapons() {
		loaded = WeaponConfig.load(RoundManager.worldRoot(), BuiltinWeaponsJson.SOURCE);
		if (loaded.ok()) {
			return "武器表已加载 " + loaded.specs().size() + " 件";
		}
		return "武器表加载失败：" + String.join(" | ", loaded.errors());
	}

	/** 当前武器表（只读）。 */
	public static WeaponConfig.Loaded current() {
		return loaded;
	}

	// ---------------------------------------------------------------- 事件入口

	private static ActionResult onAttack(PlayerEntity player, World world, Hand hand,
			Entity target, EntityHitResult hit) {
		if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.PASS;
		}
		// 只在 PLAY 生效：非 PLAY 阶段挥击不该触发攻速闸门/击退/审计（PVP 本身已由 RoundManager 闸掉）。
		if (!RoundManager.phase().allowsPvp()) {
			return ActionResult.PASS;
		}
		ItemStack stack = sp.getStackInHand(hand);
		if (!(stack.getItem() instanceof WeaponItem wi)) {
			return ActionResult.PASS;   // 手上不是我们的武器，不归武器规则管
		}
		WeaponSpec spec = loaded.spec(wi.weaponId());
		if (spec == null) {
			return ActionResult.PASS;   // 注册了但表里没配（world 覆盖把它删了）：当普通物品
		}

		long now = sw.getServer().getTicks();
		Long last = lastValidAttack.get(sp.getUuid());
		String reason = CombatRule.cooldownViolation(last == null ? -1L : last, now, spec.cooldownTicks());
		if (reason != null) {
			rejectedOverSpeed++;
			record(now, sp, target, wi.weaponId(), reason);
			return ActionResult.FAIL;   // 超频：取消这次挥击，原版不再结算伤害
		}

		lastValidAttack.put(sp.getUuid(), now);
		accepted++;
		if (target instanceof LivingEntity living) {
			applyKnockback(sp, living, spec.knockback());
			if (living instanceof ServerPlayerEntity victim) {
				MetricsService.onPvpDamage(sp, victim);   // 埋点：首战时刻（M3-3）
				SurvivalService.onWeaponHit(victim, wi.weaponId());   // 弯刀→流血 / 铁头锤→骨折（M4-3）
				CombatStats.onDamage(sp, victim, spec.damage());   // 战绩：造成伤害（M5-3）
			}
		}
		record(now, sp, target, wi.weaponId(), null);
		return ActionResult.PASS;   // 交给原版按物品属性结算伤害
	}

	private static void applyKnockback(ServerPlayerEntity attacker, LivingEntity target, double strength) {
		if (strength <= 0.0) {
			return;
		}
		double dx = target.getX() - attacker.getX();
		double dz = target.getZ() - attacker.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1e-4) {
			dx = 0.0;
			dz = 1.0;
		} else {
			dx /= len;
			dz /= len;
		}
		target.takeKnockback(strength, dx, dz);
	}

	// ---------------------------------------------------------------- 反馈与观测

	private static void record(long tick, ServerPlayerEntity attacker, Entity target,
			String weaponId, String reason) {
		String line = String.format(Locale.ROOT, "t=%d %s → %s 武器=%s → %s",
				tick, attacker.getName().getString(), target == null ? "?" : target.getName().getString(),
				weaponId, reason == null ? "允许" : "拒绝：" + reason);
		while (audit.size() >= AUDIT_KEEP) {
			audit.removeLast();
		}
		audit.addFirst(line);
		if (reason == null) {
			NBW.LOGGER.info("[combat] {}", line);
		} else {
			NBW.LOGGER.warn("[combat] {}", line);
		}
	}

	/** 最近若干次判定，给 {@code /cww weapon status} 与探针断言"超频确实被拒并留痕"。 */
	public static List<String> recent() {
		return new ArrayList<>(audit);
	}

	/** 累计计数 + 当前表，给运维与探针。 */
	public static String status() {
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
				"[combat] 放行=%d 超频拒绝=%d ｜ 武器=%d 件", accepted, rejectedOverSpeed, loaded.specs().size()));
		for (WeaponSpec s : loaded.specs()) {
			sb.append(String.format(Locale.ROOT, "｜%s 伤害=%.1fHP 冷却=%dtick 击退=%.1f",
					s.id(), s.damage(), s.cooldownTicks(), s.knockback()));
		}
		return sb.toString();
	}

	/** 持有近战武器的件数（give 命令的持有上限前置）。 */
	public static int meleeHeld(PlayerEntity player) {
		int n = 0;
		for (int i = 0; i < player.getInventory().size(); i++) {
			if (player.getInventory().getStack(i).getItem() instanceof WeaponItem wi && wi != null
					&& loaded.spec(wi.weaponId()) != null && loaded.spec(wi.weaponId()).isMelee()) {
				n++;
			}
		}
		return n;
	}

	/** 局后清理：攻速闸门的"上次有效攻击"是局内状态，跨局不该残留成"下一局开局就被超频卡住"。 */
	public static void reset() {
		lastValidAttack.clear();
	}
}
