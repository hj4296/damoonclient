-- 블록 종류 정의.
--  color    : 텍스처가 없을 때 보이는 색 (텍스처의 평균색)
--  tex      : 면마다 붙일 픽셀 텍스처 이름 (Textures.luau 에서 그림). 문자열이면 여섯 면 모두 같음
--  shape    : nil(정육면체), slab, slab_top, carpet, fence, wall, plant, grass, lantern, torch, chest, bed
--  hardness : 맨손으로 부수는 시간(초), tool: 알맞은 도구
local Config = require(script.Parent.Config)

local Blocks = {}

export type BlockDef = {
	id: string,
	name: string,
	color: Color3,
	material: Enum.Material,
	hardness: number,
	tool: string?,
	tex: any,
	shape: string?,
	transparency: number?,
	canCollide: boolean?,
	unbreakable: boolean?,
	blastProof: boolean?,
	kill: boolean?,
	light: number?,
	lightColor: Color3?,
	team: string?,
	bed: boolean?,
	tnt: boolean?,
	chest: boolean?,
	noItem: boolean?,
	occludes: boolean,
	bloom: Color3?,
	textureId: string?,
}

local defs: { [string]: BlockDef } = {}
Blocks.defs = defs

local M = Enum.Material
local function rgb(r: number, g: number, b: number): Color3
	return Color3.fromRGB(r, g, b)
end

-- 모양별 크기와 위치(블록 단위, 칸 중심 기준)
Blocks.Shapes = {
	slab = { size = Vector3.new(1, 0.5, 1), offset = Vector3.new(0, -0.25, 0) },
	slab_top = { size = Vector3.new(1, 0.5, 1), offset = Vector3.new(0, 0.25, 0) },
	carpet = { size = Vector3.new(1, 0.07, 1), offset = Vector3.new(0, -0.465, 0) },
	fence = { size = Vector3.new(0.26, 1, 0.26), offset = Vector3.zero },
	wall = { size = Vector3.new(0.5, 1, 0.5), offset = Vector3.zero },
	plant = { size = Vector3.new(0.1, 0.5, 0.1), offset = Vector3.new(0, -0.25, 0) },
	grass = { size = Vector3.new(0.75, 0.55, 0.05), offset = Vector3.new(0, -0.225, 0) },
	lantern = { size = Vector3.new(0.34, 0.42, 0.34), offset = Vector3.new(0, -0.29, 0) },
	torch = { size = Vector3.new(0.12, 0.55, 0.12), offset = Vector3.new(0, -0.225, 0) },
	chest = { size = Vector3.new(0.86, 0.86, 0.86), offset = Vector3.new(0, -0.07, 0) },
	bed = { size = Vector3.new(1, 0.56, 1), offset = Vector3.new(0, -0.22, 0) },
}

local function add(id: string, data: { [string]: any })
	data.id = id
	data.hardness = data.hardness or 1
	data.material = data.material or M.SmoothPlastic
	if data.tex == nil then
		data.tex = id
	end
	local transparent = (data.transparency or 0) > 0.02
	data.occludes = data.shape == nil and not transparent and data.canCollide ~= false and not data.chest and not data.bed
	defs[id] = data :: any
end

