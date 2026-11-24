-- TNT 런 맵 3종: 4층 바닥, 맨 아래는 용암.
local Shapes = require(script.Parent.Shapes)

local FLOORS = { 0, -6, -12, -18 }

local function base(api)
	for _, y in FLOORS do
		api.marker("floor", y)
	end
	-- 장식: 바깥쪽 조명 탑과 용암 바다 (닿을 수 없는 거리)
	api.big(-22, -27, -22, 22, -27, 22, "lava")
	for _, p in Shapes.ring(4, 19, math.pi / 4) do
		Shapes.pillar(api, p.x, -26, 3, p.z, "quartz")
		api.set(p.x, 4, p.z, "lamp")
	end
	for _, p in Shapes.ring(12, 6, 0) do
		api.marker("spawn", { x = p.x, y = 1, z = p.z })
	end
end

local function rainbow(api, _rng)
	local colors = { "color_red", "color_orange", "color_yellow", "color_lime", "color_cyan", "color_blue", "color_purple", "color_pink" }
	for floorIndex, y in FLOORS do
		Shapes.disc(api, 0, y, 0, 12, function(dx, dz)
			local band = math.floor(math.sqrt(dx * dx + dz * dz) / 2)
			return colors[(band + floorIndex) % #colors + 1]
		end)
	end
	base(api)
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
	base(api)
end

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
	end
	base(api)
end

return {
	{
		id = "rainbow",
		name = "무지개 탑",
		description = "층마다 색이 다른 동그란 무지개 바닥",
		seed = 7701,
		build = rainbow,
	},
	{
		id = "checker",
		name = "체스판",
		description = "흑백 체스판 무늬 네모 바닥",
		seed = 8803,
		build = checker,
	},
	{
		id = "sands",
		name = "모래 폭포",
		description = "구멍이 숭숭 뚫린 모래와 자갈 바닥",
		seed = 9907,
		build = sands,
	},
}
