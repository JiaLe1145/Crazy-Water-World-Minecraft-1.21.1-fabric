# test_sea 建海入口：/function nbw:arena/test_sea/build
#
# 几何来自 data/nbw/arena/test_sea.json（圆心 -1000,1000 半径 60 水面 62），改图请重跑生成器。
# 分层：水 y 53..62、海床 y 50..52（石头）、水面以上清空 y 63..71
#
# 代价（算术写在生成器里，别照抄感觉）：
#   forceload 81 笔（逐区块，每 2 tick 一笔 = 162 tick）+ fill 363 笔（每 tick 一笔）→ 建海 ≈ 26 秒；撤票 ≈ 8 秒
#   方块写入合计 248358，单 tick 最大 1210 —— 远低于原版 fill 的 32768 上限
#   覆盖区块 81 个；建海跑完不会自动撤票，收尾看 finish 的提示
#
# 注意：这是**写地形**的命令，会覆盖该区域已有的方块。
# 正式服请先确认这片坐标归地图作者所有，再执行。
# 队列还有剩余时不重开：清空重跑会把已写的行再写一遍，
# 而且重复 forceload 同一批区块会逐条命令失败（噪音会淹没真错误）。
execute if data storage nbw:sea_test_sea rows[0] run tellraw @a [{"text":"[nbw] test_sea 队列仍有剩余笔数，先跑 /function nbw:arena/test_sea/abort 或直接 resume，不要重复 build。","color":"red"}]
execute if data storage nbw:sea_test_sea rows[0] run return fail
data modify storage nbw:sea_test_sea note set value "建海"
data modify storage nbw:sea_test_sea rows set value []
function nbw:arena/test_sea/queue_build
tellraw @a [{"text":"[nbw] test_sea 开始建海：","color":"gold"},{"nbt":"rows","storage":"nbw:sea_test_sea","color":"red"},{"text":" 笔","color":"gold"}]
schedule function nbw:arena/test_sea/step 1t replace
