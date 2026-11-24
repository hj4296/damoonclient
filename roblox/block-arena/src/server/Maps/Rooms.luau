-- 게임 시작 전 대기실과, 탈락한 사람이 구경하는 관전실
local Rooms = {}

function Rooms.waiting(api, _rng)
	for x = -5, 5 do
		for z = -5, 5 do
			local edge = math.abs(x) == 5 or math.abs(z) == 5
			if edge then
				for y = 1, 4 do
					api.set(x, y, z, "cage_glass")
				end
				api.set(x, 0, z, "quartz")
			else
				api.set(x, 0, z, if (x + z) % 2 == 0 then "quartz" else "stone_bricks")
			end
			api.set(x, 5, z, "cage_glass")
		end
	end
	for _, c in { { -4, -4 }, { 4, -4 }, { -4, 4 }, { 4, 4 } } do
		api.set(c[1], 1, c[2], "lamp")
	end
	api.marker("spawn", { x = 0, y = 1, z = 0 })
end

function Rooms.spectator(api, _rng)
	for x = -4, 4 do
		for z = -4, 4 do
			local edge = math.abs(x) == 4 or math.abs(z) == 4
			api.set(x, 0, z, "cage_glass")
			if edge then
				for y = 1, 3 do
					api.set(x, y, z, "cage_glass")
				end
			end
		end
	end
	api.marker("spawn", { x = 0, y = 1, z = 0 })
end

return Rooms
