# 剩余笔数：data get 的那行回包就是机器可读读数（RCON 直接拿到）。
# 不用 @s —— RCON/控制台这个来源不是实体，@s 会选择失败并刷一条无关的错误。
tellraw @a [{"text":"[nbw] default_sea 剩余笔数见下一行","color":"aqua"}]
data get storage nbw:sea_default_sea rows
