package dev.nbw.domain.raft;

/**
 * 桨的方向口径（M2-5，动力第二源）。纯函数，零依赖。
 *
 * <p><b>这里只剩方向，没有力。</b>以前它有一个 {@code THRUST_PER_OAR}：每 tick 按帆的写法
 * 直接给一冲量，靠 Sable 的水阻自己收敛出"终端速度"。实测证明那种做法给出的数字
 * 是每 tick 施力后的<b>峰值读数</b>，与玩家看见的位移差约 22 倍（M2-RESULTS §3.10），
 * 于是档位数值全都对不上玩法描述。现在改由 {@link SpeedGovernor} 闭环控制<b>位移</b>速度，
 * 桨只回答两件事：<b>谁在往哪个方向划</b>，以及<b>方向相反的桨要互相抵消</b>。
 *
 * <p><b>桨是人才有的动力。</b>一支桨只有"正被一个玩家划着"时才算进方向表，
 * 没人划的桨台是纯装饰（02 §6 的"全靠手，慢"说的就是这件事）。
 * 具体几个人能跑多快在 {@link SpeedGovernor#ROW_SPEED_PER_ROWER} 里，不在这里。
 */
public final class OarModel {

	private OarModel() {
	}

	/**
	 * 桨只能轴对齐推：方块朝向只有 north/south/east/west 四个水平值。
	 *
	 * <p>这条校验不是防御性摆设——命令层与 dev 入口都收字符串方向，
	 * 混进斜向（0.7071, 0.7071）会让"人数×档位"的目标速度算错，
	 * 而现场表现只是"桨好像少了三分之一力"，很难归因。
	 */
	public static boolean isAxisAligned(double dirX, double dirZ) {
		if (Double.isNaN(dirX) || Double.isNaN(dirZ)) {
			return false;
		}
		int nonzero = 0;
		for (double v : new double[]{dirX, dirZ}) {
			if (Math.abs(v) > 1.0 + 1e-9) {
				return false;   // 分量超 1 等于自带放大器
			}
			if (Math.abs(v) > 1e-9) {
				if (Math.abs(Math.abs(v) - 1.0) > 1e-9) {
					return false;   // 非 0 非 ±1 —— 斜向
				}
				nonzero++;
			}
		}
		return nonzero == 1;   // 恰好一个非零分量；全 0 是"无向"
	}

	/**
	 * 把一名划桨者的单位方向累进方向表（返回新数组，方便纯函数断言）。
	 *
	 * <p>累加的是<b>单位矢量</b>而不是力：所以一西一东相加自然得到 (0,0)，
	 * "乱装桨不该白得动力"这条设计意图不需要额外规则就成立。
	 * 方向不合法的输入返回原表（不当份数计），并让调用方的计数仍然对得上。
	 *
	 * @return {@code {ΣdirX, ΣdirZ}} —— 各方向的人数计数，不是力也不是速度
	 */
	public static double[] addDirection(double[] sum, double dirX, double dirZ) {
		double[] s = sum == null ? new double[]{0.0, 0.0} : sum;
		if (!isAxisAligned(dirX, dirZ)) {
			return new double[]{s[0], s[1]};
		}
		return new double[]{s[0] + dirX, s[1] + dirZ};
	}

	/**
	 * 方向表折算成"这一队一共有几份桨力在生效"。
	 *
	 * <p>用模长而不是人数：4 个人里 2 人朝西 2 人朝东，模长是 0 —— 与"没人在划"同解。
	 * 2 人朝西 1 人朝东，模长是 1 —— 净一份桨力。这样档位与抵消都只用一条算式表达。
	 */
	public static double effectiveStrokes(double sumDirX, double sumDirZ) {
		double mag = Math.hypot(sumDirX, sumDirZ);
		return Double.isNaN(mag) ? 0.0 : mag;
	}

	/** 单位方向；合方向为 0 时返回 (0,0)，调用方据此跳过本 tick。 */
	public static double[] unit(double dirX, double dirZ) {
		double mag = Math.hypot(dirX, dirZ);
		if (Double.isNaN(mag) || mag <= 1e-9) {
			return new double[]{0.0, 0.0};
		}
		return new double[]{dirX / mag, dirZ / mag};
	}
}