-- 땅
add("grass", { name = "잔디 블록", color = rgb(96, 150, 58), hardness = 0.6, tex = { top = "grass_top", side = "grass_side", bottom = "dirt" } })
add("dirt", { name = "흙", color = rgb(134, 96, 67), hardness = 0.5 })
add("path", { name = "흙길", color = rgb(148, 122, 74), hardness = 0.5, tex = { top = "path_top", side = "dirt", bottom = "dirt" } })
add("snow_grass", { name = "눈 덮인 잔디", color = rgb(236, 244, 248), hardness = 0.6, tex = { top = "snow", side = "snow_side", bottom = "dirt" } })
add("mycelium", { name = "균사체", color = rgb(118, 100, 118), hardness = 0.6, tex = { top = "mycelium_top", side = "mycelium_side", bottom = "dirt" } })
add("sand", { name = "모래", color = rgb(220, 208, 158), hardness = 0.5 })
add("red_sand", { name = "붉은 모래", color = rgb(190, 104, 50), hardness = 0.5 })
add("gravel", { name = "자갈", color = rgb(132, 125, 122), hardness = 0.6 })
add("snow", { name = "눈 블록", color = rgb(240, 247, 252), hardness = 0.3 })
add("ice", { name = "얼음", color = rgb(150, 192, 245), hardness = 0.5, transparency = 0.15 })
add("packed_ice", { name = "단단한 얼음", color = rgb(130, 170, 232), hardness = 0.8, tool = "pickaxe" })
add("clay_block", { name = "점토", color = rgb(160, 166, 178), hardness = 0.6 })

-- 돌
add("stone", { name = "돌", color = rgb(125, 125, 125), hardness = 2.2, tool = "pickaxe" })
add("cobble", { name = "조약돌", color = rgb(115, 115, 115), hardness = 2.2, tool = "pickaxe" })
add("mossy_cobble", { name = "이끼 낀 조약돌", color = rgb(100, 118, 92), hardness = 2.2, tool = "pickaxe" })
add("stone_bricks", { name = "석재 벽돌", color = rgb(120, 120, 122), hardness = 2.2, tool = "pickaxe" })
add("mossy_stone_bricks", { name = "이끼 낀 석재 벽돌", color = rgb(104, 118, 96), hardness = 2.2, tool = "pickaxe" })
add("polished_stone", { name = "매끈한 돌", color = rgb(160, 160, 162), hardness = 2.2, tool = "pickaxe" })
add("polished_granite", { name = "매끈한 화강암", color = rgb(160, 110, 92), hardness = 2.2, tool = "pickaxe" })
add("polished_diorite", { name = "매끈한 섬록암", color = rgb(196, 196, 198), hardness = 2.2, tool = "pickaxe" })
add("coal_ore", { name = "석탄 광석", color = rgb(110, 110, 110), hardness = 2.4, tool = "pickaxe" })
add("iron_ore", { name = "철 광석", color = rgb(132, 124, 118), hardness = 2.6, tool = "pickaxe" })
add("gold_ore", { name = "금 광석", color = rgb(140, 132, 110), hardness = 2.6, tool = "pickaxe" })
add("bricks", { name = "벽돌", color = rgb(150, 82, 66), hardness = 2.2, tool = "pickaxe" })
add("quartz", { name = "석영 블록", color = rgb(234, 228, 220), hardness = 1.6, tool = "pickaxe" })
add("quartz_pillar", { name = "석영 기둥", color = rgb(232, 226, 218), hardness = 1.6, tool = "pickaxe", tex = { top = "quartz_pillar_top", side = "quartz_pillar", bottom = "quartz_pillar_top" } })
add("sandstone", { name = "사암", color = rgb(214, 198, 144), hardness = 1.2, tool = "pickaxe", tex = { top = "sandstone_top", side = "sandstone_side", bottom = "sandstone_top" } })
add("smooth_sandstone", { name = "매끈한 사암", color = rgb(216, 202, 150), hardness = 1.2, tool = "pickaxe", tex = "sandstone_top" })
add("carved_sandstone", { name = "조각된 사암", color = rgb(210, 192, 136), hardness = 1.2, tool = "pickaxe", tex = { top = "sandstone_top", side = "sandstone_carved", bottom = "sandstone_top" } })
add("red_sandstone", { name = "붉은 사암", color = rgb(186, 100, 46), hardness = 1.2, tool = "pickaxe", tex = { top = "red_sandstone_top", side = "red_sandstone_side", bottom = "red_sandstone_top" } })
add("basalt", { name = "현무암", color = rgb(72, 70, 76), hardness = 2.2, tool = "pickaxe", tex = { top = "basalt_top", side = "basalt_side", bottom = "basalt_top" } })
add("dark_stone", { name = "흑암", color = rgb(46, 42, 50), hardness = 2.2, tool = "pickaxe" })
add("dark_bricks", { name = "흑암 벽돌", color = rgb(52, 46, 56), hardness = 2.2, tool = "pickaxe" })
add("magma", { name = "마그마 블록", color = rgb(150, 58, 26), hardness = 1, tool = "pickaxe" })
add("obsidian", { name = "흑요석", color = rgb(30, 22, 44), hardness = 25, tool = "pickaxe", blastProof = true })
add("bedrock", { name = "기반암", color = rgb(56, 56, 58), unbreakable = true, blastProof = true, noItem = true })

