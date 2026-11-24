-- 베드워즈 맵 3종.
-- 구조: 팀 섬 4개(뒤쪽 집, 자원 생성기, 상점 가판대, 앞쪽 침대), 다이아몬드 섬 4개, 에메랄드가 있는 가운데 섬, 먼 풍경.
-- 팀 섬 좌표계: s = 옆, f = 바깥쪽(가운데에서 멀어지는 방향).
local Shapes = require(script.Parent.Shapes)

local TEAM_DIRS = {
	{ id = "red", ox = 0, oz = 1 },
	{ id = "blue", ox = 1, oz = 0 },
	{ id = "green", ox = 0, oz = -1 },
	{ id = "yellow", ox = -1, oz = 0 },
}

local TEAM_DISTANCE = 36
local DIAMOND_DISTANCE = 22
local CENTER_RADIUS = 13

type Theme = {
	top: string,
	fill: string,
	deep: string,
	floor: string,
	border: string,
	bedFloor: string,
	genPad: string,
	flora: { string },
	floraChance: number,
	house: (api: any, at: (number, number) -> (number, number), dir: any, team: string, rng: any) -> (),
	tree: (api: any, x: number, y: number, z: number, rng: any) -> (),
	lamp: (api: any, x: number, y: number, z: number) -> (),
	diamond: (api: any, x: number, z: number, rng: any) -> (),
	center: (api: any, rng: any) -> (),
	scenery: (api: any, rng: any) -> (),
}

local GRASSY = { grass = true, snow_grass = true, sand = true, mycelium = true }

-- 방향이 있는 집 짓기: 로컬 직사각형(s1..s2, f1..f2)을 월드 좌표로 바꿔 Shapes.house 호출
local function orientedHouse(api, at, dir, s1: number, f1: number, s2: number, f2: number, y: number, opts)
	local ax, az = at(s1, f1)
	local bx, bz = at(s2, f2)
	-- 문은 가운데(앞쪽 = f1 쪽)를 향함
	local door
	if dir.oz == 1 then
		door = "z-"
	elseif dir.oz == -1 then
		door = "z+"
	elseif dir.ox == 1 then
		door = "x-"
	else
		door = "x+"
	end
	local o = table.clone(opts)
	o.door = door
	Shapes.house(api, ax, y, az, bx, bz, o)
end

local function teamIsland(api, theme: Theme, dir, rng)
	local cx, cz = dir.ox * TEAM_DISTANCE, dir.oz * TEAM_DISTANCE
	local at = Shapes.frame(cx, cz, dir.ox, dir.oz)
	local ix, iz = at(0, 2)
	local alongX = dir.ox ~= 0
	Shapes.island(api, ix, 0, iz, {
		rx = if alongX then 12 else 10,
		rz = if alongX then 10 else 12,
		top = theme.top,
		fill = theme.fill,
		deep = theme.deep,
		depth = 8,
		edge = 0.14,
		ores = true,
		drips = 0.05,
		rng = rng,
	})

	-- 기지 바닥
	for s = -5, 5 do
		for f = -4, 8 do
			local x, z = at(s, f)
			local border = math.abs(s) == 5 or f == -4 or f == 8
			api.set(x, 0, z, if border then theme.border else theme.floor)
		end
	end
	for s = -1, 1 do
		for f = -4, -1 do
			local x, z = at(s, f)
			api.set(x, 0, z, theme.bedFloor)
		end
		for f = 5, 7 do
			local x, z = at(s, f)
			api.set(x, 0, z, theme.genPad)
		end
	end
	-- 생성기 주변 랜턴
	for _, s in { -2, 2 } do
		local x, z = at(s, 7)
		theme.lamp(api, x, 0, z)
	end

	-- 상점/강화 가판대 (팀 색 천막)
	local wool = "wool_" .. dir.id
	for _, side in { -1, 1 } do
		local x1, z1 = at(side * 4, 0)
		local x2, z2 = at(side * 6, 2)
		Shapes.stall(api, x1, 0, z1, x2, z2, wool, "wool_white")
	end

	-- 뒤쪽 집
	theme.house(api, at, dir, dir.id, rng)

	-- 앞쪽 나무와 가로등
	for _, side in { -1, 1 } do
		local tx, tz = at(side * 7, -6)
		theme.tree(api, tx, 0, tz, rng)
		local lx, lz = at(side * 2, -5)
		theme.lamp(api, lx, 0, lz)
		local bx, bz = at(side * 8, 5)
		theme.tree(api, bx, 0, bz, rng)
	end

	local function cell(s: number, y: number, f: number): { number }
		local x, z = at(s, f)
		return { x, y, z }
	end
	-- 팀 상자(팀원끼리 같이 씀)와 개인 상자(어느 개인 상자에서 열어도 내 것)
	local chestCell = cell(-2, 1, 3)
	local enderCell = cell(2, 1, 3)
	api.set(chestCell[1], chestCell[2], chestCell[3], "chest")
	api.set(enderCell[1], enderCell[2], enderCell[3], "ender_chest")
	api.marker("team", {
		chest = chestCell,
		ender = enderCell,
		team = dir.id,
		spawn = cell(0, 1, 1),
		lookAt = { 0, 1, 0 },
		gen = cell(0, 1, 6),
		bedFoot = cell(0, 1, -2),
		bedHead = cell(0, 1, -3),
		shop = cell(-5, 1, 1),
		upgrades = cell(5, 1, 1),
		npcLook = cell(0, 1, 1),
		center = cell(0, 0, 2),
	})

	-- 풀/꽃 (기지 바닥 밖)
	local x1, z1 = at(-12, -12)
	local x2, z2 = at(12, 16)
	Shapes.flora(api, rng, x1, z1, x2, z2, 6, -2, GRASSY, theme.flora, theme.floraChance)
