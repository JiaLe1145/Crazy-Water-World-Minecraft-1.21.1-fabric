# 停手：撤掉排队的 step。队列内容留在 storage 里，想继续跑 resume。
schedule clear nbw:arena/test_sea/step
tellraw @a [{"text":"[nbw] test_sea 已中断，剩余笔数见下一行","color":"yellow"}]
data get storage nbw:sea_test_sea rows
