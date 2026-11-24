-- 블록 종류 정의. 색, 재질, 부수는 데 걸리는 시간(초, 맨손 기준), 알맞은 도구를 정합니다.
-- textureId 를 넣으면 블록 여섯 면에 그 이미지를 붙입니다(직접 만든 픽셀 텍스처를 올려 쓰세요).
local Config = require(script.Parent.Config)

local Blocks = {}

export type BlockDef = {
	id: string,
	name: string,
	color: Color3,
	material: Enum.Material,
	hardness: number,
	tool: string?,
	transparency: number?,
	canCollide: boolean?,
	unbreakable: boolean?,
	blastProof: boolean?,
	kill: boolean?,
	light: number?,
	size: Vector3?, -- 블록 단위 크기(기본 1,1,1)
	team: string?,
	bed: boolean?,
	tnt: boolean?,
	chest: boolean?,
	noItem: boolean?, -- 아이템으로 존재하지 않는 블록
	textureId: string?,
}

local defs: { [string]: BlockDef } = {}
Blocks.defs = defs

local M = Enum.Material
local function rgb(r: number, g: number, b: number): Color3
	return Color3.fromRGB(r, g, b)
end

local function add(id: string, data: { [string]: any })
	data.id = id
	data.hardness = data.hardness or 1
	data.material = data.material or M.SmoothPlastic
	defs[id] = data :: any
end

-- 자연 블록
add("grass", { name = "잔디 블록", color = rgb(95, 159, 53), hardness = 0.6 })
add("dirt", { name = "흙", color = rgb(134, 96, 67), hardness = 0.5 })
add("stone", { name = "돌", color = rgb(128, 128, 128), hardness = 2.2, tool = "pickaxe" })
add("cobble", { name = "조약돌", color = rgb(108, 108, 110), hardness = 2.2, tool = "pickaxe" })
add("stone_bricks", { name = "석재 벽돌", color = rgb(120, 120, 122), hardness = 2.2, tool = "pickaxe" })
add("bricks", { name = "벽돌", color = rgb(150, 76, 60), hardness = 2.2, tool = "pickaxe", material = M.Brick })
add("planks", { name = "나무 판자", color = rgb(168, 135, 84), hardness = 1.4, tool = "axe", material = M.WoodPlanks })
add("dark_planks", { name = "짙은 나무 판자", color = rgb(92, 66, 40), hardness = 1.4, tool = "axe", material = M.WoodPlanks })
add("log", { name = "원목", color = rgb(104, 82, 50), hardness = 1.8, tool = "axe", material = M.Wood })
add("leaves", { name = "나뭇잎", color = rgb(58, 130, 42), hardness = 0.25, tool = "shears" })
add("sakura_leaves", { name = "벚꽃잎", color = rgb(244, 172, 206), hardness = 0.25, tool = "shears" })
add("sand", { name = "모래", color = rgb(222, 210, 160), hardness = 0.5 })
add("sandstone", { name = "사암", color = rgb(214, 196, 140), hardness = 1.2, tool = "pickaxe" })
add("red_sandstone", { name = "붉은 사암", color = rgb(190, 110, 60), hardness = 1.2, tool = "pickaxe" })
add("cactus", { name = "선인장", color = rgb(76, 140, 52), hardness = 0.4 })
add("gravel", { name = "자갈", color = rgb(140, 130, 128), hardness = 0.6 })
add("snow", { name = "눈 블록", color = rgb(242, 248, 252), hardness = 0.3 })
add("ice", { name = "얼음", color = rgb(150, 190, 245), hardness = 0.5, transparency = 0.2, material = M.Ice })
add("packed_ice", { name = "단단한 얼음", color = rgb(125, 165, 230), hardness = 0.8, tool = "pickaxe" })
add("quartz", { name = "석영 블록", color = rgb(236, 230, 222), hardness = 1.6, tool = "pickaxe" })
add("basalt", { name = "현무암", color = rgb(66, 64, 70), hardness = 2.2, tool = "pickaxe" })
add("magma", { name = "마그마 블록", color = rgb(160, 62, 28), hardness = 1, tool = "pickaxe", material = M.CrackedLava })
add("mushroom_red", { name = "붉은 버섯 블록", color = rgb(196, 44, 40), hardness = 0.4, tool = "axe" })
add("mushroom_stem", { name = "버섯 줄기", color = rgb(224, 214, 196), hardness = 0.4, tool = "axe" })
add("hay", { name = "건초 더미", color = rgb(204, 172, 52), hardness = 0.5 })
add("lamp", { name = "조명 블록", color = rgb(255, 222, 140), hardness = 0.5, material = M.Neon, light = 16 })
add("iron_block", { name = "철 블록", color = rgb(218, 218, 222), hardness = 3, tool = "pickaxe", material = M.Metal })
add("gold_block", { name = "금 블록", color = rgb(250, 208, 60), hardness = 3, tool = "pickaxe", material = M.Metal })
add("diamond_block", { name = "다이아몬드 블록", color = rgb(98, 225, 215), hardness = 3, tool = "pickaxe" })
add("emerald_block", { name = "에메랄드 블록", color = rgb(40, 200, 90), hardness = 3, tool = "pickaxe" })

