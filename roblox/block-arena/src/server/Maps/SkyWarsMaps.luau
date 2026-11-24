-- 스카이워즈 맵 3종: 시작 섬 8개(작은 집 + 상자 2개), 중간 섬 4개(폐허 + 상자), 큰 가운데 섬(좋은 상자 4개).
local Shapes = require(script.Parent.Shapes)

local ISLANDS = 8
local ISLAND_DISTANCE = 30
local MID_DISTANCE = 16

type Theme = {
	top: string,
	fill: string,
	deep: string,
	flora: { string },
	floraChance: number,
	hut: (api: any, at: (number, number) -> (number, number), ox: number, oz: number, rng: any) -> (),
	tree: (api: any, x: number, y: number, z: number, rng: any) -> (),
	ruin: (api: any, x: number, y: number, z: number, rng: any) -> (),
	center: (api: any, rng: any) -> (),
	sky: (api: any, rng: any) -> (),
	centerChestRadius: number,
}

local GROUND = { grass = true, mycelium = true, basalt = true, snow_grass = true }

local function chest(api, x: number, y: number, z: number, tier: string)
	api.set(x, y, z, "chest")
	api.marker("chest", { x = x, y = y, z = z, tier = tier })
end

-- 바깥쪽 방향을 가장 가까운 축으로 맞춤 (건물이 반듯하게 서도록)
local function axisDir(angle: number): (number, number)
	local sx, cz = math.sin(angle), math.cos(angle)
	if math.abs(sx) > math.abs(cz) + 0.01 then
		return if sx > 0 then 1 else -1, 0
	end
	return 0, if cz > 0 then 1 else -1
end

local function doorFacing(ox: number, oz: number): string
	if oz == 1 then
		return "z-"
	elseif oz == -1 then
		return "z+"
	elseif ox == 1 then
		return "x-"
	end
	return "x+"
end

local function startIsland(api, theme: Theme, index: number, rng)
	local angle = (index - 1) * (2 * math.pi / ISLANDS)
	local cx = math.round(math.sin(angle) * ISLAND_DISTANCE) + 0
	local cz = math.round(math.cos(angle) * ISLAND_DISTANCE) + 0
	local ox, oz = axisDir(angle)
	local at = Shapes.frame(cx, cz, ox, oz)
	Shapes.island(api, cx, 0, cz, { rx = 6, rz = 6, top = theme.top, fill = theme.fill, deep = theme.deep, depth = 6, edge = 0.16, ores = true, drips = 0.08, rng = rng })
	-- 집(뒤쪽) 안에 상자 하나, 옆에 상자 하나
	theme.hut(api, at, ox, oz, rng)
	local hx, hz = at(1, 4)
	chest(api, hx, 1, hz, "island")
	local sx, sz = at(3, 0)
	chest(api, sx, 1, sz, "island")
	local tx, tz = at(-4, -2)
	theme.tree(api, tx, 0, tz, rng)
	Shapes.flora(api, rng, cx - 7, cz - 7, cx + 7, cz + 7, 3, -2, GROUND, theme.flora, theme.floraChance)
	-- 유리관 자리(스폰 주변 3x3, 높이 3)는 비워 둠
	for dx = -1, 1 do
		for dz = -1, 1 do
			for y = 1, 3 do
				local id = api.get(cx + dx, y, cz + dz)
				if id and id ~= "chest" then
					api.clear(cx + dx, y, cz + dz)
				end
			end
		end
	end
	api.marker("spawn", { x = cx, y = 1, z = cz })
end

local function midIsland(api, theme: Theme, index: number, rng)
	local angle = math.pi / 4 + (index - 1) * (math.pi / 2)
	local cx = math.round(math.sin(angle) * MID_DISTANCE) + 0
	local cz = math.round(math.cos(angle) * MID_DISTANCE) + 0
	Shapes.island(api, cx, 1, cz, { rx = 4, rz = 4, top = theme.top, fill = theme.fill, deep = theme.deep, depth = 5, edge = 0.2, ores = true, drips = 0.1, rng = rng })
	theme.ruin(api, cx, 1, cz, rng)
	chest(api, cx, 2, cz, "island")
end

local function build(theme: Theme)
	return function(api, rng)
		for i = 1, ISLANDS do
			startIsland(api, theme, i, rng)
		end
		for i = 1, 4 do
			midIsland(api, theme, i, rng)
		end
		Shapes.island(api, 0, 2, 0, { rx = 11, rz = 11, top = theme.top, fill = theme.fill, deep = theme.deep, depth = 10, edge = 0.12, ores = true, drips = 0.06, rng = rng })
		theme.center(api, rng)
		for _, p in Shapes.ring(4, theme.centerChestRadius, math.pi / 4) do
			chest(api, p.x, 3, p.z, "center")
		end
		Shapes.flora(api, rng, -13, -13, 13, 13, 6, 0, GROUND, theme.flora, theme.floraChance)
		theme.sky(api, rng)
	end
end

