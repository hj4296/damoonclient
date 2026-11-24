-- 로비: 큰 떠 있는 섬 위의 마을.
--  남쪽: 스폰 광장과 큰길, 상점가(가판대, 집)
--  가운데: 분수 광장
--  북쪽: 게임 포털 3개(베드워즈, 스카이워즈, TNT 런)와 NPC, 뒤쪽 성벽과 탑
--  동쪽: 파쿠르 탑 (빙글빙글 올라가는 발판)
--  서쪽: 연못과 다리, 정자, 벚나무 정원
local Shapes = require(script.Parent.Shapes)

local Lobby = {}

Lobby.RadiusX = 30
Lobby.RadiusZ = 34

local GRASSY = { grass = true }

-- 포털 문 (문틀은 x 방향으로 넓고 남쪽을 봄)
local PORTALS = {
	{ mode = "bedwars", x = -12, z = 19, frame = { "clay_red", "wool_white" }, floor = { "wool_red", "quartz" } },
	{ mode = "skywars", x = 0, z = 21, frame = { "quartz_pillar", "quartz_pillar" }, floor = { "polished_diorite", "diamond_block" } },
	{ mode = "tntrun", x = 12, z = 19, frame = { "tnt", "tnt" }, floor = { "sand", "gravel" } },
}

local function portal(api, p, rng)
	local x, z = p.x, p.z
	-- 단: 한 칸 높이, 앞쪽 반 블록 계단
	for dx = -4, 4 do
		for dz = -2, 2 do
			api.set(x + dx, 1, z + dz, if (dx + dz) % 2 == 0 then p.floor[1] else p.floor[2])
		end
		api.set(x + dx, 1, z - 3, "polished_stone_slab")
	end
	-- 문틀
	for y = 2, 7 do
		local id = if y % 2 == 0 then p.frame[1] else p.frame[2]
		api.set(x - 2, y, z, id)
		api.set(x + 2, y, z, id)
	end
	for dx = -2, 2 do
		api.set(x + dx, 8, z, p.frame[1])
	end
	api.set(x - 3, 8, z, "polished_stone_slab")
	api.set(x + 3, 8, z, "polished_stone_slab")
	if p.mode == "bedwars" then
		api.set(x, 9, z, "wool_red")
		api.set(x - 4, 2, z + 1, "lantern")
		api.set(x + 4, 2, z + 1, "lantern")
	elseif p.mode == "skywars" then
		api.set(x - 2, 8, z, "diamond_block")
		api.set(x + 2, 8, z, "diamond_block")
		Shapes.cloud(api, x, 13, z, rng)
	else
		api.set(x - 2, 9, z, "tnt")
		api.set(x + 2, 9, z, "tnt")
		api.set(x + 4, 2, z + 1, "tnt")
		api.set(x - 4, 2, z + 1, "tnt")
	end
	api.marker("portal", { mode = p.mode, x = x, y = 2, z = z, w = 3, h = 6 })
	api.marker("npc", { mode = p.mode, x = x + 3, y = 2, z = z - 1 })
	api.marker("portalLabel", { mode = p.mode, x = x, y = 11, z = z })
end