end

local function diamondIsland(api, theme: Theme, x: number, z: number, rng)
	Shapes.island(api, x, 0, z, { rx = 5, rz = 5, top = theme.top, fill = theme.fill, deep = theme.deep, depth = 5, edge = 0.18, ores = true, drips = 0.06, rng = rng })
	for dx = -1, 1 do
		for dz = -1, 1 do
			api.set(x + dx, 0, z + dz, theme.border)
		end
	end
	api.set(x, 0, z, "diamond_block")
	theme.diamond(api, x, z, rng)
	api.marker("diamond", { x, 1, z })
end

local function centerIsland(api, theme: Theme, rng)
	Shapes.island(api, 0, 0, 0, { rx = CENTER_RADIUS, rz = CENTER_RADIUS, top = theme.top, fill = theme.fill, deep = theme.deep, depth = 10, edge = 0.12, ores = true, drips = 0.05, rng = rng })
	-- 가운데 두 층 단상과 반 블록 계단
	for dx = -4, 4 do
		for dz = -4, 4 do
			local ring = math.max(math.abs(dx), math.abs(dz))
			if ring <= 3 then
				api.set(dx, 1, dz, theme.floor)
				api.set(dx, 2, dz, if ring == 3 then theme.border else theme.floor)
			elseif ring == 4 then
				api.set(dx, 1, dz, theme.border .. "_slab")
			end
		end
	end
	api.set(0, 2, -2, "emerald_block")
	api.set(0, 2, 2, "emerald_block")
	api.marker("emerald", { 0, 3, -2 })
	api.marker("emerald", { 0, 3, 2 })
	theme.center(api, rng)
	Shapes.flora(api, rng, -15, -15, 15, 15, 8, -2, GRASSY, theme.flora, theme.floraChance)
end

local function build(theme: Theme)
	return function(api, rng)
		for _, dir in TEAM_DIRS do
			teamIsland(api, theme, dir, rng)
		end
		for _, sx in { -1, 1 } do
			for _, sz in { -1, 1 } do
				diamondIsland(api, theme, sx * DIAMOND_DISTANCE, sz * DIAMOND_DISTANCE, rng)
			end
		end
		centerIsland(api, theme, rng)
		theme.scenery(api, rng)
	end
end

local function farIslands(api, rng, theme: Theme, count: number, decorate: (number, number, number) -> ())
	for i = 1, count do
		local angle = (i - 0.5) * (2 * math.pi / count) + (rng:next() - 0.5) * 0.3
		local dist = rng:int(62, 72)
		local x = math.round(math.sin(angle) * dist)
		local z = math.round(math.cos(angle) * dist)
		local y = rng:int(-10, 6)
		local r = rng:int(3, 5)
		Shapes.island(api, x, y, z, { rx = r, rz = r, top = theme.top, fill = theme.fill, deep = theme.deep, depth = 5, edge = 0.25, drips = 0.1, rng = rng })
		decorate(x, y, z)
	end
end

