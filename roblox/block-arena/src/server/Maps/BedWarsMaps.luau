-- 베드워즈 맵 3종. 4팀 섬 + 다이아몬드 섬 4개 + 에메랄드가 있는 가운데 섬 구조는 같고,
-- 테마(블록, 장식, 가운데 건물)가 다릅니다.
local Shapes = require(script.Parent.Shapes)

local TEAM_DIRS = {
	{ id = "red", ox = 0, oz = 1 },
	{ id = "blue", ox = 1, oz = 0 },
	{ id = "green", ox = 0, oz = -1 },
	{ id = "yellow", ox = -1, oz = 0 },
}

local TEAM_DISTANCE = 30
local TEAM_RADIUS = 7
local DIAMOND_DISTANCE = 18
local CENTER_RADIUS = 8

type Theme = {
	top: string,
	fill: string,
	bottom: string,
	accent: string,
	path: string,
	decorate: (api: any, x: number, y: number, z: number, rng: any) -> (),
	center: (api: any, rng: any) -> (),
}

local function teamIsland(api, theme: Theme, dir, rng)
	local ox, oz = dir.ox, dir.oz
	local lx, lz = oz, -ox -- 옆 방향
	local cx, cz = ox * TEAM_DISTANCE, oz * TEAM_DISTANCE
	local function at(s: number, f: number): (number, number)
		return cx + lx * s + ox * f, cz + lz * s + oz * f
	end

	Shapes.island(api, cx, 0, cz, TEAM_RADIUS, { top = theme.top, fill = theme.fill, bottom = theme.bottom, depth = 5, rng = rng })

	-- 자원 생성기 받침
	for s = -1, 1 do
		for f = 4, 6 do
			local x, z = at(s, f)
			api.set(x, 0, z, theme.accent)
		end
	end
	-- 침대 앞 길
	for f = -6, 3 do
		local x, z = at(0, f)
		api.set(x, 0, z, theme.path)
	end

	-- 장식
	for _, side in { -1, 1 } do
		local x, z = at(side * 4, -5)
		theme.decorate(api, x, 0, z, rng)
		local lx2, lz2 = at(side * 2, 3)
		Shapes.lamp(api, lx2, 0, lz2)
	end

	local spawnX, spawnZ = at(0, 1)
	local genX, genZ = at(0, 5)
	local footX, footZ = at(0, -2)
	local headX, headZ = at(0, -3)
	local shopX, shopZ = at(-4, 1)
	local upX, upZ = at(4, 1)
	local faceShopX, faceShopZ = at(0, 1)

	api.marker("team", {
		team = dir.id,
		spawn = { spawnX, 1, spawnZ },
		lookAt = { 0, 1, 0 },
		gen = { genX, 1, genZ },
		bedFoot = { footX, 1, footZ },
		bedHead = { headX, 1, headZ },
		shop = { shopX, 1, shopZ },
		upgrades = { upX, 1, upZ },
		npcLook = { faceShopX, 1, faceShopZ },
		center = { cx, 0, cz },
	})
end

local function diamondIsland(api, theme: Theme, x: number, z: number, rng)
	Shapes.island(api, x, 0, z, 3, { top = theme.top, fill = theme.fill, bottom = theme.bottom, depth = 3, rng = rng })
	api.set(x, 0, z, "diamond_block")
	api.marker("diamond", { x, 1, z })
	local sx = if x > 0 then -1 else 1
	Shapes.lamp(api, x + sx * 2, 0, z)
end

local function centerIsland(api, theme: Theme, rng)
	Shapes.island(api, 0, 0, 0, CENTER_RADIUS, { top = theme.top, fill = theme.fill, bottom = theme.bottom, depth = 6, rng = rng })
	Shapes.disc(api, 0, 0, 0, 3, theme.path)
	api.set(0, 0, -2, "emerald_block")
	api.set(0, 0, 2, "emerald_block")
	api.marker("emerald", { 0, 1, -2 })
	api.marker("emerald", { 0, 1, 2 })
	theme.center(api, rng)
end

