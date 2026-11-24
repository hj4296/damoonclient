-- 로비: 떠 있는 광장, 분수, 미니게임 NPC 세 개, 가장자리 파쿠르.
local Shapes = require(script.Parent.Shapes)

local Lobby = {}

Lobby.Radius = 18

function Lobby.build(api, rng)
	local R = Lobby.Radius
	Shapes.island(api, 0, 0, 0, R, { top = "grass", fill = "dirt", bottom = "stone", depth = 4, rng = rng })

	-- 광장 바닥 무늬
	Shapes.disc(api, 0, 0, 0, 13, function(dx, dz)
		local d = math.sqrt(dx * dx + dz * dz)
		if d <= 4.5 then
			return "quartz"
		elseif d <= 5.5 then
			return "gold_block"
		elseif d >= 12 then
			return "stone_bricks"
		end
		return if (dx + dz) % 2 == 0 then "stone_bricks" else "cobble"
	end)

	-- 분수
	for dx = -3, 3 do
		for dz = -3, 3 do
			local d = math.max(math.abs(dx), math.abs(dz))
			if d == 3 then
				api.set(dx, 1, dz, "stone_bricks")
			elseif d < 3 then
				api.set(dx, 1, dz, "water")
			end
		end
	end
	Shapes.pillar(api, 0, 1, 3, 0, "quartz")
	api.set(0, 4, 0, "lamp")

	-- 미니게임 NPC 받침대 (북쪽 반원)
	local stations = {
		{ mode = "bedwars", angle = math.rad(-38), block = "wool_red" },
		{ mode = "skywars", angle = 0, block = "diamond_block" },
		{ mode = "tntrun", angle = math.rad(38), block = "tnt" },
	}
	for _, station in stations do
		local x = math.round(math.sin(station.angle) * 10)
		local z = math.round(math.cos(station.angle) * 10)
		for dx = -1, 1 do
			for dz = -1, 1 do
				api.set(x + dx, 1, z + dz, if dx == 0 and dz == 0 then station.block else "quartz")
			end
		end
		Shapes.lamp(api, x - 2, 0, z + 1, "quartz")
		Shapes.lamp(api, x + 2, 0, z + 1, "quartz")
		api.marker("npc", { mode = station.mode, x = x, y = 2, z = z })
	end

	-- 바깥쪽 나무와 가로등
	for _, p in Shapes.ring(10, 16, 0.2) do
		if p.z < 8 then
			Shapes.tree(api, p.x, 0, p.z, { leaves = if (p.x + p.z) % 2 == 0 then "leaves" else "sakura_leaves", height = 4 })
		end
	end
	for _, p in Shapes.ring(8, 8, math.pi / 8) do
		if p.z < 5 then
			Shapes.lamp(api, p.x, 0, p.z)
		end
	end

	-- 파쿠르: 동쪽 가장자리에서 시작해 위로 올라가는 발판
	-- 한 칸씩 띄우고 많아야 한 칸씩 올라가서 누구나 넘을 수 있는 코스
	local jumps = {
		{ 19, 1, -3 },
		{ 21, 1, -3 },
		{ 23, 2, -3 },
		{ 23, 2, -1 },
		{ 23, 3, 1 },
		{ 21, 3, 1 },
		{ 19, 4, 1 },
		{ 19, 4, 3 },
		{ 19, 5, 5 },
		{ 21, 5, 5 },
		{ 23, 6, 5 },
	}
	for i, j in jumps do
		api.set(j[1], j[2], j[3], if i % 2 == 0 then "color_lime" else "color_cyan")
	end
	Shapes.box(api, 22, 6, 7, 24, 6, 9, "gold_block")
	api.set(24, 7, 9, "lamp")
	api.marker("parkourEnd", { x = 23, y = 7, z = 8 })

	api.marker("spawn", { x = 0, y = 1, z = -9, lookAt = { 0, 1, 10 } })
	api.marker("title", { x = 0, y = 9, z = 0 })
end

return Lobby
