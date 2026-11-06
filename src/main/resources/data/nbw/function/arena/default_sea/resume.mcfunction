# 中断（abort）或服务端重启后接着跑剩余队列，不重铺、不重复已写的行。
execute if data storage nbw:sea_default_sea rows[0] run schedule function nbw:arena/default_sea/step 1t replace
execute unless data storage nbw:sea_default_sea rows[0] run tellraw @a [{"text":"[nbw] default_sea 队列是空的：要么已经跑完，要么还没 build。","color":"red"}]