----------------------------------------------------------------------------
-- 1. 벚꽃 정원: 일본식 정원, 벚나무, 석등, 도리이, 가운데 정자
----------------------------------------------------------------------------
local sakura: Theme = {
	top = "grass",
	fill = "dirt",
	deep = "stone",
	floor = "polished_stone",
	border = "stone_bricks",
	bedFloor = "cherry_planks",
	genPad = "gold_block",
	flora = { "tall_grass", "tall_grass", "flower_pink", "flower_white", "fern", "petals" },
	floraChance = 0.22,
	house = function(api, at, dir, team, rng)
		orientedHouse(api, at, dir, -5, 10, 5, 14, 0, {
			wall = "cherry_planks",
			corner = "cherry_log",
			floor = "dark_planks",
			roof = "dark_planks",
			roofSlab = "dark_planks_slab",
			beam = "dark_log",
			window = "stained_white",
			height = 4,
			accent = "wool_" .. team,
		})
		-- 집 앞 팀 깃발
		for _, s in { -3, 3 } do
			local x, z = at(s, 9)
			Shapes.banner(api, x, 4, z, "wool_" .. team, 2)
		end
		local _ = rng
	end,
	tree = function(api, x, y, z, rng)
		Shapes.sakura(api, x, y, z, rng, true)
	end,
	lamp = function(api, x, y, z)
		Shapes.stoneLantern(api, x, y, z)
	end,
	diamond = function(api, x, z, rng)
		local ax = if x > 0 then -1 else 1
		local az = if z > 0 then -1 else 1
		Shapes.torii(api, x + ax * 3, 0, z, "z", 2, 4)
		Shapes.stoneLantern(api, x - ax * 3, 0, z + az * 2)
		local _ = rng
	end,
	center = function(api, rng)
		-- 정자: 네 기둥과 3단 지붕
		for _, c in { { -5, -5 }, { 5, -5 }, { -5, 5 }, { 5, 5 } } do
			for i = 1, 7 do
				api.set(c[1], i, c[2], "cherry_log")
			end
		end
		for level = 0, 2 do
			local r = 6 - level * 2
			for dx = -r, r do
				for dz = -r, r do
					local ring = math.max(math.abs(dx), math.abs(dz))
					if ring >= r - 1 then
						local corner = math.abs(dx) == r and math.abs(dz) == r
						api.set(dx, 8 + level * 2 + (if corner then 1 else 0), dz, if ring == r then "dark_planks_slab" else "dark_planks")
					end
				end
			end
		end
		api.set(0, 14, 0, "gold_block")
		api.set(0, 7, 0, "lantern")
		-- 네 방향 도리이와 석등
		for _, d in { { 0, 1 }, { 0, -1 }, { 1, 0 }, { -1, 0 } } do
			local x, z = d[1] * 10, d[2] * 10
			Shapes.torii(api, x, 0, z, if d[1] == 0 then "x" else "z", 2, 5)
			Shapes.stoneLantern(api, x + d[2] * 4, 0, z + d[1] * 4)
			Shapes.stoneLantern(api, x - d[2] * 4, 0, z - d[1] * 4)
		end
		-- 연못
		for dx = -2, 2 do
			for dz = -1, 1 do
				api.set(8 + dx, 0, -5 + dz, "water")
				api.set(8 + dx, -1, -5 + dz, "clay_block")
			end
		end
		api.set(7, 1, -5, "carpet_lime")
		for _, p in { { -8, -8 }, { 8, 8 }, { -8, 8 } } do
			Shapes.sakura(api, p[1], 0, p[2], rng, true)
		end
	end,
	scenery = function(api, rng)
		farIslands(api, rng, { top = "grass", fill = "dirt", deep = "stone" } :: any, 7, function(x, y, z)
			Shapes.sakura(api, x, y, z, rng, true)
		end)
		for i = 1, 6 do
			local angle = i * math.pi / 3 + 0.4
			Shapes.cloud(api, math.round(math.sin(angle) * 80), rng:int(22, 28), math.round(math.cos(angle) * 80), rng)
		end
	end,
}