-- 나무
add("planks", { name = "참나무 판자", color = rgb(162, 130, 80), hardness = 1.4, tool = "axe" })
add("dark_planks", { name = "짙은 판자", color = rgb(88, 62, 38), hardness = 1.4, tool = "axe" })
add("birch_planks", { name = "자작나무 판자", color = rgb(196, 178, 124), hardness = 1.4, tool = "axe" })
add("cherry_planks", { name = "벚나무 판자", color = rgb(222, 170, 166), hardness = 1.4, tool = "axe" })
add("log", { name = "참나무 원목", color = rgb(104, 82, 50), hardness = 1.8, tool = "axe", tex = { top = "log_top", side = "log_side", bottom = "log_top" } })
add("dark_log", { name = "짙은 원목", color = rgb(62, 46, 30), hardness = 1.8, tool = "axe", tex = { top = "dark_log_top", side = "dark_log_side", bottom = "dark_log_top" } })
add("birch_log", { name = "자작나무 원목", color = rgb(216, 212, 200), hardness = 1.8, tool = "axe", tex = { top = "birch_log_top", side = "birch_log_side", bottom = "birch_log_top" } })
add("cherry_log", { name = "벚나무 원목", color = rgb(70, 42, 50), hardness = 1.8, tool = "axe", tex = { top = "cherry_log_top", side = "cherry_log_side", bottom = "cherry_log_top" } })
add("bookshelf", { name = "책장", color = rgb(130, 100, 70), hardness = 1.4, tool = "axe", tex = { top = "planks", side = "bookshelf", bottom = "planks" } })
add("hay", { name = "건초 더미", color = rgb(196, 166, 50), hardness = 0.5, tex = { top = "hay_top", side = "hay_side", bottom = "hay_top" } })

-- 잎과 식물 블록
add("leaves", { name = "참나무 잎", color = rgb(62, 128, 44), hardness = 0.25, tool = "shears" })
add("dark_leaves", { name = "짙은 잎", color = rgb(46, 92, 46), hardness = 0.25, tool = "shears" })
add("birch_leaves", { name = "자작나무 잎", color = rgb(110, 150, 70), hardness = 0.25, tool = "shears" })
add("sakura_leaves", { name = "벚꽃잎", color = rgb(240, 172, 204), hardness = 0.25, tool = "shears" })
add("palm_leaves", { name = "야자 잎", color = rgb(92, 160, 56), hardness = 0.25, tool = "shears" })
add("cactus", { name = "선인장", color = rgb(70, 128, 48), hardness = 0.4, tex = { top = "cactus_top", side = "cactus_side", bottom = "cactus_top" } })
add("mushroom_red", { name = "붉은 버섯 블록", color = rgb(190, 44, 40), hardness = 0.4, tool = "axe" })
add("mushroom_brown", { name = "갈색 버섯 블록", color = rgb(150, 112, 80), hardness = 0.4, tool = "axe" })
add("mushroom_stem", { name = "버섯 줄기", color = rgb(220, 212, 196), hardness = 0.4, tool = "axe" })

