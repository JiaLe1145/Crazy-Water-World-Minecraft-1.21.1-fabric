# 停手：撤掉排队的 step。队列内容留在 storage 里，想继续跑 resume。
schedule clear nbw:arena/default_sea/step
tellraw @a [{"text":"[nbw] default_sea 已中断，剩余笔数见下一行","color":"yellow"}]
data get storage nbw:sea_default_sea rows