----------------------------------------------------------------------------
-- 2. 사막 신전: 사암 집, 야자수와 선인장, 오아시스, 기둥 신전과 오벨리스크
----------------------------------------------------------------------------
local desert: Theme = {
	top = "sand",
	fill = "sandstone",
	deep = "red_sandstone",
	floor = "smooth_sandstone",
	border = "sandstone",
	bedFloor = "red_sandstone",
	genPad = "gold_block",
	flora = { "dead_bush", "dead_bush", "tall_grass" },
	floraChance = 0.07,
	house = function(api, at, dir, team, rng)
		orientedHouse(api, at, dir, -5, 10, 5, 14, 0, {
			wall = "sandstone",
			corner = "carved_sandstone",
			floor = "smooth_sandstone",
			roof = "smooth_sandstone",
			window = "glass",
			height = 4,
			flat = true,
			trim = "sandstone_wall",
			accent = "terracotta_" .. team,
		})
		for _, s in { -3, 3 } do
			local x, z = at(s, 9)
			Shapes.banner(api, x, 4, z, "wool_" .. team, 3)
		end
		local px, pz = at(-8, 11)
		Shapes.palm(api, px, 0, pz, rng, { dir.ox, dir.oz })
	end,
	tree = function(api, x, y, z, rng)
		if rng:next() < 0.6 then
			Shapes.palm(api, x, y, z, rng, { 1, 0 })
		else
			Shapes.cactus(api, x, y, z, rng:int(2, 4))
		end
	end,
	lamp = function(api, x, y, z)
		api.set(x, y + 1, z, "sandstone_wall")
		api.set(x, y + 2, z, "sandstone_wall")
		api.set(x, y + 3, z, "torch")
	end,
	diamond = function(api, x, z, rng)
		local ax = if x > 0 then 1 else -1
		local az = if z > 0 then 1 else -1
		-- 오벨리스크
		for i = 1, 6 do
			api.set(x + ax * 3, i, z + az * 2, if i == 3 then "carved_sandstone" else "smooth_sandstone")
		end
		api.set(x + ax * 3, 7, z + az * 2, "gold_block")
		Shapes.cactus(api, x - ax * 3, 0, z + az * 2, rng:int(2, 3))
	end,
	center = function(api, rng)
		-- 기둥 신전 (둥글게 선 기둥 + 위를 잇는 들보)
		for _, p in Shapes.ring(12, 7, math.pi / 12) do
			for i = 1, 5 do
				api.set(p.x, i, p.z, if i == 1 or i == 5 then "carved_sandstone" else "quartz_pillar")
			end
		end
		for _, p in Shapes.ring(48, 7, 0) do
			api.set(p.x, 6, p.z, "smooth_sandstone_slab")
		end
		-- 네 귀퉁이 오벨리스크
		for _, c in { { -8, -8 }, { 8, -8 }, { -8, 8 }, { 8, 8 } } do
			local x, z = c[1], c[2]
			for i = 1, 7 do
				api.set(x, i, z, if i == 4 then "carved_sandstone" else "smooth_sandstone")
			end
			api.set(x, 8, z, "gold_block")
		end
		-- 오아시스
		for dx = -2, 2 do
			for dz = -2, 2 do
				if math.abs(dx) + math.abs(dz) <= 3 then
					api.set(-9 + dx, 0, 3 + dz, "water")
					api.set(-9 + dx, -1, 3 + dz, "sand")
				end
			end
		end
		Shapes.palm(api, -11, 0, 0, rng, { -1, 0 })
		Shapes.palm(api, -7, 0, 6, rng, { 0, 1 })
	end,
	scenery = function(api, rng)
		-- 멀리 보이는 붉은 메사(층층이 쌓인 바위산)
		for i = 1, 6 do
			local angle = (i - 0.5) * math.pi / 3
			local x, z = math.round(math.sin(angle) * 68), math.round(math.cos(angle) * 68)
			local base = rng:int(-14, -8)
			-- 큰 파트 몇 개로 쌓은 층층 바위산 (멀리 있는 배경이라 파트 수를 아낌)
			for layer = 0, rng:int(4, 6) do
				local r = 7 - layer
				api.big(x - r, base + layer * 2, z - r, x + r, base + layer * 2, z + r, if layer % 2 == 0 then "red_sandstone" else "terracotta_orange")
				api.big(x - r, base + layer * 2 + 1, z - r, x + r, base + layer * 2 + 1, z + r, "terracotta_yellow")
			end
		end
	end,
}