----------------------------------------------------------------------------
-- 1. 구름 정원: 오두막, 참나무/자작나무, 가운데 큰 나무와 나선 계단, 구름
----------------------------------------------------------------------------
local cloud: Theme = {
	top = "grass",
	fill = "dirt",
	deep = "stone",
	flora = { "tall_grass", "tall_grass", "flower_red", "flower_yellow", "flower_white", "fern" },
	floraChance = 0.25,
	centerChestRadius = 4,
	hut = function(api, at, ox, oz, _rng)
		local ax, az = at(-2, 2)
		local bx, bz = at(2, 5)
		Shapes.house(api, ax, 0, az, bx, bz, {
			wall = "planks",
			corner = "log",
			floor = "planks",
			roof = "bricks",
			roofSlab = "bricks_slab",
			window = "glass",
			door = doorFacing(ox, oz),
			height = 3,
			interior = false,
		})
		local lx, lz = at(-1, 4)
		api.set(lx, 1, lz, "lantern")
	end,
	tree = function(api, x, y, z, rng)
		if rng:next() < 0.5 then
			Shapes.oak(api, x, y, z, rng)
		else
			Shapes.birch(api, x, y, z, rng)
		end
	end,
	ruin = function(api, x, y, z, rng)
		for _, c in { { -2, -2 }, { 2, -2 }, { -2, 2 }, { 2, 2 } } do
			Shapes.ruinPillar(api, x + c[1], y, z + c[2], 4, "stone_bricks", rng)
		end
		Shapes.lampPost(api, x + 1, y, z - 1, "fence", "lantern")
	end,
	center = function(api, rng)
		-- 큰 나무: 3x3 줄기, 뿌리, 넓은 잎
		for dx = -1, 1 do
			for dz = -1, 1 do
				for y = 3, 15 do
					api.set(dx, y, dz, "log")
				end
			end
		end
		for _, d in { { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 } } do
			api.set(d[1], 3, d[2], "log")
		end
		for _, b in { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } } do
			for k = 1, 4 do
				api.set(b[1] * (1 + k), 12 + (k // 2), b[2] * (1 + k), "log")
			end
		end
		Shapes.disc(api, 0, 15, 0, 6.5, "leaves")
		Shapes.disc(api, 0, 16, 0, 7, "leaves")
		Shapes.disc(api, 0, 17, 0, 5.5, "leaves")
		Shapes.disc(api, 0, 18, 0, 3.5, "leaves")
		-- 나무 위 전망대로 오르는 나선 계단
		local steps = { { 2, -1 }, { 2, 0 }, { 2, 1 }, { 1, 2 }, { 0, 2 }, { -1, 2 }, { -2, 1 }, { -2, 0 }, { -2, -1 }, { -1, -2 } }
		for i, p in steps do
			api.set(p[1], 2 + i, p[2], "planks")
		end
		for dx = -3, 3 do
			for dz = -3, 3 do
				local ring = math.max(math.abs(dx), math.abs(dz))
				if ring >= 2 then
					api.set(dx, 12, dz, "planks")
				end
			end
		end
		Shapes.fenceLine(api, -3, -3, 3, -3, 13, "fence")
		Shapes.fenceLine(api, 3, -2, 3, 3, 13, "fence")
		Shapes.fenceLine(api, 2, 3, -3, 3, 13, "fence")
		api.set(0, 4, -2, "lantern")
		-- 작은 연못과 꽃밭
		for dx = -1, 1 do
			for dz = -1, 1 do
				api.set(7 + dx, 2, -6 + dz, "water")
				api.set(7 + dx, 1, -6 + dz, "clay_block")
			end
		end
		Shapes.oak(api, -7, 2, 6, rng)
		Shapes.birch(api, 7, 2, 6, rng)
	end,
	sky = function(api, rng)
		for i = 1, 10 do
			local angle = i * (2 * math.pi / 10) + 0.2
			local dist = rng:int(42, 52)
			Shapes.cloud(api, math.round(math.sin(angle) * dist), rng:int(9, 16), math.round(math.cos(angle) * dist), rng)
		end
	end,
}

----------------------------------------------------------------------------
-- 2. 화산 군도: 검은 현무암 섬, 부서진 사당, 용암 웅덩이, 가운데 화산
----------------------------------------------------------------------------
local volcano: Theme = {
	top = "basalt",
	fill = "dark_stone",
	deep = "basalt",
	flora = { "dead_bush" },
	floraChance = 0.05,
	centerChestRadius = 7,
	hut = function(api, at, ox, oz, rng)
		local ax, az = at(-2, 2)
		local bx, bz = at(2, 5)
		Shapes.house(api, ax, 0, az, bx, bz, {
			wall = "dark_bricks",
			corner = "basalt",
			floor = "dark_stone",
			roof = "dark_bricks",
			window = "stained_orange",
			door = doorFacing(ox, oz),
			height = 3,
			flat = true,
			trim = "magma",
			interior = false,
		})
		local lx, lz = at(-1, 4)
		api.set(lx, 1, lz, "torch")
		-- 집 옆 작은 용암 웅덩이 (테두리 있음)
		local px, pz = at(-4, 4)
		api.set(px, 0, pz, "lava")
		api.set(px, -1, pz, "magma")
		local _ = rng
	end,
	tree = function(api, x, y, z, rng)
		Shapes.spike(api, x, y, z, rng:int(3, 5), if rng:next() < 0.5 then "obsidian" else "magma")
	end,
	ruin = function(api, x, y, z, rng)
		for _, c in { { -2, -2 }, { 2, -2 }, { -2, 2 }, { 2, 2 } } do
			Shapes.ruinPillar(api, x + c[1], y, z + c[2], 4, "dark_bricks", rng)
		end
		api.set(x + 1, y + 1, z + 2, "torch")
	end,
	center = function(api, rng)
		-- 화산: 층층이 좁아지는 원뿔, 꼭대기 분화구에 용암, 옆으로 흐르는 용암 줄기
		for layer = 0, 9 do
			local r = 6 - layer * 0.55
			Shapes.disc(api, 0, 3 + layer, 0, r, function(dx, dz)
				local d = math.sqrt(dx * dx + dz * dz)
				if layer >= 7 and d < 1.6 then
					return "lava"
				end
				if (dx * 3 + dz * 5 + layer) % 7 == 0 then
					return "magma"
				end
				return if layer > 6 then "dark_stone" else "basalt"
			end)
		end
		for _, d in { { 1, 0 }, { -1, 0 } } do
			for k = 2, 5 do
				local y = 12 - k * 2
				api.set(d[1] * k, y, 0, "magma")
			end
		end
		-- 둘레의 흑요석 기둥
		for _, p in Shapes.ring(8, 9, 0.2) do
			Shapes.ruinPillar(api, p.x, 2, p.z, 5, "obsidian", rng)
		end
	end,
	sky = function(api, rng)
		for i = 1, 7 do
			local angle = i * (2 * math.pi / 7)
			local x, z = math.round(math.sin(angle) * 46), math.round(math.cos(angle) * 46)
			Shapes.spike(api, x, rng:int(-14, -8), z, rng:int(10, 15), "obsidian")
		end
	end,
}

----------------------------------------------------------------------------
-- 3. 버섯 숲: 버섯 집, 작은 거대버섯, 가운데 큰 버섯
----------------------------------------------------------------------------
local mushroom: Theme = {
	top = "mycelium",
	fill = "dirt",
	deep = "stone",
	flora = { "small_mushroom", "fern", "tall_grass", "flower_purple" },
	floraChance = 0.2,
	centerChestRadius = 4,
	hut = function(api, at, ox, oz, _rng)
		local ax, az = at(-2, 2)
		local bx, bz = at(2, 5)
		Shapes.house(api, ax, 0, az, bx, bz, {
			wall = "mushroom_stem",
			corner = "mushroom_brown",
			floor = "birch_planks",
			roof = "mushroom_red",
			window = "glass",
			door = doorFacing(ox, oz),
			height = 3,
			interior = false,
		})
		local lx, lz = at(-1, 4)
		api.set(lx, 1, lz, "shroomlight")
	end,
	tree = function(api, x, y, z, rng)
		Shapes.mushroom(api, x, y, z, rng:int(3, 4), 2, if rng:next() < 0.5 then "mushroom_red" else "mushroom_brown", rng)
	end,
	ruin = function(api, x, y, z, rng)
		Shapes.mushroom(api, x - 2, y, z + 2, 2, 1, "mushroom_brown", rng)
		Shapes.mushroom(api, x + 2, y, z - 2, 3, 2, "mushroom_red", rng)
	end,
	center = function(api, rng)
		for dx = -1, 1 do
			for dz = -1, 1 do
				for y = 3, 10 do
					api.set(dx, y, dz, "mushroom_stem")
				end
			end
		end
		Shapes.mushroom(api, 0, 2, 0, 9, 7, "mushroom_red", rng)
		for _, p in Shapes.ring(6, 8, 0.3) do
			Shapes.mushroom(api, p.x, 2, p.z, rng:int(2, 3), 1, "mushroom_brown", rng)
		end
	end,
	sky = function(api, rng)
		for i = 1, 5 do
			local angle = i * (2 * math.pi / 5) + 0.5
			local x, z = math.round(math.sin(angle) * 48), math.round(math.cos(angle) * 48)
			local y = rng:int(-4, 3)
			Shapes.island(api, x, y, z, { rx = 3, rz = 3, top = "mycelium", fill = "dirt", deep = "stone", depth = 4, edge = 0.2, drips = 0.1, rng = rng })
			Shapes.mushroom(api, x, y, z, rng:int(5, 7), 3, "mushroom_red", rng)
		end
	end,
}

return {
	{
		id = "cloud",
		name = "구름 정원",
		description = "오두막과 큰 나무 전망대가 있는 구름 위 숲",
		seed = 4401,
		build = build(cloud),
	},
	{
		id = "volcano",
		name = "화산 군도",
		description = "용암이 흐르는 화산과 부서진 사당",
		seed = 5503,
		build = build(volcano),
	},
	{
		id = "mushroom",
		name = "버섯 숲",
		description = "버섯 집과 거대한 버섯이 자라는 섬",
		seed = 6607,
		build = build(mushroom),
	},
}
