# 中心残骸模板（模板区 (140,62,140)..(147,69,147)），由 /cww reset buildtemplate 建一次；
# 之后每局 READY 由 MapResetService 把这 8×8×8 复制到中心贴合区 (-4,62,-4)..(3,69,3)。
# 想换玩法结构，改这里的 fill/setblock 再重跑 buildtemplate 即可。
fill 140 62 140 147 62 147 minecraft:stone_bricks
fill 140 63 140 140 66 140 minecraft:mossy_stone_bricks
fill 147 63 140 147 65 140 minecraft:stone_bricks
fill 140 63 147 140 65 147 minecraft:stone_bricks
fill 147 63 147 147 66 147 minecraft:mossy_stone_bricks
fill 141 63 140 143 64 140 minecraft:stone_brick_wall
fill 146 63 147 147 64 147 minecraft:stone_brick_wall
setblock 143 63 143 minecraft:chest
