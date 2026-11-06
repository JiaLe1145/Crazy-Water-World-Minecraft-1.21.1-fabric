package dev.nbw.domain.raft;

/**
 * 桨台推进模型（01 §4.4 已批准的"桨台驱动"，02 §6 表里的"木桨"那一行）。
 * 纯函数，零依赖。
 *
 * <p><b>推力是<b>人</b>给的，不是方块给的。</b>一支桨台只有"正被一个玩家划着"时才出力，
 * 没人划的桨台是纯装饰。这是桨与帆的根本区别：帆被动等风，桨占用一个人的双手
 * （02 §6 的"全靠手，慢"说的就是这件事）。
 *
 * <p><b>与帆共用同一个航速上限。</b>上限是<b>船体</b>的属性，不是某种动力的属性；
 * 各动力各自收口后再叠加，仍然越不过去（{@link WindModel#headroom}）。
 *
 * <p>数值口径来自提案 P-01（旧表"位移 2 格 / 冷却 6s"转写为推力与航速）：木桨 ≈2 格/秒，
 * 帆 ≈3 格/秒，档位比 2:3 与 02 §6 一致。桨与帆各自<b>独立标定</b>（帆的有效推力还乘风强，
 * 编成倍数会让桨跟着风配平漂），2:3 由 {@code OarModelTest} 里那条实测水阻绊线守住——
 * 谁改了推力却没重新标定，单测会直接拦住，两张表不会各改一半。
 */
public final class OarModel {

	/**
	 * 单个正在划桨的玩家贡献的推力。<b>0.267 是真机标定值，不是设计值。</b>
	 *
	 * <p>推导链只有两个锚点，都是实测（M2-RESULTS §3.9）：1 帆在风强 2 时有效推力
	 * {@code 0.2×2=0.4}，终端速度 0.156 格/tick → 这一档的水阻 {@code k≈0.4/0.156=2.56}
	 * （Sable 的水阻 ∝ 浸没体积×速度，{@code buoyancy.rs} 的 {@code do_drag}）。
	 * 02 §6 的档位比是木桨 2 格 : 帆 3 格，P-01 把它转写成"桨 ≈2 格/秒"，
	 * 也就是单桨终端取单帆的 2/3 ≈ 0.104 格/tick → 推力 {@code 0.104×2.56≈0.267}。
	 * 实测 1 人单桨读数 0.1078 格/tick，与推算一致（{@code tools/oar_probe.py} 第 3 步）。
	 *
	 * <p><b>注意单位口径：</b>上面这些全是 {@code linearVelocity} 的<b>读数</b>，不是位移速度。
	 * 实测同一状态下位移只有读数的约 1/22（见 {@link WindModel#MAX_SAIL_SPEED} 与任务 #39），
	 * 所以"桨档 ≈2 格/秒"这句在玩法上要重新按位移标定，别照这个数排节奏。
	 *
	 * <p><b>为什么不写成 {@code FORCE_PER_SAIL × 2/3}：</b>帆的有效推力还要乘风强，
	 * 而"常态风强"目前只有探针用的 2.0，<b>不是已批准的数值</b>。把它编进桨的常数，
	 * 等于让桨的强度跟着风那侧的配平一起漂——风一改，桨就莫名变强或变弱。
	 * 比例关系用 {@link #SPEED_RATIO_VS_ONE_SAIL} 与单测守住，不靠算式假装耦合。
	 */
	public static final double THRUST_PER_OAR = 0.267;

	/**
	 * 单桨终端速度对单帆终端速度的设计比例（02 §6：木桨 2 格 / 帆 3 格）。
	 *
	 * <p>只是把已批准的比值写成一个可断言的常量，不参与任何计算；
	 * 真正守住它的是 {@code OarModelTest} 里那条用实测水阻反推的绊线。
	 */
	public static final double SPEED_RATIO_VS_ONE_SAIL = 2.0 / 3.0;

	/** 桨与帆共用同一个筏级航速上限；上限是船体的属性，见 {@link WindModel#MAX_SAIL_SPEED}。 */
	public static final double MAX_SPEED = WindModel.MAX_SAIL_SPEED;

	private OarModel() {
	}

	/**
	 * 桨只能轴对齐推：方块朝向只有 north/south/east/west 四个水平值。
	 *
	 * <p>这条校验不是防御性摆设——命令与 dev 入口都收字符串方向，
	 * 传进斜向（0.7071, 0.7071）会让"合力=各桨之和"的断言全错位，
	 * 而现场表现只是"桨好像少了三分之一力"，很难归因。
	 */
	public static boolean isAxisAligned(double dirX, double dirZ) {
		if (Double.isNaN(dirX) || Double.isNaN(dirZ)) {
			return false;
		}
		int nz = 0;
		for (double v : new double[]{dirX, dirZ}) {
			if (Math.abs(v) > 1.0 + 1e-9) {
				return false;   // 分量超 1 等于自带放大器，越界方向一律不认
			}
			if (Math.abs(v) > 1e-9) {
				if (Math.abs(Math.abs(v) - 1.0) > 1e-9) {
					return false;   // 非 0 非 1 —— 斜向
				}
				nz++;
			}
		}
		return nz == 1;   // 必须恰好一个非零分量；全 0 是"无向"，不算推力方向
	}

	/**
	 * 一名划桨者的推力分量。方向不合法时返回 {@code {0,0}} 而不是抛异常：
	 * 桨出力发生在每 tick 的循环里，一颗脏方向不该把整局推进打断。
	 */
	public static double[] thrustOf(double dirX, double dirZ) {
		if (!isAxisAligned(dirX, dirZ)) {
			return new double[]{0.0, 0.0};
		}
		return new double[]{dirX * THRUST_PER_OAR, dirZ * THRUST_PER_OAR};
	}

	/** 把一名划桨者的推力累进合力（就地不改，返回新数组，方便纯函数测试与断言）。 */
	public static double[] accumulate(double[] sum, double dirX, double dirZ) {
		double[] t = thrustOf(dirX, dirZ);
		double[] s = sum == null ? new double[]{0.0, 0.0} : sum;
		return new double[]{s[0] + t[0], s[1] + t[1]};
	}

	/**
	 * 按航速上限收口后的推力。
	 *
	 * <p>收口发生在<b>合力</b>上而不是每支桨上：先累加再收口，方向相反的桨才会互相抵消
	 * （乱装桨不该白得动力，这条与帆一致）。
	 */
	public static double[] scaledByCap(double forceX, double forceZ, double speed) {
		if (Double.isNaN(forceX) || Double.isNaN(forceZ) || Double.isNaN(speed)) {
			return new double[]{0.0, 0.0};
		}
		double room = WindModel.headroom(speed, MAX_SPEED);
		return new double[]{forceX * room, forceZ * room};
	}
}