-- 멀리 떠 있는 작은 바위섬 (풍경용)
local function scenery(api, theme: Theme, rng)
	for i = 1, 6 do
		local angle = i * (math.pi / 3) + rng:next() * 0.4
		local dist = rng:int(48, 58)
		local x = math.round(math.sin(angle) * dist)
		local z = math.round(math.cos(angle) * dist)
		local y = rng:int(-8, 6)
		Shapes.island(api, x, y, z, rng:int(2, 3), { top = theme.top, fill = theme.fill, bottom = theme.bottom, depth = 3, rng = rng })
	end
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
		scenery(api, theme, rng)
	end
end

local sakura: Theme = {
	top = "grass",
	fill = "dirt",
	bottom = "stone",
	accent = "planks",
	path = "gravel",
	decorate = function(api, x, y, z, _rng)
		Shapes.tree(api, x, y, z, { leaves = "sakura_leaves", height = 4, wide = true })
	end,
	center = function(api, _rng)
		-- 정자: 기둥 4개와 지붕
		for _, sx in { -4, 4 } do
			for _, sz in { -4, 4 } do
				Shapes.pillar(api, sx, 1, 4, sz, "dark_planks")
			end
		end
		for x = -5, 5 do
			for z = -5, 5 do
				local ring = math.max(math.abs(x), math.abs(z))
				if ring >= 3 then
					api.set(x, 5, z, if ring == 5 then "dark_planks" else "planks")
				end
			end
		end
		for x = -2, 2 do
			for z = -2, 2 do
				if math.max(math.abs(x), math.abs(z)) == 2 then
					api.set(x, 6, z, "dark_planks")
				end
			end
		end
		Shapes.tree(api, 6, 0, -3, { leaves = "sakura_leaves", height = 3 })
		Shapes.tree(api, -6, 0, 3, { leaves = "sakura_leaves", height = 3 })
	end,
}

local desert: Theme = {
	top = "sand",
	fill = "sandstone",
	bottom = "red_sandstone",
	accent = "sandstone",
	path = "red_sandstone",
	decorate = function(api, x, y, z, rng)
		Shapes.cactus(api, x, y, z, rng:int(2, 3))
	end,
	center = function(api, _rng)
		-- 사암 기둥이 둘러싼 신전
		for _, p in Shapes.ring(8, 6, math.pi / 8) do
			Shapes.pillar(api, p.x, 1, 4, p.z, "sandstone")
			api.set(p.x, 5, p.z, "gold_block")
		end
		for _, p in Shapes.ring(4, 4, math.pi / 4) do
			api.set(p.x, 1, p.z, "red_sandstone")
		end
	end,
}

local frost: Theme = {
	top = "snow",
	fill = "packed_ice",
	bottom = "stone",
	accent = "quartz",
	path = "stone_bricks",
	decorate = function(api, x, y, z, rng)
		if rng:chance(0.5) then
			Shapes.spike(api, x, y, z, rng:int(4, 6), "packed_ice")
		else
			Shapes.tree(api, x, y, z, { log = "dark_planks", leaves = "leaves", height = 5 })
		end
	end,
	center = function(api, rng)
		for _, p in Shapes.ring(4, 6, math.pi / 4) do
			Shapes.spike(api, p.x, 0, p.z, rng:int(5, 7), "ice")
		end
		-- 석영 아치 두 개
		for _, sx in { -1, 1 } do
			Shapes.pillar(api, sx * 3, 1, 4, 0, "quartz")
		end
		for x = -3, 3 do
			api.set(x, 5, 0, "quartz")
		end
		api.set(0, 6, 0, "lamp")
	end,
}

return {
	{
		id = "sakura",
		name = "벚꽃 정원",
		description = "분홍 꽃잎이 흩날리는 정원 섬",
		seed = 1101,
		build = build(sakura),
	},
	{
		id = "desert",
		name = "사막 신전",
		description = "모래바람 속 황금 기둥 신전",
		seed = 2203,
		build = build(desert),
	},
	{
		id = "frost",
		name = "얼음 성채",
		description = "얼음 가시가 솟은 눈 덮인 섬",
		seed = 3307,
		build = build(frost),
	},
}