----------------------------------------------------------------------------
-- 3. 얼음 성채: 석재 벽돌 오두막과 눈 쌓인 지붕, 가문비나무, 얼음 가시, 가운데 성
----------------------------------------------------------------------------
local frost: Theme = {
	top = "snow_grass",
	fill = "dirt",
	deep = "stone",
	floor = "stone_bricks",
	border = "cobble",
	bedFloor = "packed_ice",
	genPad = "iron_block",
	flora = { "fern", "tall_grass", "flower_blue" },
	floraChance = 0.08,
	house = function(api, at, dir, team, rng)
		orientedHouse(api, at, dir, -5, 10, 5, 14, 0, {
			wall = "stone_bricks",
			corner = "dark_log",
			floor = "dark_planks",
			roof = "dark_planks",
			roofSlab = "dark_planks_slab",
			beam = "dark_log",
			window = "glass",
			height = 4,
			accent = "wool_" .. team,
		})
		-- 지붕 위 눈
		local x1, z1 = at(-7, 8)
		local x2, z2 = at(7, 16)
		for x = math.min(x1, x2), math.max(x1, x2) do
			for z = math.min(z1, z2), math.max(z1, z2) do
				local y = Shapes.topAt(api, x, z, 14, 5)
				if y and api.get(x, y + 1, z) == nil and rng:next() < 0.85 then
					api.set(x, y + 1, z, "carpet_white")
				end
			end
		end
		for _, s in { -3, 3 } do
			local x, z = at(s, 9)
			Shapes.banner(api, x, 4, z, "wool_" .. team, 2)
		end
	end,
	tree = function(api, x, y, z, rng)
		if rng:next() < 0.75 then
			Shapes.spruce(api, x, y, z, rng, true)
		else
			Shapes.spike(api, x, y, z, rng:int(4, 6), "packed_ice")
		end
	end,
	lamp = function(api, x, y, z)
		api.set(x, y + 1, z, "cobble_wall")
		api.set(x, y + 2, z, "cobble_wall")
		api.set(x, y + 3, z, "soul_lantern")
	end,
	diamond = function(api, x, z, rng)
		local ax = if x > 0 then 1 else -1
		local az = if z > 0 then 1 else -1
		Shapes.spike(api, x + ax * 3, 0, z + az * 3, rng:int(5, 7), "ice")
		Shapes.spruce(api, x - ax * 3, 0, z + az * 2, rng, true)
	end,
	center = function(api, rng)
		-- 네 귀퉁이 탑 + 짧은 성벽 (네 방향은 비워 둬서 다리를 놓을 수 있게)
		for _, c in { { -8, -8 }, { 8, -8 }, { -8, 8 }, { 8, 8 } } do
			Shapes.tower(api, c[1], 0, c[2], 2, 7, "stone_bricks", { roof = "dark_planks", floor = "dark_planks", window = "glass" })
			Shapes.wall(api, c[1], c[2] - math.sign(c[2]) * 3, c[1], c[2] - math.sign(c[2]) * 5, 0, 3, "cobble", "stone_bricks")
			Shapes.wall(api, c[1] - math.sign(c[1]) * 3, c[2], c[1] - math.sign(c[1]) * 5, c[2], 0, 3, "cobble", "stone_bricks")
		end
		-- 얼어붙은 분수 대신 얼음 기둥 장식
		for _, p in Shapes.ring(4, 7, math.pi / 4) do
			Shapes.spike(api, p.x, 0, p.z, rng:int(3, 5), "packed_ice")
		end
		-- 탑과 다리 길(네 방향) 사이에 가문비나무
		for _, p in Shapes.ring(8, 11, math.pi / 8) do
			Shapes.spruce(api, p.x, 0, p.z, rng, true)
		end
	end,
	scenery = function(api, rng)
		-- 멀리 솟은 얼음 산
		for i = 1, 7 do
			local angle = (i - 0.5) * (2 * math.pi / 7)
			local x, z = math.round(math.sin(angle) * 70), math.round(math.cos(angle) * 70)
			local base = rng:int(-16, -10)
			for layer = 0, 8 do
				local r = 8 - layer
				api.big(x - r, base + layer * 2, z - r, x + r, base + layer * 2 + 1, z + r, if layer > 5 then "snow" else "packed_ice")
			end
		end
	end,
}

return {
	{
		id = "sakura",
		name = "벚꽃 정원",
		description = "벚나무와 도리이, 석등이 있는 일본식 정원",
		seed = 1101,
		build = build(sakura),
	},
	{
		id = "desert",
		name = "사막 신전",
		description = "야자수 오아시스와 기둥 신전이 있는 모래섬",
		seed = 2203,
		build = build(desert),
	},
	{
		id = "frost",
		name = "얼음 성채",
		description = "눈 덮인 오두막과 가문비나무, 얼음 성",
		seed = 3307,
		build = build(frost),
	},
}
