-- TNT 런 맵 3종: 4층 바닥 + 둘레를 둘러싼 배경 건축물, 맨 아래는 용암.
-- 배경은 몇 개의 큰 파트(api.big / api.part)로 만들어서 파트 수가 적습니다. 바닥에서 닿을 수 없는 거리에 있습니다.
local Shapes = require(script.Parent.Shapes)

local FLOORS = { 0, -6, -12, -18 }

local function common(api)
	for _, y in FLOORS do
		api.marker("floor", y)
	end
	api.big(-30, -28, -30, 30, -28, 30, "lava")
	for _, p in Shapes.ring(12, 6, 0) do
		api.marker("spawn", { x = p.x, y = 1, z = p.z })
	end
end

----------------------------------------------------------------------------
-- 1. 무지개 탑: 동그란 무지개 바닥, 석영 기둥과 색유리 고리
----------------------------------------------------------------------------
local function rainbow(api, rng)
	local colors = { "color_red", "color_orange", "color_yellow", "color_lime", "color_cyan", "color_blue", "color_purple", "color_pink" }
	for floorIndex, y in FLOORS do
		Shapes.disc(api, 0, y, 0, 12, function(dx, dz)
			local band = math.floor(math.sqrt(dx * dx + dz * dz) / 2)
			return colors[(band + floorIndex) % #colors + 1]
		end)
	end
	common(api)
	local glass = { "stained_red", "stained_orange", "stained_yellow", "stained_lime", "stained_cyan", "stained_blue", "stained_purple", "stained_pink" }
	local count = 16
	local radius = 18
	for i = 0, count - 1 do
		local a = i * (2 * math.pi / count)
		local x, z = math.sin(a) * radius, math.cos(a) * radius
		api.part(x, -9, z, 2, 36, 2, "quartz_pillar")
		api.part(x, 9.5, z, 1, 1, 1, "lamp")
		-- 기둥 사이 색유리 판 (위쪽 두 줄)
		local b = (i + 0.5) * (2 * math.pi / count)
		local mx, mz = math.sin(b) * radius, math.cos(b) * radius
		local chord = 2 * radius * math.sin(math.pi / count) - 1.6
		local rot = CFrame.Angles(0, b, 0)
		api.part(mx, 6, mz, chord, 4, 0.25, glass[i % #glass + 1], { rotation = rot })
		api.part(mx, -6, mz, chord, 4, 0.25, glass[(i + 4) % #glass + 1], { rotation = rot })
		api.part(mx, 8.75, mz, chord + 1.6, 0.5, 1, "quartz", { rotation = rot })
	end
	-- 바깥 구름
	for i = 1, 6 do
		local a = i * math.pi / 3
		Shapes.cloud(api, math.round(math.sin(a) * 34), rng:int(-4, 8), math.round(math.cos(a) * 34), rng)
	end
end

----------------------------------------------------------------------------
-- 2. 체스판: 흑백 체스판 바닥, 성 안 같은 석재 벽과 기둥, 깃발, 큰 체스 말
----------------------------------------------------------------------------
local function rook(api, x: number, y: number, z: number, color: string)
	Shapes.box(api, x - 1, y, z - 1, x + 1, y, z + 1, color)
	for i = 1, 4 do
		api.set(x, y + i, z, color)
	end
	Shapes.box(api, x - 1, y + 5, z - 1, x + 1, y + 5, z + 1, color)
	for _, c in { { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } } do
		api.set(x + c[1], y + 6, z + c[2], color)
	end
end

local function king(api, x: number, y: number, z: number, color: string)
	Shapes.box(api, x - 1, y, z - 1, x + 1, y, z + 1, color)
	for i = 1, 5 do
		api.set(x, y + i, z, color)
	end
	Shapes.box(api, x - 1, y + 5, z - 1, x + 1, y + 5, z + 1, color)
	api.set(x, y + 6, z, color)
	api.set(x, y + 7, z, "gold_block")
	api.set(x - 1, y + 7, z, "gold_block")
	api.set(x + 1, y + 7, z, "gold_block")
	api.set(x, y + 8, z, "gold_block")
end

local function checker(api, _rng)
	for floorIndex, y in FLOORS do
		local a = if floorIndex % 2 == 1 then "color_white" else "color_red"
		local b = if floorIndex % 2 == 1 then "color_black" else "color_white"
		for x = -11, 11 do
			for z = -11, 11 do
				local tile = (math.floor((x + 12) / 3) + math.floor((z + 12) / 3)) % 2
				api.set(x, y, z, if tile == 0 then a else b)
			end
		end
	end
	common(api)
	local W = 17
	-- 네 벽 (안쪽에서 보이는 큰 석재 벽)
	api.big(-W, -8, -W, W, 8, -W, "stone_bricks")
	api.big(-W, -8, W, W, 8, W, "stone_bricks")
	api.big(-W, -8, -W + 1, -W, 8, W - 1, "stone_bricks")
	api.big(W, -8, -W + 1, W, 8, W - 1, "stone_bricks")
	-- 벽 아래쪽 이끼 낀 띠, 위쪽 장식 띠
	api.big(-W, -9, -W, W, -9, -W, "mossy_stone_bricks")
	api.big(-W, -9, W, W, -9, W, "mossy_stone_bricks")
	api.big(-W, -9, -W + 1, -W, -9, W - 1, "mossy_stone_bricks")
	api.big(W, -9, -W + 1, W, -9, W - 1, "mossy_stone_bricks")
	-- 기둥 (벽 안쪽으로 살짝 튀어나옴) 과 랜턴
	for _, t in { -12, -6, 0, 6, 12 } do
		for _, side in { { t, -W + 1 }, { t, W - 1 }, { -W + 1, t }, { W - 1, t } } do
			api.part(side[1], -9, side[2], 1, 36, 1, "polished_stone")
			api.part(side[1], 9.2, side[2], 1.2, 0.4, 1.2, "polished_stone")
		end
	end
	for _, t in { -9, -3, 3, 9 } do
		for _, side in { { t, -W + 0.4, 0 }, { t, W - 0.4, 0 }, { -W + 0.4, t, 1 }, { W - 0.4, t, 1 } } do
			local wool = if (t // 3) % 2 == 0 then "wool_red" else "wool_white"
			local sx = if side[3] == 1 then 0.15 else 1.6
			local sz = if side[3] == 1 then 1.6 else 0.15
			api.part(side[1], 4, side[2], sx, 6, sz, wool)
			api.part(side[1], 0.5, side[2], sx * 0.6 + 0.1, 0.6, sz * 0.6 + 0.1, "lantern", { collide = false })
		end
	end
	-- 벽 위 네 귀퉁이에 큰 체스 말
	rook(api, -W, 9, -W, "quartz")
	rook(api, W, 9, W, "dark_stone")
	king(api, W, 9, -W, "quartz")
	king(api, -W, 9, W, "dark_stone")
end

----------------------------------------------------------------------------
-- 3. 모래 폭포: TNT 위에 깔린 모래/자갈 바닥, 사방을 둘러싼 협곡 절벽과 모래 폭포
----------------------------------------------------------------------------
local function sands(api, rng)
	local radii = { 13, 11, 13, 10 }
	for floorIndex, y in FLOORS do
		Shapes.disc(api, 0, y, 0, radii[floorIndex], function(dx, dz)
			-- 군데군데 구멍
			if floorIndex > 1 and (dx * 7 + dz * 13 + floorIndex * 5) % 23 == 0 then
				return nil
			end
			return if rng:chance(0.3) then "gravel" else "sand"
		end)
		Shapes.disc(api, 0, y - 1, 0, radii[floorIndex], function(dx, dz)
			if floorIndex > 1 and (dx * 7 + dz * 13 + floorIndex * 5) % 23 == 0 then
				return nil
			end
			return "tnt"
		end)
	end
	common(api)
	-- 협곡 절벽: 높이와 거리가 다른 큰 바위 조각들을 빙 둘러 쌓음
	local layers = { "sandstone", "red_sandstone", "terracotta_orange", "sandstone", "terracotta_yellow", "red_sandstone" }
	local count = 20
	for i = 0, count - 1 do
		local a = i * (2 * math.pi / count)
		local r = 20 + rng:int(0, 3)
		local x, z = math.sin(a) * r, math.cos(a) * r
		local width = 2 * r * math.sin(math.pi / count) + 2
		local top = rng:int(4, 12)
		local y = -27
		local k = 1
		while y < top do
			local h = math.min(top - y, rng:int(3, 6))
			local inset = (k % 2) * 0.6
			api.part(x, y + h / 2, z, width - inset, h, 4 - inset, layers[(i + k) % #layers + 1], { rotation = CFrame.Angles(0, a, 0) })
			y += h
			k += 1
		end
		-- 절벽 위 선인장과 덤불
		local gx, gz = math.round(math.sin(a) * (r + 1)), math.round(math.cos(a) * (r + 1))
		if rng:next() < 0.5 then
			api.set(gx, top + 1, gz, "sand")
			Shapes.cactus(api, gx, top + 1, gz, rng:int(2, 3))
		else
			api.set(gx, top + 1, gz, "sand")
			api.set(gx, top + 2, gz, "dead_bush")
		end
		-- 가끔 절벽에서 흘러내리는 모래 폭포
		if i % 5 == 2 then
			local fx, fz = math.sin(a) * (r - 2.1), math.cos(a) * (r - 2.1)
			api.part(fx, (top - 27) / 2, fz, 1.6, top + 27, 0.6, "sand", { rotation = CFrame.Angles(0, a, 0), collide = false })
		end
	end
end

return {
	{
		id = "rainbow",
		name = "무지개 탑",
		description = "석영 기둥과 색유리로 둘러싼 무지개 바닥",
		seed = 7701,
		build = rainbow,
	},
	{
		id = "checker",
		name = "체스판",
		description = "성 안 홀, 흑백 체스판 바닥과 거대한 체스 말",
		seed = 8803,
		build = checker,
	},
	{
		id = "sands",
		name = "모래 폭포",
		description = "TNT 위 모래 바닥과 모래가 흘러내리는 협곡",
		seed = 9907,
		build = sands,
	},
}