-- 광물/장식 블록
add("iron_block", { name = "철 블록", color = rgb(216, 216, 220), hardness = 3, tool = "pickaxe" })
add("gold_block", { name = "금 블록", color = rgb(246, 206, 62), hardness = 3, tool = "pickaxe" })
add("diamond_block", { name = "다이아몬드 블록", color = rgb(100, 222, 212), hardness = 3, tool = "pickaxe" })
add("emerald_block", { name = "에메랄드 블록", color = rgb(48, 196, 96), hardness = 3, tool = "pickaxe" })
add("lamp", { name = "조명 블록", color = rgb(255, 214, 140), hardness = 0.5, material = M.Neon, light = 16, tex = false })
add("crystal_lamp", { name = "수정 조명", color = rgb(160, 230, 230), hardness = 0.5, material = M.Neon, light = 16, tex = false })
add("shroomlight", { name = "버섯 조명", color = rgb(250, 160, 80), hardness = 0.5, material = M.Neon, light = 14, tex = false })

-- 유리
add("glass", { name = "유리", color = rgb(214, 236, 248), hardness = 0.3, transparency = 0.55, material = M.Glass, tex = "glass" })
add("blast_glass", { name = "방폭 유리", color = rgb(190, 216, 236), hardness = 0.6, transparency = 0.45, material = M.Glass, tex = "glass", blastProof = true })
add("cage_glass", { name = "출발 유리관", color = rgb(200, 230, 255), transparency = 0.55, material = M.Glass, tex = "glass", unbreakable = true, noItem = true })
local STAINED = {
	red = rgb(200, 60, 60),
	blue = rgb(60, 90, 200),
	yellow = rgb(230, 200, 60),
	lime = rgb(120, 200, 60),
	purple = rgb(140, 70, 190),
	orange = rgb(230, 130, 50),
	pink = rgb(236, 140, 180),
	cyan = rgb(60, 170, 190),
	white = rgb(236, 236, 236),
}
for name, color in STAINED do
	add("stained_" .. name, { name = "색유리", color = color, hardness = 0.3, transparency = 0.4, material = M.Glass, tex = "glass" })
end

-- 액체 (통과, 부서지지 않음)
add("water", { name = "물", color = rgb(56, 108, 214), material = M.Glass, transparency = 0.4, canCollide = false, unbreakable = true, blastProof = true, noItem = true })
add("lava", { name = "용암", color = rgb(255, 110, 20), material = M.Neon, transparency = 0.02, canCollide = false, unbreakable = true, blastProof = true, kill = true, noItem = true, tex = false })

-- 특수
add("tnt", { name = "TNT", color = rgb(204, 56, 44), hardness = 0.05, tnt = true, tex = { top = "tnt_top", side = "tnt_side", bottom = "tnt_bottom" } })
add("chest", {
	name = "상자",
	color = rgb(158, 110, 52),
	shape = "chest",
	unbreakable = true,
	blastProof = true,
	chest = true,
	noItem = true,
	tex = { top = "chest_top", side = "chest_side", front = "chest_front", bottom = "chest_top" },
})

-- 양털 (팀 색 + 장식 색), 테라코타, 콘크리트(색 블록), 카펫
local DYES: { [string]: Color3 } = {
	white = rgb(234, 236, 236),
	black = rgb(30, 30, 36),
	gray = rgb(80, 84, 88),
	brown = rgb(116, 74, 42),
	red = rgb(176, 46, 38),
	orange = rgb(236, 120, 24),
	yellow = rgb(246, 196, 40),
	lime = rgb(110, 184, 26),
	green = rgb(84, 110, 28),
	cyan = rgb(22, 144, 152),
	blue = rgb(52, 56, 168),
	purple = rgb(122, 44, 170),
	pink = rgb(236, 140, 172),
}
Blocks.Dyes = DYES
for name, color in DYES do
	add("wool_" .. name, { name = "양털", color = color, hardness = 0.7, tool = "shears", tex = "wool_" .. name })
	add("terracotta_" .. name, { name = "테라코타", color = color:Lerp(rgb(150, 95, 70), 0.45), hardness = 1.4, tool = "pickaxe", tex = "terracotta_" .. name })
	add("carpet_" .. name, { name = "카펫", color = color, hardness = 0.1, shape = "carpet", canCollide = false, tex = "wool_" .. name })
