package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.nbw.NBW;
import dev.nbw.combat.WeaponService;
import dev.nbw.content.NbwWeapons;
import dev.nbw.content.WeaponItem;
import dev.nbw.domain.combat.CombatRule;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 武器相关命令（从 {@link NbwCommands} 拆出来）：list / reload / status / give / probe / probe2 / ranged。
 *
 * <p>拆分**不改命令树**：注册出来的路径与拆分前逐字一致。
 */
public final class WeaponCommands {

	private WeaponCommands() {
	}

	/** 挂到 {@code /cww} 根节点下：{@code /cww weapon ...}。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		var weapon = CmdSupport.admin(CommandManager.literal("weapon"));
		weapon.then(CommandManager.literal("list").executes(WeaponCommands::list));
		weapon.then(CommandManager.literal("reload").executes(WeaponCommands::reload));
		weapon.then(CommandManager.literal("status").executes(WeaponCommands::status));
		weapon.then(CommandManager.literal("give")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("id", StringArgumentType.word())
								.executes(WeaponCommands::give))));
		// 确定性 PVP 诊断：不依赖 Carpet 的 attack once，直接走攻速闸门并读实际攻击属性。
		weapon.then(CommandManager.literal("probe")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("target", StringArgumentType.word())
								.then(CommandManager.argument("id", StringArgumentType.word())
										.executes(WeaponCommands::probe)))));
		weapon.then(CommandManager.literal("probe2")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("target", StringArgumentType.word())
								.then(CommandManager.argument("id", StringArgumentType.word())
										.executes(WeaponCommands::probeTwice)))));
		// 远程武器夹具：Carpet 的 use once 不触发自定义 Item.use，这条路直接走真实的
		// RangedWeaponItem.use（冷却 + 命中 + 方块遮挡），给安全探针验"隔墙打不中"。
		weapon.then(CommandManager.literal("ranged")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(WeaponCommands::ranged)));
		root.then(weapon);
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		var loaded = WeaponService.current();
		if (!loaded.ok()) {
			ctx.getSource().sendError(Text.literal("[cww] 武器表不可用：" + String.join(" | ", loaded.errors())));
			return 0;
		}
		List<String> lines = new ArrayList<>();
		for (CombatRule.WeaponSpec s : loaded.specs()) {
			lines.add(String.format(Locale.ROOT, "%s 伤害=%.1fHP 冷却=%dtick 击退=%.1f 对筏心=×%.1f",
					s.id(), s.damage(), s.cooldownTicks(), s.knockback(), s.coreMultiplier()));
		}
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] 武器 " + loaded.specs().size() + " 件："
				+ String.join("｜", lines)), false);
		return 1;
	}

	private static int reload(CommandContext<ServerCommandSource> ctx) {
		return CmdSupport.tell(ctx, WeaponService.reloadWeapons());
	}

	private static int status(CommandContext<ServerCommandSource> ctx) {
		List<String> recent = WeaponService.recent();
		StringBuilder sb = new StringBuilder(WeaponService.status());
		if (!recent.isEmpty()) {
			sb.append("\n最近判定：\n");
			for (String line : recent) {
				sb.append("  ").append(line).append('\n');
			}
		}
		ctx.getSource().sendFeedback(() -> Text.literal(sb.toString().trim()), false);
		return 1;
	}

	private static int give(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
		if (target == null) {
			return CmdSupport.tell(ctx, "找不到在线玩家 " + name + "（假玩家也要先 /player <名> spawn）");
		}
		String id = StringArgumentType.getString(ctx, "id");
		WeaponItem item = NbwWeapons.item(id);
		if (item == null) {
			return CmdSupport.tell(ctx, "不认识武器 id：" + id
					+ "（近战：paddle/cutlass/pike/warhammer/twin_blade/stone_spear）");
		}
		CombatRule.WeaponSpec spec = WeaponService.current().spec(id);
		if (spec == null) {
			return CmdSupport.tell(ctx, "武器 " + id + " 不在当前武器表里（/cww weapon reload 后重试）");
		}
		String violation = CombatRule.holdViolation(WeaponService.meleeHeld(target), spec.kind());
		if (violation != null) {
			// 拒绝第二件近战：这是 09 §5.1 的持有上限，不是物品本身被禁
			NBW.LOGGER.warn("[cmd] weapon give 拒绝 {} 给 {}：{}", id, name, violation);
			return CmdSupport.tell(ctx, "拒绝：" + violation);
		}
		if (!target.getInventory().insertStack(new ItemStack(item))) {
			return CmdSupport.tell(ctx, "背包已满，给不进去");
		}
		NBW.LOGGER.info("[cmd] weapon give {} 给 {}（{}）", id, name, spec);
		return CmdSupport.tell(ctx, "已给 " + name + " 武器 " + id + "（伤害 " + spec.damage()
				+ "HP，冷却 " + spec.cooldownTicks() + " tick）");
	}

	private static int probe(CommandContext<ServerCommandSource> ctx) {
		String player = StringArgumentType.getString(ctx, "player");
		String target = StringArgumentType.getString(ctx, "target");
		ServerPlayerEntity attacker = ctx.getSource().getServer().getPlayerManager().getPlayer(player);
		ServerPlayerEntity victim = ctx.getSource().getServer().getPlayerManager().getPlayer(target);
		if (attacker == null || victim == null) {
			return CmdSupport.tell(ctx, "找不到在线玩家：" + (attacker == null ? player : target));
		}
		return CmdSupport.tell(ctx, WeaponService.probe(attacker, victim, StringArgumentType.getString(ctx, "id")));
	}

	private static int probeTwice(CommandContext<ServerCommandSource> ctx) {
		String player = StringArgumentType.getString(ctx, "player");
		String target = StringArgumentType.getString(ctx, "target");
		ServerPlayerEntity attacker = ctx.getSource().getServer().getPlayerManager().getPlayer(player);
		ServerPlayerEntity victim = ctx.getSource().getServer().getPlayerManager().getPlayer(target);
		if (attacker == null || victim == null) {
			return CmdSupport.tell(ctx, "找不到在线玩家：" + (attacker == null ? player : target));
		}
		String id = StringArgumentType.getString(ctx, "id");
		// 同一 tick 连判两次：第一发留下 lastValidAttack，第二发必然落在冷却内。
		String first = WeaponService.probe(attacker, victim, id);
		String second = WeaponService.probe(attacker, victim, id);
		return CmdSupport.tell(ctx, "#1 " + first + " | #2 " + second);
	}

	/**
	 * {@code /cww weapon ranged <玩家>}：按主手远程武器走一次真实的 {@code RangedWeaponItem.use}。
	 *
	 * <p>为什么需要它：Carpet 假玩家的 {@code use once} 不会触发我们的自定义 {@code Item.use}，
	 * 所以"隔墙能不能打中"一直只能靠读代码。这条夹具让安全探针能直接验冷却、命中与遮挡。
	 * 它只代替"客户端右键"这一个动作，服务端判定一行不改。
	 */
	private static int ranged(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		ServerPlayerEntity p = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
		if (p == null) {
			return CmdSupport.tell(ctx, "找不到在线玩家 " + name);
		}
		if (!(p.getMainHandStack().getItem() instanceof dev.nbw.content.RangedWeaponItem rw)) {
			return CmdSupport.tell(ctx, "主手不是远程武器（先 /cww weapon give 或 item replace）");
		}
		int before = rw.cooldownRemaining(p);
		var result = rw.use(p.getServerWorld(), p, net.minecraft.util.Hand.MAIN_HAND);
		return CmdSupport.tell(ctx, "远程使用已执行：" + result.getResult()
				+ "｜冷却剩余(执行前)=" + before + "tick"
				+ "｜朝向=" + p.getRotationVector()
				+ "｜目标=" + rw.probeTarget(p.getServerWorld(), p));
	}
}