-- 장애물/특수 블록
add("glass", { name = "유리", color = rgb(210, 235, 250), hardness = 0.3, transparency = 0.6, material = M.Glass })
add("blast_glass", {
	name = "방폭 유리",
	color = rgb(185, 215, 240),
	hardness = 0.6,
	transparency = 0.45,
	material = M.Glass,
	blastProof = true,
})
add("cage_glass", {
	name = "출발 유리관",
	color = rgb(200, 230, 255),
	transparency = 0.55,
	material = M.Glass,
	unbreakable = true,
	noItem = true,
})
add("obsidian", { name = "흑요석", color = rgb(34, 24, 48), hardness = 25, tool = "pickaxe", blastProof = true })
add("bedrock", { name = "기반암", color = rgb(52, 52, 56), unbreakable = true, blastProof = true, noItem = true })
add("lava", {
	name = "용암",
	color = rgb(255, 112, 20),
	material = M.Neon,
	transparency = 0.05,
	canCollide = false,
	unbreakable = true,
	blastProof = true,
	kill = true,
	noItem = true,
})
add("water", {
	name = "물",
	color = rgb(55, 110, 220),
	material = M.Glass,
	transparency = 0.45,
	canCollide = false,
	unbreakable = true,
	blastProof = true,
	noItem = true,
})
add("tnt", { name = "TNT", color = rgb(214, 52, 40), hardness = 0.05, tnt = true })
add("chest", {
	name = "상자",
	color = rgb(160, 110, 50),
	material = M.WoodPlanks,
	unbreakable = true,
	blastProof = true,
	chest = true,
	noItem = true,
	size = Vector3.new(0.86, 0.86, 0.86),
})

-- 팀 색 블록(양털, 단단한 점토)과 침대
for _, team in Config.Teams do
	add("wool_" .. team.id, {
		name = team.name .. " 양털",
		color = team.color,
		hardness = 0.7,
		tool = "shears",
		material = M.Fabric,
		team = team.id,
	})
	add("clay_" .. team.id, {
		name = team.name .. " 단단한 점토",
		color = team.color:Lerp(Color3.new(0.35, 0.3, 0.3), 0.35),
		hardness = 1.6,
		tool = "pickaxe",
		team = team.id,
	})
	add("bed_" .. team.id, {
		name = team.name .. " 침대",
		color = team.color,
		hardness = 0.4,
		bed = true,
		team = team.id,
		noItem = true,
		blastProof = true,
		size = Vector3.new(1, 0.56, 1),
	})
end
add("wool_white", { name = "흰 양털", color = rgb(234, 236, 236), hardness = 0.7, tool = "shears", material = M.Fabric })

-- TNT 런 바닥에 쓰는 색 블록
local COLORS: { { string } } = {
	{ "white", "흰색" },
	{ "black", "검은색" },
	{ "red", "빨간색" },
	{ "orange", "주황색" },
	{ "yellow", "노란색" },
	{ "lime", "연두색" },
	{ "cyan", "청록색" },
	{ "blue", "파란색" },
	{ "purple", "보라색" },
	{ "pink", "분홍색" },
}
local COLOR_VALUES: { [string]: Color3 } = {
	white = rgb(232, 234, 235),
	black = rgb(32, 34, 40),
	red = rgb(220, 50, 48),
	orange = rgb(240, 130, 30),
	yellow = rgb(245, 205, 40),
	lime = rgb(120, 205, 40),
	cyan = rgb(30, 160, 170),
	blue = rgb(50, 70, 190),
	purple = rgb(130, 50, 180),
	pink = rgb(235, 130, 170),
}
Blocks.ColorNames = {}
for _, entry in COLORS do
	local id = "color_" .. entry[1]
	add(id, { name = entry[2] .. " 블록", color = COLOR_VALUES[entry[1]], hardness = 0.6 })
	table.insert(Blocks.ColorNames, entry[1])
end

function Blocks.get(id: string): BlockDef?
	return defs[id]
end

function Blocks.exists(id: string): boolean
	return defs[id] ~= nil
end

-- 블록 위치마다 색을 살짝 다르게 해서 픽셀 블록 느낌을 냅니다.
function Blocks.shade(def: BlockDef, x: number, y: number, z: number): Color3
	local h = (x * 73856093 + y * 19349663 + z * 83492791) % 1000
	if h < 0 then
		h += 1000
	end
	local t = h / 1000 -- 0..1
	local color = def.color
	if t < 0.5 then
		return color:Lerp(Color3.new(0, 0, 0), (0.5 - t) * 0.16)
	end
	return color:Lerp(Color3.new(1, 1, 1), (t - 0.5) * 0.12)
end

return Blocks