end
add("petals", { name = "꽃잎", color = rgb(244, 178, 210), hardness = 0.05, shape = "carpet", canCollide = false, noItem = true, tex = "petals" })

-- 팀 블록: 양털은 Config 팀 색을 따르고, 점토와 침대도 팀마다
for _, team in Config.Teams do
	local wool = defs["wool_" .. team.id]
	if wool then
		wool.name = team.name .. " 양털"
		wool.team = team.id
		wool.color = team.color
	else
		add("wool_" .. team.id, { name = team.name .. " 양털", color = team.color, hardness = 0.7, tool = "shears", team = team.id })
	end
	add("clay_" .. team.id, {
		name = team.name .. " 단단한 점토",
		color = team.color:Lerp(rgb(150, 95, 70), 0.35),
		hardness = 1.6,
		tool = "pickaxe",
		team = team.id,
		tex = "terracotta_" .. team.id,
	})
	add("bed_" .. team.id, {
		name = team.name .. " 침대",
		color = team.color,
		hardness = 0.4,
		bed = true,
		team = team.id,
		noItem = true,
		blastProof = true,
		shape = "bed",
		tex = { top = "wool_" .. team.id, side = "bed_side", bottom = "planks" },
	})
end
Blocks.TeamTextureColors = {}
for _, team in Config.Teams do
	Blocks.TeamTextureColors[team.id] = team.color
end

-- TNT 런 바닥 색 블록
local COLORS = {
	{ "white", "흰색", rgb(232, 234, 235) },
	{ "black", "검은색", rgb(30, 32, 38) },
	{ "red", "빨간색", rgb(196, 40, 40) },
	{ "orange", "주황색", rgb(236, 120, 20) },
	{ "yellow", "노란색", rgb(242, 196, 30) },
	{ "lime", "연두색", rgb(110, 186, 30) },
	{ "cyan", "청록색", rgb(22, 140, 150) },
	{ "blue", "파란색", rgb(46, 52, 160) },
	{ "purple", "보라색", rgb(112, 36, 160) },
	{ "pink", "분홍색", rgb(220, 112, 150) },
}
Blocks.ColorNames = {}
Blocks.ColorValues = {}
for _, entry in COLORS do
	add("color_" .. entry[1], { name = entry[2] .. " 콘크리트", color = entry[3], hardness = 0.6, tex = "concrete_" .. entry[1] })
	table.insert(Blocks.ColorNames, entry[1])
	Blocks.ColorValues[entry[1]] = entry[3]
end

-- 반 블록 (아래/위)
for _, base in { "planks", "dark_planks", "birch_planks", "cherry_planks", "stone_bricks", "polished_stone", "sandstone", "smooth_sandstone", "red_sandstone", "quartz", "bricks", "cobble", "dark_bricks", "basalt" } do
	local d = defs[base]
	for _, variant in { "slab", "slab_top" } do
		add(base .. "_" .. variant, {
			name = d.name .. " 반 블록",
			color = d.color,
			hardness = d.hardness,
			tool = d.tool,
			shape = variant,
			tex = d.tex,
			noItem = variant == "slab_top",
		})
	end
end

