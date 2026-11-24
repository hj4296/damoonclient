-- 스카이워즈 맵 3종: 시작 섬 8개, 중간 섬 4개, 좋은 상자가 있는 가운데 섬.
local Shapes = require(script.Parent.Shapes)

local ISLANDS = 8
local ISLAND_DISTANCE = 26
local ISLAND_RADIUS = 4
local MID_DISTANCE = 14

type Theme = {
	top: string,
	fill: string,
	bottom: string,
	accent: string,
	decorate: (api: any, x: number, y: number, z: number, rng: any) -> (),
	center: (api: any, rng: any) -> (),
	sky: ((api: any, rng: any) -> ())?,
	centerChestRadius: number?,
}

local function chest(api, x: number, y: number, z: number, tier: string)
	api.set(x, y, z, "chest")
	api.marker("chest", { x = x, y = y, z = z, tier = tier })
end

local function startIsland(api, theme: Theme, index: number, rng)
	local angle = (index - 1) * (2 * math.pi / ISLANDS)
	local ox, oz = math.sin(angle), math.cos(angle)
	local cx, cz = math.round(ox * ISLAND_DISTANCE) + 0, math.round(oz * ISLAND_DISTANCE) + 0
	Shapes.island(api, cx, 0, cz, ISLAND_RADIUS, { top = theme.top, fill = theme.fill, bottom = theme.bottom, depth = 4, rng = rng })
	-- 옆 방향(정수 격자에 맞춤)
	local lx, lz = math.round(oz * 2.4) + 0, math.round(-ox * 2.4) + 0
	local bx, bz = math.round(ox * 2.6) + 0, math.round(oz * 2.6) + 0
	chest(api, cx + lx, 1, cz + lz, "island")
	chest(api, cx - lx + bx, 1, cz - lz + bz, "island")
	theme.decorate(api, cx + bx + lx, 0, cz + bz + lz, rng)
	api.marker("spawn", { x = cx, y = 1, z = cz })
end

local function midIsland(api, theme: Theme, index: number, rng)
	local angle = math.pi / 4 + (index - 1) * (math.pi / 2)
	local cx = math.round(math.sin(angle) * MID_DISTANCE) + 0
	local cz = math.round(math.cos(angle) * MID_DISTANCE) + 0
	Shapes.island(api, cx, 1, cz, 3, { top = theme.top, fill = theme.fill, bottom = theme.bottom, depth = 3, rng = rng })
	chest(api, cx, 2, cz, "island")
	api.set(cx + 1, 1, cz, theme.accent)
end

local function build(theme: Theme)
	return function(api, rng)
		for i = 1, ISLANDS do
			startIsland(api, theme, i, rng)
		end
		for i = 1, 4 do
			midIsland(api, theme, i, rng)
		end
		Shapes.island(api, 0, 2, 0, 9, { top = theme.top, fill = theme.fill, bottom = theme.bottom, depth = 7, rng = rng })
		for _, p in Shapes.ring(4, theme.centerChestRadius or 3, math.pi / 4) do
			chest(api, p.x, 3, p.z, "center")
		end
		theme.center(api, rng)
		if theme.sky then
			theme.sky(api, rng)
		end
	end
end

local cloud: Theme = {
	top = "grass",
	fill = "dirt",
	bottom = "stone",
	accent = "planks",
	decorate = function(api, x, y, z, rng)
		Shapes.tree(api, x, y, z, { height = rng:int(3, 4) })
	end,
	center = function(api, _rng)
		-- 가운데 나무 집
		for _, p in Shapes.ring(4, 6, 0) do
			Shapes.pillar(api, p.x, 3, 5, p.z, "log")
		end
		for x = -6, 6 do
			for z = -6, 6 do
				local d = math.abs(x) + math.abs(z)
				if d == 6 then
					api.set(x, 6, z, "planks")
				end
			end
		end
		Shapes.tree(api, 0, 2, 0, { height = 5, wide = true })
	end,
	sky = function(api, rng)
		for i = 1, 8 do
			local angle = i * (math.pi / 4) + 0.3
			Shapes.cloud(api, math.round(math.sin(angle) * 40), rng:int(10, 16), math.round(math.cos(angle) * 40), rng)
		end
	end,
}

local volcano: Theme = {
	top = "basalt",
	fill = "basalt",
	bottom = "obsidian",
	accent = "magma",
	centerChestRadius = 7,
	decorate = function(api, x, y, z, rng)
		Shapes.spike(api, x, y, z, rng:int(2, 4), "magma")
	end,
	center = function(api, _rng)
		-- 가운데 화산 (꼭대기 분화구에 용암)
		for layer = 0, 6 do
			local r = 5 - layer * 0.7
			Shapes.disc(api, 0, 3 + layer, 0, r, function(dx, dz)
				local d = math.sqrt(dx * dx + dz * dz)
				if layer >= 5 and d < 1.2 then
					return "lava"
				end
				return if (dx + dz + layer) % 3 == 0 then "magma" else "basalt"
			end)
		end
	end,
	sky = function(api, rng)
		for i = 1, 6 do
			local angle = i * (math.pi / 3)
			local x, z = math.round(math.sin(angle) * 42), math.round(math.cos(angle) * 42)
			Shapes.spike(api, x, rng:int(-10, -4), z, rng:int(8, 12), "obsidian")
		end
	end,
}

local mushroom: Theme = {
	top = "grass",
	fill = "dirt",
	bottom = "stone",
	accent = "mushroom_stem",
	decorate = function(api, x, y, z, rng)
		Shapes.mushroom(api, x, y, z, rng:int(2, 3), 2)
	end,
	center = function(api, _rng)
		Shapes.mushroom(api, 0, 2, 0, 6, 5)
	end,
	sky = function(api, rng)
		for i = 1, 4 do
			local angle = i * (math.pi / 2) + math.pi / 4
			local x, z = math.round(math.sin(angle) * 40), math.round(math.cos(angle) * 40)
			Shapes.island(api, x, -2, z, 2, { top = "grass", fill = "dirt", bottom = "stone", depth = 2, rng = rng })
			Shapes.mushroom(api, x, -2, z, rng:int(4, 6), 3)
		end
	end,
}

return {
	{
		id = "cloud",
		name = "구름 정원",
		description = "구름 사이에 떠 있는 숲 섬",
		seed = 4401,
		build = build(cloud),
	},
	{
		id = "volcano",
		name = "화산 군도",
		description = "가운데에 화산이 솟은 검은 섬",
		seed = 5503,
		build = build(volcano),
	},
	{
		id = "mushroom",
		name = "버섯 숲",
		description = "거대한 버섯이 자라는 섬",
		seed = 6607,
		build = build(mushroom),
	},
}
