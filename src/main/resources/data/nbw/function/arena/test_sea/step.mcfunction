# 队列推进器：每 tick 从队首取一笔干完，再排下一 tick。
# 用 key 存在性分派，不用值比较 —— execute if data 只判存在，不判相等。
execute if data storage nbw:sea_test_sea rows[0] run data modify storage nbw:sea_test_sea job set from storage nbw:sea_test_sea rows[0]
execute if data storage nbw:sea_test_sea rows[0] run data remove storage nbw:sea_test_sea rows[0]
execute if data storage nbw:sea_test_sea job.load run function nbw:arena/test_sea/macro_load with storage nbw:sea_test_sea job.load
execute if data storage nbw:sea_test_sea job.fill run function nbw:arena/test_sea/macro_fill with storage nbw:sea_test_sea job.fill
execute if data storage nbw:sea_test_sea job.unload run function nbw:arena/test_sea/macro_unload with storage nbw:sea_test_sea job.unload
# 排程按活种分节流：下票要生成新区块（贵），写块只是改方块（便宜）
execute if data storage nbw:sea_test_sea rows[0] if data storage nbw:sea_test_sea job.load run schedule function nbw:arena/test_sea/step 2t replace
execute if data storage nbw:sea_test_sea rows[0] if data storage nbw:sea_test_sea job.unload run schedule function nbw:arena/test_sea/step 2t replace
execute if data storage nbw:sea_test_sea rows[0] if data storage nbw:sea_test_sea job.fill run schedule function nbw:arena/test_sea/step 1t replace
# 取完即清：不把这笔留给下一个 tick 重放（最后一行 fill 被重复执行就是这个坑）
execute if data storage nbw:sea_test_sea job run data remove storage nbw:sea_test_sea job
# 队列空了才收尾 —— 最后一笔之后循环不再排队，收尾必须放在末尾判，放开头永远触发不了
execute unless data storage nbw:sea_test_sea rows[0] run function nbw:arena/test_sea/finish
