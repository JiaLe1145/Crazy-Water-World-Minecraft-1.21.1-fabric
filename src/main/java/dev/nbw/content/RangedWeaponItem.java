package dev.nbw.content;

import dev.nbw.domain.combat.WeatherCombatRule;
import dev.nbw.round.EventService;
import dev.nbw.round.RoundManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 远程武器（M4-7，09 §2.2）：右键放一发 <b>hitscan</b>，不做投射物实体。
 *
 * <p>为什么用 hitscan 而不是投射物：这一版没有自己的弹道/实体注册基建，投射物要处理生成、飞行、
 * 命中、回收（标枪）一整套；hitscan 用射线在服务端直接判命中，行为可测、参数就三四个，
 * 先把"射程/伤害/装填"这条竞技维度立起来，投射物的表现（拖尾/音效）后补。
 *
 * <p>命中判定：在射程内找离射线最近、且垂直距离 < 0.8 格的活体，取最近的那个。
 * 只在 PLAY 生效（与近战同一道闸），冷却按"玩家 + 武器实例"记。
 */
public final class RangedWeaponItem extends Item {

	/** 一件远程武器的数值：伤害(HP) / 装填(tick) / 射程(格) / 命中后是否把目标拉近(格)。 */
	public record Spec(String id, double damage, int cooldownTicks, double range, double pullTowardShooter) {
	}

	private final Spec spec;
	private final Map<UUID, Integer> lastUse = new HashMap<>();

	public RangedWeaponItem(Spec spec, Settings settings) {
		super(settings);
		this.spec = spec;
	}

	public Spec spec() {
		return spec;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		if (world.isClient() || !(user instanceof ServerPlayerEntity sp)) {
			return TypedActionResult.pass(user.getStackInHand(hand));
		}
		if (sp.isSpectator()) {
			return TypedActionResult.pass(sp.getStackInHand(hand));   // 观战者不能开枪
		}
		if (!RoundManager.phase().allowsPvp()) {
			return TypedActionResult.pass(sp.getStackInHand(hand));
		}
		ServerWorld sw = sp.getServerWorld();
		int now = sw.getServer().getTicks();
		Integer last = lastUse.get(sp.getUuid());
		if (last != null && now - last < spec.cooldownTicks()) {
			int remain = (spec.cooldownTicks() - (now - last)) / 20 + 1;
			sp.sendMessage(Text.literal("§e[cww] 装填中（还要 " + remain + "s）"), true);
			return TypedActionResult.success(sp.getStackInHand(hand));
		}
		lastUse.put(sp.getUuid(), now);
		boolean storm = EventService.stormActive();
		if (WeatherCombatRule.musketDisabledByStorm(storm, spec.id())) {
			sp.sendMessage(Text.literal("§7[cww] 风暴中火铳失效"), true);
			return TypedActionResult.success(sp.getStackInHand(hand));
		}
		LivingEntity target = findTarget(sw, sp, spec.range());
		if (target == null) {
			sp.sendMessage(Text.literal("§7[cww] 未命中"), true);
			return TypedActionResult.success(sp.getStackInHand(hand));
		}
		if (WeatherCombatRule.rangedMissesInStorm(storm, sw.getRandom().nextFloat())) {
			sp.sendMessage(Text.literal("§7[cww] 风暴中射偏了（精度 −50%）"), true);
			return TypedActionResult.success(sp.getStackInHand(hand));
		}
		sw.getServer().execute(() -> {
			target.damage(sw.getDamageSources().playerAttack(sp), (float) spec.damage());
			if (target instanceof ServerPlayerEntity victim) {
				dev.nbw.combat.CombatStats.onDamage(sp, victim, spec.damage());   // 战绩（M5-3）
			}
			if (spec.pullTowardShooter() > 0.0) {
				pullToward(sp, target, spec.pullTowardShooter());
			}
		});
		sp.sendMessage(Text.literal(String.format(Locale.ROOT, "§c[cww] %s 命中 %s（%.1f♥）",
				spec.id(), target.getName().getString(), spec.damage() / 2.0)), true);
		return TypedActionResult.success(sp.getStackInHand(hand));
	}

	/** dev 探针：按当前朝向找目标，返回名字或“无目标”；不改变冷却/伤害。 */
	public String probeTarget(ServerWorld world, ServerPlayerEntity shooter) {
		LivingEntity target = findTarget(world, shooter, spec.range());
		return target == null ? "无目标" : target.getName().getString();
	}

	/** dev 探针：还剩多少 tick 冷却（0 = 可以开枪）。 */
	public int cooldownRemaining(ServerPlayerEntity player) {
		Integer last = lastUse.get(player.getUuid());
		if (last == null) {
			return 0;
		}
		int now = player.getServer().getTicks();
		return Math.max(0, spec.cooldownTicks() - (now - last));
	}

	/** 射线最近活体：垂直距离 < 0.8 格、在射程内、取最近。 */
	private static LivingEntity findTarget(ServerWorld world, ServerPlayerEntity shooter, double range) {
		Vec3d eye = shooter.getEyePos();
		Vec3d dir = shooter.getRotationVector();
		Vec3d end = eye.add(dir.multiply(range));
		List<net.minecraft.entity.Entity> candidates = world.getOtherEntities(shooter,
				new Box(eye, end).expand(1.0));
		LivingEntity best = null;
		double bestAlong = Double.MAX_VALUE;
		for (net.minecraft.entity.Entity e : candidates) {
			if (!(e instanceof LivingEntity le) || le.isDead() || le.isSpectator()) {
				continue;
			}
			// 同队玩家不作为远程目标：RoundManager 的同队门只能挡住最终血量，
			// 挡不住鱼叉拉人和战绩累计，所以必须在选目标时就排除（2026-11-21 实测）。
			if (le instanceof ServerPlayerEntity other
					&& RoundManager.machine().sameTeam(shooter.getUuid(), other.getUuid())) {
				continue;
			}
			Vec3d targetCenter = le.getPos().add(0.0, le.getHeight() * 0.5, 0.0);
			Vec3d toEntity = targetCenter.subtract(eye);
			double along = toEntity.dotProduct(dir);
			if (along < 0.0 || along > range) {
				continue;
			}
			double perp = toEntity.subtract(dir.multiply(along)).length();
			if (perp > 0.8) {
				continue;
			}
			// 方块遮挡：hitscan 不能穿墙。射线先撞到方块（且比目标中心更近）就不算命中。
			// Sable 的筏体方块也在父世界碰撞体里，所以隔着一层甲板/船壳也打不到人。
			HitResult blockHit = world.raycast(new RaycastContext(eye, targetCenter,
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, shooter));
			if (blockHit.getType() == HitResult.Type.BLOCK
					&& blockHit.getPos().distanceTo(eye) < along - 0.3) {
				continue;
			}
			if (along < bestAlong) {
				bestAlong = along;
				best = le;
			}
		}
		return best;
	}

	private static void pullToward(ServerPlayerEntity shooter, LivingEntity target, double distance) {
		Vec3d from = target.getPos();
		Vec3d to = shooter.getPos();
		Vec3d dir = to.subtract(from);
		double len = dir.length();
		if (len < 1e-6) {
			return;
		}
		double step = Math.min(distance, len);
		Vec3d dest = from.add(dir.multiply(step / len));
		target.requestTeleport(dest.x, dest.y, dest.z);
	}

	/** 局后清理：装填表是局内的。 */
	public void resetCooldown() {
		lastUse.clear();
	}
}