-- 울타리, 담장
add("fence", { name = "울타리", color = rgb(162, 130, 80), hardness = 1, tool = "axe", shape = "fence", tex = "planks" })
add("dark_fence", { name = "짙은 울타리", color = rgb(88, 62, 38), hardness = 1, tool = "axe", shape = "fence", tex = "dark_planks" })
add("cobble_wall", { name = "조약돌 담장", color = rgb(115, 115, 115), hardness = 2, tool = "pickaxe", shape = "wall", tex = "cobble" })
add("sandstone_wall", { name = "사암 담장", color = rgb(214, 198, 144), hardness = 1.2, tool = "pickaxe", shape = "wall", tex = "sandstone_top" })
add("quartz_wall", { name = "석영 담장", color = rgb(234, 228, 220), hardness = 1.2, tool = "pickaxe", shape = "wall", tex = "quartz" })

-- 조명
add("lantern", { name = "랜턴", color = rgb(255, 196, 110), hardness = 0.3, shape = "lantern", canCollide = false, light = 14, lightColor = rgb(255, 190, 120), material = M.Neon, tex = false, noItem = true })
add("soul_lantern", { name = "푸른 랜턴", color = rgb(120, 220, 230), hardness = 0.3, shape = "lantern", canCollide = false, light = 12, lightColor = rgb(120, 220, 240), material = M.Neon, tex = false, noItem = true })
add("torch", { name = "횃불", color = rgb(120, 86, 50), hardness = 0.05, shape = "torch", canCollide = false, light = 12, lightColor = rgb(255, 170, 80), tex = false, noItem = true })

-- 꽃과 풀 (작은 장식, 통과 가능)
local FLOWERS = {
	flower_red = rgb(220, 40, 40),
	flower_yellow = rgb(250, 220, 50),
	flower_blue = rgb(70, 110, 230),
	flower_white = rgb(245, 245, 245),
	flower_pink = rgb(245, 150, 200),
	flower_purple = rgb(160, 80, 220),
}
for id, color in FLOWERS do
	add(id, { name = "꽃", color = rgb(60, 140, 40), bloom = color, hardness = 0.02, shape = "plant", canCollide = false, tex = false, noItem = true })
end
add("tall_grass", { name = "풀", color = rgb(84, 150, 52), hardness = 0.02, shape = "grass", canCollide = false, tex = false, noItem = true })
add("dead_bush", { name = "마른 덤불", color = rgb(140, 100, 52), hardness = 0.02, shape = "grass", canCollide = false, tex = false, noItem = true })
add("fern", { name = "고사리", color = rgb(60, 120, 50), hardness = 0.02, shape = "grass", canCollide = false, tex = false, noItem = true })
add("small_mushroom", { name = "작은 버섯", color = rgb(220, 210, 190), bloom = rgb(200, 40, 40), hardness = 0.02, shape = "plant", canCollide = false, tex = false, noItem = true })

-- 팀 색 양털/침대 등은 위에서 만들었고, 그 밖의 팀 이름(red 등)은 DYES 와 겹칩니다.

function Blocks.get(id: string): BlockDef?
	return defs[id]
end

function Blocks.exists(id: string): boolean
	return defs[id] ~= nil
end

-- 면 이름(top/bottom/side/front)에 맞는 텍스처 이름
function Blocks.faceTexture(def: BlockDef, face: string): string?
	local tex = def.tex
	if tex == false or tex == nil then
		return nil
	end
	if type(tex) == "string" then
		return tex
	end
	if face == "front" then
		return tex.front or tex.side
	end
	return tex[face] or tex.side
end

-- 블록 위치마다 색을 살짝 다르게 해서 텍스처가 없을 때도 픽셀 블록 느낌을 냅니다.
function Blocks.shade(def: BlockDef, x: number, y: number, z: number): Color3
	local h = (x * 73856093 + y * 19349663 + z * 83492791) % 1000
	local t = h / 1000
	local color = def.color
	if t < 0.5 then
		return color:Lerp(Color3.new(0, 0, 0), (0.5 - t) * 0.14)
	end
	return color:Lerp(Color3.new(1, 1, 1), (t - 0.5) * 0.1)
end

return Blocks
