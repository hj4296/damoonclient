-- 맵을 그릴 때 쓰는 모양 도구 (섬, 나무, 기둥 등). api.set(x, y, z, 블록) 만 사용합니다.
local Shapes = {}

-- 위가 넓고 아래로 갈수록 좁아지는 떠 있는 섬
function Shapes.island(
	api,
	cx: number,
	cy: number,
	cz: number,
	radius: number,
	opts: { top: string, fill: string, bottom: string?, depth: number?, rng: any? }
)
	local depth = opts.depth or math.max(3, math.floor(radius * 0.8))
	local rng = opts.rng
	for layer = 0, depth do
		local r = radius - layer * (radius / (depth + 1)) * 1.1
		if layer == 0 then
			r = radius
		end
		local id = if layer == 0 then opts.top elseif layer <= 2 then opts.fill else (opts.bottom or opts.fill)
		local ri = math.ceil(r)
		for dx = -ri, ri do
			for dz = -ri, ri do
				local d = math.sqrt(dx * dx + dz * dz)
				local edge = r
				if rng and layer > 0 then
					edge += (rng:next() - 0.5) * 1.2
				end
				if d <= edge then
					api.set(cx + dx, cy - layer, cz + dz, id)
				end
			end
		end
	end
end

-- 평평한 원판
function Shapes.disc(api, cx: number, y: number, cz: number, radius: number, id: string | (number, number) -> string?)
	local ri = math.ceil(radius)
	for dx = -ri, ri do
		for dz = -ri, ri do
			if dx * dx + dz * dz <= radius * radius + 0.5 then
				local block = if type(id) == "function" then id(dx, dz) else id
				if block then
					api.set(cx + dx, y, cz + dz, block)
				end
			end
		end
	end
end

function Shapes.box(api, x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, id: string, hollow: boolean?)
	for x = math.min(x1, x2), math.max(x1, x2) do
		for y = math.min(y1, y2), math.max(y1, y2) do
			for z = math.min(z1, z2), math.max(z1, z2) do
				local edge = x == x1 or x == x2 or y == y1 or y == y2 or z == z1 or z == z2
				if not hollow or edge then
					api.set(x, y, z, id)
				end
			end
		end
	end
end

function Shapes.pillar(api, x: number, y1: number, y2: number, z: number, id: string)
	for y = y1, y2 do
		api.set(x, y, z, id)
	end
end

-- 나무: y 는 땅 블록 높이, 줄기는 그 위로 올라갑니다.
function Shapes.tree(api, x: number, y: number, z: number, opts: { log: string?, leaves: string?, height: number?, wide: boolean? }?)
	local o = opts or {}
	local log = o.log or "log"
	local leaves = o.leaves or "leaves"
	local h = o.height or 4
	for i = 1, h do
		api.set(x, y + i, z, log)
	end
	local top = y + h
	local r = if o.wide then 3 else 2
	for dy = -1, 1 do
		local rr = if dy == 1 then r - 1 else r
		for dx = -rr, rr do
			for dz = -rr, rr do
				local corner = math.abs(dx) == rr and math.abs(dz) == rr
				if not corner and not (dx == 0 and dz == 0 and dy < 1) then
					api.set(x + dx, top + dy, z + dz, leaves)
				end
			end
		end
	end
	api.set(x, top + 2, z, leaves)
end

function Shapes.cactus(api, x: number, y: number, z: number, height: number)
	for i = 1, height do
		api.set(x, y + i, z, "cactus")
	end
end

-- 위로 갈수록 가늘어지는 뾰족한 기둥 (얼음 가시, 바위)
function Shapes.spike(api, x: number, y: number, z: number, height: number, id: string)
	for i = 1, height do
		local r = math.floor((1 - i / height) * 1.6 + 0.4)
		for dx = -r, r do
			for dz = -r, r do
				if math.abs(dx) + math.abs(dz) <= r then
					api.set(x + dx, y + i, z + dz, id)
				end
			end
		end
	end
end

-- 큰 버섯
function Shapes.mushroom(api, x: number, y: number, z: number, height: number, radius: number)
	for i = 1, height do
		api.set(x, y + i, z, "mushroom_stem")
	end
	local top = y + height + 1
	Shapes.disc(api, x, top, z, radius, "mushroom_red")
	Shapes.disc(api, x, top + 1, z, radius - 1, "mushroom_red")
	for dx = -radius, radius do
		for dz = -radius, radius do
			local d = dx * dx + dz * dz
			if d <= radius * radius and d > (radius - 1) * (radius - 1) then
				api.set(x + dx, top - 1, z + dz, "mushroom_red")
			end
		end
	end
	-- 하얀 점
	api.set(x + 1, top + 2, z, "wool_white")
	api.set(x - 1, top + 1, z + radius - 2, "wool_white")
end

-- 가로등: 기둥 + 조명 블록
function Shapes.lamp(api, x: number, y: number, z: number, post: string?)
	local id = post or "dark_planks"
	api.set(x, y + 1, z, id)
	api.set(x, y + 2, z, id)
	api.set(x, y + 3, z, "lamp")
end

-- 두 점 사이를 잇는 다리 (가로 방향)
function Shapes.bridge(api, x1: number, z1: number, x2: number, z2: number, y: number, id: string, width: number?)
	local steps = math.max(math.abs(x2 - x1), math.abs(z2 - z1))
	local w = width or 1
	for i = 0, steps do
		local t = if steps == 0 then 0 else i / steps
		local x = math.round(x1 + (x2 - x1) * t)
		local z = math.round(z1 + (z2 - z1) * t)
		for o = -(w // 2), w // 2 do
			if math.abs(x2 - x1) >= math.abs(z2 - z1) then
				api.set(x, y, z + o, id)
			else
				api.set(x + o, y, z, id)
			end
		end
	end
end

-- 구름: 흰 블록 덩어리
function Shapes.cloud(api, x: number, y: number, z: number, rng)
	local n = rng:int(5, 9)
	for _ = 1, n do
		local cx = x + rng:int(-3, 3)
		local cz = z + rng:int(-2, 2)
		Shapes.disc(api, cx, y, cz, rng:int(1, 2), "wool_white")
	end
end

-- 섬 둘레를 돌며 하나씩 떨어진 위치
function Shapes.ring(count: number, radius: number, offsetAngle: number?): { { x: number, z: number, angle: number } }
	local out = {}
	for i = 0, count - 1 do
		local angle = (offsetAngle or 0) + i * (2 * math.pi / count)
		table.insert(out, {
			x = math.round(math.sin(angle) * radius) + 0, -- + 0 은 -0 을 0 으로 바꿈
			z = math.round(math.cos(angle) * radius) + 0,
			angle = angle,
		})
	end
	return out
end

return Shapes
