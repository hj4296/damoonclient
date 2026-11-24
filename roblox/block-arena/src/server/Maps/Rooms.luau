-- 게임 시작 전 대기실과, 탈락한 사람이 구경하는 관전실
local Shapes = require(script.Parent.Shapes)

local Rooms = {}

-- 대기실: 석영 기둥과 유리벽으로 된 방, 팀 색 깃발, 랜턴, 둘레를 떠다니는 작은 섬
function Rooms.waiting(api, rng)
	local R = 6
	for x = -R, R do
		for z = -R, R do
			local edge = math.abs(x) == R or math.abs(z) == R
			local corner = math.abs(x) == R and math.abs(z) == R
			local mid = edge and (x == 0 or z == 0)
			-- 바닥 무늬
			local d = math.max(math.abs(x), math.abs(z))
			local floor = if d <= 1 then "gold_block" elseif d % 2 == 0 then "quartz" else "polished_diorite"
			api.set(x, 0, z, if edge then "quartz_pillar" else floor)
			api.set(x, -1, z, "stone_bricks")
			if edge then
				for y = 1, 5 do
					api.set(x, y, z, if corner or mid then "quartz_pillar" else "cage_glass")
				end
				api.set(x, 6, z, "quartz")
			else
				api.set(x, 6, z, if (x + z) % 2 == 0 then "cage_glass" else "quartz")
			end
		end
	end
	-- 랜턴과 팀 색 깃발
	for _, c in { { -5, -5 }, { 5, -5 }, { -5, 5 }, { 5, 5 } } do
		api.set(c[1], 1, c[2], "lantern")
	end
	local wools = { "wool_red", "wool_blue", "wool_green", "wool_yellow" }
	for i, p in { { -3, 5, -5 }, { 3, 5, -5 }, { -3, 5, 5 }, { 3, 5, 5 } } do
		Shapes.banner(api, p[1], p[2], p[3], wools[i], 2)
	end
	-- 밖에서 보이는 섬 장식
	Shapes.island(api, 0, -2, 0, { rx = 8, rz = 8, top = "grass", fill = "dirt", deep = "stone", depth = 6, edge = 0.2, drips = 0.08, rng = rng })
	for x = -R, R do
		for z = -R, R do
			api.set(x, -1, z, "stone_bricks")
		end
	end
	api.marker("spawn", { x = 0, y = 1, z = 0 })
end

-- 관전실: 아래가 보이는 유리 바닥과 낮은 유리 담
function Rooms.spectator(api, _rng)
	for x = -4, 4 do
		for z = -4, 4 do
			local edge = math.abs(x) == 4 or math.abs(z) == 4
			api.set(x, 0, z, if edge then "quartz" else "cage_glass")
			if edge then
				for y = 1, 3 do
					api.set(x, y, z, if (math.abs(x) == 4 and math.abs(z) == 4) then "quartz_pillar" else "cage_glass")
				end
			end
		end
	end
	api.set(-3, 1, -3, "lantern")
	api.set(3, 1, 3, "lantern")
	api.marker("spawn", { x = 0, y = 1, z = 0 })
end

return Rooms