function Lobby.build(api, rng)
	Shapes.island(api, 0, 0, 0, {
		rx = Lobby.RadiusX,
		rz = Lobby.RadiusZ,
		top = "grass",
		fill = "dirt",
		deep = "stone",
		depth = 12,
		edge = 0.1,
		ores = true,
		drips = 0.04,
		rng = rng,
	})

	-- 스폰 광장
	Shapes.disc(api, 0, 0, -24, 4.5, function(dx, dz)
		local d = math.sqrt(dx * dx + dz * dz)
		if d > 3.6 then
			return "gold_block"
		end
		return if (dx + dz) % 2 == 0 then "quartz" else "polished_diorite"
	end)
	-- 큰길 (남쪽 -> 광장, 광장 -> 북쪽)
	Shapes.paint(api, -2, -20, 2, -11, 0, "polished_stone")
	Shapes.paint(api, -3, -20, -3, -11, 0, "stone_bricks")
	Shapes.paint(api, 3, -20, 3, -11, 0, "stone_bricks")
	Shapes.paint(api, -2, 11, 2, 14, 0, "polished_stone")
	Shapes.paint(api, -11, -2, -14, 2, 0, "polished_stone")
	Shapes.paint(api, 11, -2, 14, 2, 0, "polished_stone")
	for _, z in { -19, -15 } do
		Shapes.lampPost(api, -4, 0, z)
		Shapes.lampPost(api, 4, 0, z)
	end

	-- 가운데 분수 광장
	Shapes.disc(api, 0, 0, 0, 11, function(dx, dz)
		local d = math.sqrt(dx * dx + dz * dz)
		if d <= 3.6 then
			return "polished_diorite"
		elseif d <= 4.6 then
			return "gold_block"
		elseif d > 10.2 then
			return "stone_bricks"
		end
		return if math.floor(d) % 2 == 0 then "polished_stone" else "stone_bricks"
	end)
	Shapes.fountain(api, 0, 0, 0, 3, "quartz", "quartz_pillar", "lamp")
	api.set(0, 5, 0, "gold_block")
	for _, p in Shapes.ring(8, 10, math.pi / 8) do
		Shapes.lampPost(api, p.x, 0, p.z)
	end
	-- 벤치 (반 블록) 네 개
	for _, b in { { 6, 6 }, { -6, 6 }, { 6, -6 }, { -6, -6 } } do
		local dx = if b[1] > 0 then 1 else -1
		api.set(b[1], 1, b[2], "dark_planks_slab")
		api.set(b[1] - dx, 1, b[2], "dark_planks_slab")
		api.set(b[1], 1, b[2] + (if b[2] > 0 then -1 else 1), "dark_planks_slab")
	end
	-- 광장 둘레 산울타리와 꽃
	for _, p in Shapes.ring(28, 12.5, 0) do
		local onPath = math.abs(p.x) <= 3 or math.abs(p.z) <= 3
		if not onPath then
			api.set(p.x, 1, p.z, "leaves")
		end
	end

	-- 북쪽 게임 홀 바닥
	Shapes.paint(api, -20, 13, 20, 25, 0, function(x, z)
		if (x + z) % 4 == 0 then
			return "stone_bricks"
		end
		return if math.abs(x) % 6 == 3 then "polished_granite" else "polished_stone"
	end)
	for _, p in PORTALS do
		portal(api, p, rng)
	end
	for _, x in { -18, -6, 6, 18 } do
		Shapes.lampPost(api, x, 0, 14)
	end

	-- 뒤쪽 성벽과 탑, 깃발
	Shapes.wall(api, -21, 28, 21, 28, 0, 7, "stone_bricks", "stone_bricks")
	Shapes.wall(api, -21, 29, 21, 29, 0, 6, "cobble", "cobble")
	for _, t in { { -24, 28 }, { 24, 28 }, { 0, 31 } } do
		Shapes.tower(api, t[1], 0, t[2], 3, if t[1] == 0 then 14 else 11, "stone_bricks", { roof = "dark_planks", floor = "dark_planks", window = "glass" })
	end
	local bannerColors = { "wool_red", "wool_cyan", "wool_orange", "wool_yellow", "wool_purple", "wool_lime" }
	for i, x in { -18, -12, -6, 6, 12, 18 } do
		Shapes.banner(api, x, 7, 27, bannerColors[i], 3)
	end

	-- 동쪽 파쿠르 탑
	local tx, tz = 22, -4
	Shapes.tower(api, tx, 0, tz, 3, 16, "stone_bricks", { floor = "dark_planks", window = "glass" })
	local jumps = {}
	local steps = 31
	for i = 0, steps - 1 do
		local a = math.rad(200) + i * 0.3
		local x = math.round(tx + math.sin(a) * 6)
		local z = math.round(tz + math.cos(a) * 6)
		local y = 1 + i // 2
		table.insert(jumps, { x, y, z })
	end
	local colors = { "color_lime", "color_cyan", "color_yellow", "color_pink" }
	for i, j in jumps do
		api.set(j[1], j[2], j[3], colors[(i - 1) % #colors + 1])
	end
	api.marker("parkour", jumps)
	api.marker("parkourStart", { x = jumps[1][1], y = 4, z = jumps[1][3] })
	api.marker("parkourEnd", { x = tx, y = 18, z = tz })
	api.set(tx, 17, tz, "gold_block")

	-- 서쪽 정원: 연못, 다리, 정자, 벚나무
	for dx = -5, 5 do
		for dz = -4, 4 do
			if (dx / 5.5) ^ 2 + (dz / 4.5) ^ 2 <= 1 then
				api.set(-19 + dx, 0, -2 + dz, "water")
				api.set(-19 + dx, -1, -2 + dz, "clay_block")
			end
		end
	end
	for _, lily in { { -21, -3 }, { -17, 0 }, { -20, 1 }, { -16, -4 } } do
		api.set(lily[1], 1, lily[2], "carpet_lime")
	end
	Shapes.bridge(api, -26, -2, -12, -2, 1, "dark_planks_slab", 3, "dark_fence")
	Shapes.pagoda(api, -18, 0, 11, 2, 2, { wall = "cherry_planks", pillar = "cherry_log", roof = "dark_planks", floor = "cherry_planks" })
	for _, t in { { -24, 7 }, { -12, 7 }, { -25, -11 }, { -12, -12 } } do
		Shapes.sakura(api, t[1], 0, t[2], rng, true)
	end
	Shapes.stoneLantern(api, -12, 0, 4)
	Shapes.stoneLantern(api, -12, 0, -5)

	-- 남쪽 상점가: 가판대와 집
	local stallColors = { "wool_red", "wool_blue", "wool_yellow", "wool_green" }
	for i, sx in { -15, -10, 10, 15 } do
		Shapes.stall(api, sx - 1, 0, -14, sx + 1, -12, stallColors[i], "wool_white")
		api.set(sx, 1, -13, if i % 2 == 0 then "hay" else "bookshelf")
	end
	Shapes.house(api, -21, 0, -21, -15, -16, { wall = "bricks", corner = "dark_log", floor = "dark_planks", roof = "dark_planks", roofSlab = "dark_planks_slab", beam = "dark_log", window = "glass", door = "x+", height = 4 })
	Shapes.house(api, 15, 0, -21, 21, -16, { wall = "birch_planks", corner = "log", floor = "planks", roof = "bricks", roofSlab = "bricks_slab", beam = "log", window = "glass", door = "x-", height = 4 })
	Shapes.well(api, 10, 0, -22)

	-- 가장자리 나무
	for _, p in Shapes.ring(16, 27, 0.15) do
		local nearParkour = (p.x - tx) ^ 2 + (p.z - tz) ^ 2 < 100
		if not nearParkour and api.get(p.x, 1, p.z) == nil and api.get(p.x, 0, p.z) == "grass" and math.abs(p.z) < 26 then
			if rng:next() < 0.5 then
				Shapes.oak(api, p.x, 0, p.z, rng)
			else
				Shapes.birch(api, p.x, 0, p.z, rng)
			end
		end
	end
	Shapes.flora(api, rng, -32, -36, 32, 36, 2, -1, GRASSY, { "tall_grass", "tall_grass", "flower_red", "flower_yellow", "flower_blue", "flower_white", "fern" }, 0.16)

	-- 둘레에 떠 있는 작은 섬
	for i = 1, 7 do
		local a = (i - 0.5) * (2 * math.pi / 7)
		local x, z = math.round(math.sin(a) * 52), math.round(math.cos(a) * 56)
		local y = rng:int(-8, 8)
		Shapes.island(api, x, y, z, { rx = 4, rz = 4, top = "grass", fill = "dirt", deep = "stone", depth = 5, edge = 0.25, drips = 0.1, rng = rng })
		if i % 2 == 0 then
			Shapes.sakura(api, x, y, z, rng, false)
		else
			Shapes.oak(api, x, y, z, rng)
		end
	end
	for i = 1, 8 do
		local a = i * (2 * math.pi / 8) + 0.3
		Shapes.cloud(api, math.round(math.sin(a) * 64), rng:int(18, 26), math.round(math.cos(a) * 64), rng)
	end

	api.marker("spawn", { x = 0, y = 1, z = -24, lookAt = { 0, 1, 0 } })
	api.marker("title", { x = 0, y = 12, z = 0 })
	api.marker("welcome", { x = 0, y = 6, z = -19 })
	api.marker("leaderboard", { x = -7, y = 5, z = -22 })
end

return Lobby
