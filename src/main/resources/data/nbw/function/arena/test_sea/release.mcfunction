# 撤掉建海期间下的 forceload（按同一张行表逐行撤，一次一批）。
# 建海/撤票共用一个队列，跑着的时候绝不能插一脚：先 status 看剩余，跑完再撤。
execute if data storage nbw:sea_test_sea rows[0] run tellraw @a [{"text":"[nbw] test_sea 队列还有剩余笔数，撤票要等它跑完（共用同一个队列）。","color":"red"}]
execute if data storage nbw:sea_test_sea rows[0] run return fail
data modify storage nbw:sea_test_sea note set value "撤票"
data modify storage nbw:sea_test_sea rows set value []
function nbw:arena/test_sea/queue_release
schedule function nbw:arena/test_sea/step 1t replace
