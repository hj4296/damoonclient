-- 맵 그리기 도구: 떠 있는 섬, 나무 여러 종류, 집, 탑, 지붕, 문, 분수, 가판대, 울타리 등.
-- 모두 api.set(x, y, z, 블록) / api.get / api.part / api.big 만 사용합니다. (y 는 땅 블록 높이 기준)
local Shapes = {}

----------------------------------------------------------------------------
-- 기본
----------------------------------------------------------------------------

-- 부드럽게 이어지는 값 노이즈 (0~1)
function Shapes.noise(seed: number): (number, number) -> number
	local s = math.floor(seed) % 2147483647
	local function hash(ix: number, iz: number): number
		local h = (ix * 374761 + iz * 668265 + s * 9176) % 2147483647
		h = (h * 48271) % 2147483647
		h = (h * 48271) % 2147483647
		return h / 2147483647
	end
	return function(x: number, z: number): number
		local ix, iz = math.floor(x), math.floor(z)
		local fx, fz = x - ix, z - iz
		fx = fx * fx * (3 - 2 * fx)
		fz = fz * fz * (3 - 2 * fz)
		local a = hash(ix, iz)
		local b = hash(ix + 1, iz)
		local c = hash(ix, iz + 1)
		local d = hash(ix + 1, iz + 1)
		return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz
	end
end

function Shapes.box(api, x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, id: string, hollow: boolean?)
	local lx, hx = math.min(x1, x2), math.max(x1, x2)
	local ly, hy = math.min(y1, y2), math.max(y1, y2)
	local lz, hz = math.min(z1, z2), math.max(z1, z2)
	for x = lx, hx do
		for y = ly, hy do
			for z = lz, hz do
				local edge = x == lx or x == hx or y == ly or y == hy or z == lz or z == hz
				if not hollow or edge then
					api.set(x, y, z, id)
				end
			end
		end
	end
end

function Shapes.clear(api, x1: number, y1: number, z1: number, x2: number, y2: number, z2: number)
	for x = math.min(x1, x2), math.max(x1, x2) do
		for y = math.min(y1, y2), math.max(y1, y2) do
			for z = math.min(z1, z2), math.max(z1, z2) do
				api.clear(x, y, z)
			end
		end
	end
end

function Shapes.pillar(api, x: number, y1: number, y2: number, z: number, id: string)
	for y = y1, y2 do
		api.set(x, y, z, id)
	end
end

-- 평평한 원판. id 가 함수면 (dx, dz) 마다 블록을 고름 (nil 이면 비움)
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

-- 둘레 위 같은 간격의 점들
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

-- 방향이 있는 구조물용 좌표계: s = 옆, f = 앞(바깥쪽). ox, oz 는 바깥 방향(축 방향)
function Shapes.frame(cx: number, cz: number, ox: number, oz: number)
	local lx, lz = oz, -ox
	return function(s: number, f: number): (number, number)
		return cx + lx * s + ox * f, cz + lz * s + oz * f
	end
end

-- (x, z) 칸에서 위에서부터 내려가며 처음 만나는 단단한 블록 높이
function Shapes.topAt(api, x: number, z: number, yMax: number, yMin: number): (number?, string?)
	for y = yMax, yMin, -1 do
		local id = api.get(x, y, z)
		if id then
			return y, id
		end
	end
	return nil, nil
end

----------------------------------------------------------------------------
-- 섬과 지형
----------------------------------------------------------------------------

export type IslandOptions = {
	rx: number,
	rz: number,
	top: string,
	fill: string,
	deep: string,
	depth: number?,
	fillDepth: number?,
	edge: number?,
	ores: boolean?,
	drips: number?,
	height: ((dx: number, dz: number, t: number) -> number)?,
	rng: any,
}

-- 위는 평평하고 아래로 갈수록 좁아지는 울퉁불퉁한 떠 있는 섬 (아래엔 돌과 광석, 고드름처럼 매달린 돌)
function Shapes.island(api, cx: number, cy: number, cz: number, o: IslandOptions)
	local rng = o.rng
	local noise = Shapes.noise(rng:int(1, 1000000))
	local edgeAmt = o.edge or 0.2
	local depthMax = o.depth or math.max(3, math.floor(math.min(o.rx, o.rz) * 0.75))
	local fillDepth = o.fillDepth or 2
	local R = math.ceil(math.max(o.rx, o.rz) * (1 + edgeAmt)) + 1
	for dx = -R, R do
		for dz = -R, R do
			local nx, nz = dx / o.rx, dz / o.rz
			local d = math.sqrt(nx * nx + nz * nz)
			local edge = 1 + (noise(dx * 0.17, dz * 0.17) - 0.5) * 2 * edgeAmt
			if d <= edge then
				local t = d / edge
				local h = if o.height then o.height(dx, dz, t) else 0
				local depth = math.floor((1 - t ^ 1.4) * depthMax + noise(dx * 0.4 + 40, dz * 0.4 - 20) * 2.2) + 1
				local x, z = cx + dx, cz + dz
				local top = cy + h
				api.set(x, top, z, o.top)
				for k = 1, depth + h do
					local id = o.fill
					if k > fillDepth then
						id = o.deep
						if o.ores and rng:next() < 0.05 then
							id = if rng:next() < 0.65 then "coal_ore" else "iron_ore"
						end
					end
					api.set(x, top - k, z, id)
				end
				if o.drips and t < 0.8 and rng:next() < o.drips then
					for k = 1, rng:int(1, 3) do
						api.set(x, top - depth - h - k, z, o.deep)
					end
				end
			end
		end
	end
end

-- 섬 위 풀밭에 꽃과 풀을 흩뿌림
function Shapes.flora(api, rng, x1: number, z1: number, x2: number, z2: number, yMax: number, yMin: number, ground: { [string]: boolean }, choices: { string }, chance: number)
	for x = math.min(x1, x2), math.max(x1, x2) do
		for z = math.min(z1, z2), math.max(z1, z2) do
			local y, id = Shapes.topAt(api, x, z, yMax, yMin)
			if y and id and ground[id] and api.get(x, y + 1, z) == nil and rng:next() < chance then
				api.set(x, y + 1, z, choices[rng:int(1, #choices)])
			end
		end
	end
end

-- 바닥 깔기 (위에서 보이는 블록만 바꿈)
function Shapes.paint(api, x1: number, z1: number, x2: number, z2: number, y: number, id: string | (number, number) -> string?)
	for x = math.min(x1, x2), math.max(x1, x2) do
		for z = math.min(z1, z2), math.max(z1, z2) do
			local block = if type(id) == "function" then id(x, z) else id
			if block then
				api.set(x, y, z, block)
				if api.get(x, y - 1, z) == nil then
					api.set(x, y - 1, z, "dirt")
				end
			end
		end
	end
end

----------------------------------------------------------------------------
-- 나무
----------------------------------------------------------------------------

local function blob(api, rng, cx: number, cy: number, cz: number, rx: number, ry: number, rz: number, id: string, holes: number?)
	for dx = -math.ceil(rx), math.ceil(rx) do
		for dy = -math.ceil(ry), math.ceil(ry) do
			for dz = -math.ceil(rz), math.ceil(rz) do
				local d = (dx / rx) ^ 2 + (dy / ry) ^ 2 + (dz / rz) ^ 2
				if d <= 1 and (d < 0.6 or rng:next() > (holes or 0.25)) then
					if api.get(cx + dx, cy + dy, cz + dz) == nil then
						api.set(cx + dx, cy + dy, cz + dz, id)
					end
				end
			end
		end
	end
end

function Shapes.oak(api, x: number, y: number, z: number, rng, opts: { log: string?, leaves: string?, height: number? }?)
	local o = opts or {}
	local log = o.log or "log"
	local leaves = o.leaves or "leaves"
	local h = o.height or rng:int(4, 6)
	local top = y + h
	blob(api, rng, x, top - 1, z, 2.6, 1.6, 2.6, leaves, 0.3)
	blob(api, rng, x, top + 1, z, 1.6, 1.2, 1.6, leaves, 0.2)
	for i = 1, h do
		api.set(x, y + i, z, log)
	end
end

function Shapes.birch(api, x: number, y: number, z: number, rng)
	local h = rng:int(5, 7)
	blob(api, rng, x, y + h - 1, z, 2.1, 2.2, 2.1, "birch_leaves", 0.25)
	for i = 1, h do
		api.set(x, y + i, z, "birch_log")
	end
end

-- 벚나무: 갈라진 가지와 넓게 퍼진 분홍 잎, 땅에 떨어진 꽃잎
function Shapes.sakura(api, x: number, y: number, z: number, rng, petals: boolean?)
	local h = rng:int(4, 5)
	for i = 1, h do
		api.set(x, y + i, z, "cherry_log")
	end
	local top = y + h
	local dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }
	local a = dirs[rng:int(1, 4)]
	local b = dirs[rng:int(1, 4)]
	for _, d in { a, b } do
		api.set(x + d[1], top, z + d[2], "cherry_log")
		api.set(x + d[1] * 2, top + 1, z + d[2] * 2, "cherry_log")
	end
	blob(api, rng, x, top + 1, z, 3.6, 1.8, 3.6, "sakura_leaves", 0.3)
	blob(api, rng, x + a[1] * 2, top + 2, z + a[2] * 2, 2.2, 1.3, 2.2, "sakura_leaves", 0.2)
	-- 아래로 늘어진 꽃잎 덩어리
	for _ = 1, 6 do
		local dx, dz = rng:int(-3, 3), rng:int(-3, 3)
		if api.get(x + dx, top, z + dz) == "sakura_leaves" and api.get(x + dx, top - 1, z + dz) == nil then
			api.set(x + dx, top - 1, z + dz, "sakura_leaves")
		end
	end
	if petals then
		for dx = -4, 4 do
			for dz = -4, 4 do
				if rng:next() < 0.22 then
					local gy, id = Shapes.topAt(api, x + dx, z + dz, y + 1, y - 2)
					if gy and id and (id == "grass" or id == "path") and api.get(x + dx, gy + 1, z + dz) == nil then
						api.set(x + dx, gy + 1, z + dz, "petals")
					end
				end
			end
		end
	end
end

-- 가문비나무 (눈 덮인 버전 가능)
function Shapes.spruce(api, x: number, y: number, z: number, rng, snowy: boolean?)
	local h = rng:int(7, 9)
	local radii = { 2, 2, 1, 2, 1, 1, 0 }
	local start = y + 2
	for i, r in radii do
		local ly = start + i - 1
		if ly > y + h then
			break
		end
		for dx = -r, r do
			for dz = -r, r do
				if math.abs(dx) + math.abs(dz) <= r + (if r >= 2 then 1 else 0) and not (dx == 0 and dz == 0) then
					api.set(x + dx, ly, z + dz, "dark_leaves")
					if snowy and api.get(x + dx, ly + 1, z + dz) == nil and (math.abs(dx) == r or math.abs(dz) == r) then
						api.set(x + dx, ly + 1, z + dz, "carpet_white")
					end
				end
			end
		end
	end
	for i = 1, h do
		api.set(x, y + i, z, "dark_log")
	end
	api.set(x, y + h + 1, z, "dark_leaves")
	if snowy then
		api.set(x, y + h + 2, z, "carpet_white")
	end
end

-- 야자수: 살짝 휜 줄기와 늘어진 잎
function Shapes.palm(api, x: number, y: number, z: number, rng, lean: { number }?)
	local h = rng:int(5, 7)
	local d = lean or { 1, 0 }
	local tx, tz = x, z
	for i = 1, h do
		if i == math.floor(h / 2) + 1 then
			tx += d[1]
			tz += d[2]
		end
		api.set(tx, y + i, tz, "log")
	end
	local top = y + h + 1
	api.set(tx, top, tz, "palm_leaves")
	for _, dir in { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { -1, -1 }, { 1, -1 }, { -1, 1 } } do
		local len = if dir[1] ~= 0 and dir[2] ~= 0 then 2 else 3
		for k = 1, len do
			local drop = if k == len then 1 else 0
			api.set(tx + dir[1] * k, top - drop, tz + dir[2] * k, "palm_leaves")
		end
	end
	if rng:next() < 0.7 then
		api.set(tx + 1, top - 1, tz, "wool_brown")
	end
end

function Shapes.bush(api, x: number, y: number, z: number, rng, leaves: string?)
	local id = leaves or "leaves"
	api.set(x, y + 1, z, id)
	for _, d in { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } } do
		if rng:next() < 0.6 then
			api.set(x + d[1], y + 1, z + d[2], id)
		end
	end
	if rng:next() < 0.5 then
		api.set(x, y + 2, z, id)
	end
end

function Shapes.cactus(api, x: number, y: number, z: number, height: number)
	for i = 1, height do
		api.set(x, y + i, z, "cactus")
	end
end

-- 위로 갈수록 가늘어지는 뾰족한 기둥 (얼음 가시, 바위)
function Shapes.spike(api, x: number, y: number, z: number, height: number, id: string)
	for i = 1, height do
		local r = math.floor((1 - i / height) * 1.8 + 0.4)
		for dx = -r, r do
			for dz = -r, r do
				if math.abs(dx) + math.abs(dz) <= r then
					api.set(x + dx, y + i, z + dz, id)
				end
			end
		end
	end
end

-- 큰 버섯 (아래에 버섯 조명)
function Shapes.mushroom(api, x: number, y: number, z: number, height: number, radius: number, cap: string?, rng)
	local capId = cap or "mushroom_red"
	local top = y + height + 1
	for dx = -radius, radius do
		for dz = -radius, radius do
			local d = math.sqrt(dx * dx + dz * dz)
			if d <= radius + 0.3 then
				api.set(x + dx, top, z + dz, capId)
				if d <= radius - 1.2 then
					api.set(x + dx, top + 1, z + dz, capId)
				end
				if d > radius - 1 then
					api.set(x + dx, top - 1, z + dz, capId)
				elseif rng and rng:next() < 0.12 then
					api.set(x + dx, top - 1, z + dz, "shroomlight")
				end
			end
		end
	end
	if capId == "mushroom_red" and rng then
		for _ = 1, radius + 2 do
			local dx, dz = rng:int(-radius + 1, radius - 1), rng:int(-radius + 1, radius - 1)
			if api.get(x + dx, top + 1, z + dz) == capId then
				api.set(x + dx, top + 1, z + dz, "wool_white")
			end
		end
	end
	for i = 1, height do
		api.set(x, y + i, z, "mushroom_stem")
	end
end

----------------------------------------------------------------------------
-- 장식
----------------------------------------------------------------------------

-- 가로등: 울타리 기둥 3칸 + 랜턴
function Shapes.lampPost(api, x: number, y: number, z: number, post: string?, light: string?)
	for i = 1, 3 do
		api.set(x, y + i, z, post or "dark_fence")
	end
	api.set(x, y + 4, z, light or "lantern")
end

-- 석등: 담장 기둥 + 반 블록 + 랜턴
function Shapes.stoneLantern(api, x: number, y: number, z: number)
	api.set(x, y + 1, z, "cobble_wall")
	api.set(x, y + 2, z, "stone_bricks_slab")
	api.set(x, y + 3, z, "lantern")
end

-- 울타리 줄 (기둥 + 가로대)
function Shapes.fenceLine(api, x1: number, z1: number, x2: number, z2: number, y: number, id: string?)
	local fence = id or "fence"
	local dx = if x2 > x1 then 1 elseif x2 < x1 then -1 else 0
	local dz = if z2 > z1 then 1 elseif z2 < z1 then -1 else 0
	local steps = math.max(math.abs(x2 - x1), math.abs(z2 - z1))
	local railId = if fence == "dark_fence" then "dark_planks" else "planks"
	for i = 0, steps do
		local x, z = x1 + dx * i, z1 + dz * i
		api.set(x, y, z, fence)
		if i < steps then
			local mx, mz = x + dx * 0.5, z + dz * 0.5
			local sx = if dx ~= 0 then 0.8 else 0.1
			local sz = if dz ~= 0 then 0.8 else 0.1
			api.part(mx, y + 0.25, mz, sx, 0.1, sz, railId)
			api.part(mx, y - 0.15, mz, sx, 0.1, sz, railId)
		end
	end
end

-- 가판대: 네 기둥과 줄무늬 천막
function Shapes.stall(api, x1: number, y: number, z1: number, x2: number, z2: number, canopy: string, stripe: string?)
	for _, c in { { x1, z1 }, { x2, z1 }, { x1, z2 }, { x2, z2 } } do
		for i = 1, 3 do
			api.set(c[1], y + i, c[2], "dark_fence")
		end
	end
	for x = math.min(x1, x2) - 1, math.max(x1, x2) + 1 do
		for z = math.min(z1, z2) - 1, math.max(z1, z2) + 1 do
			local stripeOn = stripe and (x + z) % 2 == 0
			api.set(x, y + 4, z, if stripeOn then stripe :: string else canopy)
		end
	end
end

-- 분수: 테두리 + 물 + 가운데 기둥
function Shapes.fountain(api, cx: number, y: number, cz: number, radius: number, rim: string, center: string, top: string?)
	for dx = -radius, radius do
		for dz = -radius, radius do
			local d = math.sqrt(dx * dx + dz * dz)
			if d <= radius + 0.4 then
				api.set(cx + dx, y, cz + dz, rim)
				if d > radius - 0.6 then
					api.set(cx + dx, y + 1, cz + dz, rim)
				else
					api.set(cx + dx, y + 1, cz + dz, "water")
				end
			end
		end
	end
	for i = 1, 3 do
		api.set(cx, y + i, cz, center)
	end
	api.set(cx, y + 4, cz, top or "lamp")
	for _, d in { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } } do
		api.set(cx + d[1], y + 3, cz + d[2], "water")
	end
end

-- 우물
function Shapes.well(api, x: number, y: number, z: number)
	for dx = -1, 1 do
		for dz = -1, 1 do
			if dx == 0 and dz == 0 then
				api.set(x, y, z, "water")
			else
				api.set(x + dx, y + 1, z + dz, "cobble")
			end
		end
	end
	for _, c in { { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } } do
		api.set(x + c[1], y + 2, z + c[2], "dark_fence")
		api.set(x + c[1], y + 3, z + c[2], "dark_fence")
	end
	Shapes.box(api, x - 1, y + 4, z - 1, x + 1, y + 4, z + 1, "dark_planks_slab")
end

-- 걸린 깃발 (양털 두세 칸)
function Shapes.banner(api, x: number, y: number, z: number, wool: string, length: number?)
	for i = 0, (length or 2) - 1 do
		api.set(x, y - i, z, wool)
	end
end

-- 구름 덩어리
function Shapes.cloud(api, x: number, y: number, z: number, rng)
	for _ = 1, rng:int(5, 9) do
		local cx = x + rng:int(-4, 4)
		local cz = z + rng:int(-3, 3)
		Shapes.disc(api, cx, y, cz, rng:int(1, 2), "wool_white")
		if rng:next() < 0.4 then
			Shapes.disc(api, cx, y + 1, cz, 1, "wool_white")
		end
	end
end

-- 두 점을 잇는 다리 (반 블록 바닥 + 난간)
function Shapes.bridge(api, x1: number, z1: number, x2: number, z2: number, y: number, floor: string, width: number?, rails: string?)
	local steps = math.max(math.abs(x2 - x1), math.abs(z2 - z1))
	local w = width or 1
	local alongX = math.abs(x2 - x1) >= math.abs(z2 - z1)
	for i = 0, steps do
		local t = if steps == 0 then 0 else i / steps
		local x = math.round(x1 + (x2 - x1) * t)
		local z = math.round(z1 + (z2 - z1) * t)
		for o = -(w // 2), w // 2 do
			if alongX then
				api.set(x, y, z + o, floor)
			else
				api.set(x + o, y, z, floor)
			end
		end
		if rails and i % 2 == 0 then
			local o = w // 2 + 1
			if alongX then
				api.set(x, y + 1, z - o, rails)
				api.set(x, y + 1, z + o, rails)
			else
				api.set(x - o, y + 1, z, rails)
				api.set(x + o, y + 1, z, rails)
			end
		end
	end
end

----------------------------------------------------------------------------
-- 건물
----------------------------------------------------------------------------

export type HouseOptions = {
	wall: string,
	corner: string,
	floor: string,
	roof: string, -- 지붕 블록
	roofSlab: string?, -- 지붕 꼭대기/처마에 쓰는 반 블록
	beam: string?,
	window: string?,
	door: string, -- "x-", "x+", "z-", "z+" 문이 난 쪽
	height: number?,
	flat: boolean?, -- 평평한 지붕 (사막집)
	trim: string?, -- 평지붕 둘레 장식
	interior: boolean?,
	accent: string?,
}

-- 직사각형 집: 바닥, 모서리 기둥, 창문, 문, 박공지붕(또는 평지붕), 안쪽 장식
function Shapes.house(api, x0: number, y: number, z0: number, x1: number, z1: number, o: HouseOptions)
	local lx, hx = math.min(x0, x1), math.max(x0, x1)
	local lz, hz = math.min(z0, z1), math.max(z0, z1)
	local h = o.height or 4
	-- 바닥과 기초
	for x = lx, hx do
		for z = lz, hz do
			api.set(x, y, z, o.floor)
			if api.get(x, y - 1, z) == nil then
				api.set(x, y - 1, z, o.corner)
			end
		end
	end
	-- 벽
	local cxDoor = math.floor((lx + hx) / 2)
	local czDoor = math.floor((lz + hz) / 2)
	for x = lx, hx do
		for z = lz, hz do
			local edgeX = x == lx or x == hx
			local edgeZ = z == lz or z == hz
			if edgeX or edgeZ then
				local corner = edgeX and edgeZ
				for i = 1, h do
					local id = if corner then o.corner else o.wall
					if not corner and i == h and o.beam then
						id = o.beam
					end
					-- 창문: 가운데 높이, 두 칸마다
					if not corner and i == 2 and h >= 3 then
						local along = if edgeX then z - lz else x - lx
						if along % 2 == 1 then
							id = o.window or "glass"
						end
					end
					api.set(x, y + i, z, id)
				end
			end
		end
	end
	-- 문 (2칸 높이)
	local doorX, doorZ = cxDoor, czDoor
	if o.door == "x-" then
		doorX = lx
	elseif o.door == "x+" then
		doorX = hx
	elseif o.door == "z-" then
		doorZ = lz
	else
		doorZ = hz
	end
	api.clear(doorX, y + 1, doorZ)
	api.clear(doorX, y + 2, doorZ)
	if o.accent then
		api.set(doorX, y + 3, doorZ, o.accent)
	end
	-- 지붕
	if o.flat then
		for x = lx, hx do
			for z = lz, hz do
				api.set(x, y + h + 1, z, o.roof)
				local edge = x == lx or x == hx or z == lz or z == hz
				if edge and ((x + z) % 2 == 0) then
					api.set(x, y + h + 2, z, o.trim or o.roof)
				end
			end
		end
	else
		local alongX = (hx - lx) >= (hz - lz)
		local a0, a1 = if alongX then lx - 1 else lz - 1, if alongX then hx + 1 else hz + 1
		local b0, b1 = if alongX then lz - 1 else lx - 1, if alongX then hz + 1 else hx + 1
		local half = (b1 - b0) / 2
		for i = 0, math.ceil(half) do
			local ry = y + h + i
			local slabTop = i == math.ceil(half) and (b1 - b0) % 2 == 0
			for a = a0, a1 do
				for _, b in { b0 + i, b1 - i } do
					local x, z = if alongX then a else b, if alongX then b else a
					if slabTop and o.roofSlab then
						api.set(x, ry, z, o.roofSlab)
					else
						api.set(x, ry, z, o.roof)
					end
				end
			end
			-- 박공 벽 채우기
			if i > 0 then
				for b = b0 + i + 1, b1 - i - 1 do
					for _, a in { a0 + 1, a1 - 1 } do
						local x, z = if alongX then a else b, if alongX then b else a
						api.set(x, ry, z, o.wall)
					end
				end
			end
		end
		-- 처마 끝을 반 블록으로
		if o.roofSlab then
			for a = a0, a1 do
				for _, b in { b0 - 1, b1 + 1 } do
					local x, z = if alongX then a else b, if alongX then b else a
					if api.get(x, y + h, z) == nil then
						api.set(x, y + h, z, o.roofSlab)
					end
				end
			end
		end
	end
	-- 안쪽: 랜턴, 책장, 카펫
	if o.interior ~= false and hx - lx >= 3 and hz - lz >= 3 then
		api.set(lx + 1, y + 1, lz + 1, "bookshelf")
		api.set(hx - 1, y + 1, hz - 1, "lantern")
		if hx - lx >= 4 then
			api.set(hx - 1, y + 1, lz + 1, "hay")
		end
	end
end

-- 둥근 탑: 벽, 창문, 꼭대기 톱니 모양 성가퀴, 원뿔 지붕(선택)
function Shapes.tower(api, cx: number, y: number, cz: number, radius: number, height: number, wall: string, opts: { roof: string?, battlement: boolean?, floor: string?, window: string? }?)
	local o = opts or {}
	for dx = -radius, radius do
		for dz = -radius, radius do
			local d = math.sqrt(dx * dx + dz * dz)
			if d <= radius + 0.4 then
				local outer = d > radius - 0.7
				api.set(cx + dx, y, cz + dz, o.floor or wall)
				for i = 1, height do
					if outer then
						local window = i % 4 == 2 and (dx == 0 or dz == 0)
						api.set(cx + dx, y + i, cz + dz, if window then o.window or "glass" else wall)
					end
				end
				if not outer then
					api.set(cx + dx, y + height, cz + dz, o.floor or wall)
				end
			end
		end
	end
	if o.battlement ~= false then
		for dx = -radius - 1, radius + 1 do
			for dz = -radius - 1, radius + 1 do
				local d = math.sqrt(dx * dx + dz * dz)
				if d > radius + 0.4 and d <= radius + 1.4 then
					api.set(cx + dx, y + height, cz + dz, wall)
					if (dx + dz) % 2 == 0 then
						api.set(cx + dx, y + height + 1, cz + dz, wall)
					end
				end
			end
		end
	end
	if o.roof then
		for i = 0, radius + 1 do
			local r = radius + 1 - i
			Shapes.disc(api, cx, y + height + 2 + i, cz, r, function(dx, dz)
				local d = math.sqrt(dx * dx + dz * dz)
				return if d > r - 1.2 then o.roof else nil
			end)
		end
		api.set(cx, y + height + radius + 4, cz, "gold_block")
	end
end

-- 성벽 한 줄 (높이 h, 꼭대기 톱니)
function Shapes.wall(api, x1: number, z1: number, x2: number, z2: number, y: number, h: number, id: string, battlement: string?)
	local dx = if x2 > x1 then 1 elseif x2 < x1 then -1 else 0
	local dz = if z2 > z1 then 1 elseif z2 < z1 then -1 else 0
	local steps = math.max(math.abs(x2 - x1), math.abs(z2 - z1))
	for i = 0, steps do
		local x, z = x1 + dx * i, z1 + dz * i
		for k = 1, h do
			api.set(x, y + k, z, id)
		end
		if i % 2 == 0 then
			api.set(x, y + h + 1, z, battlement or id)
		end
	end
end

-- 도리이 문 (axis "x": 문이 x 방향으로 넓음)
function Shapes.torii(api, x: number, y: number, z: number, axis: string, half: number, height: number)
	local pillar = "wool_red"
	local function at(o: number): (number, number)
		if axis == "x" then
			return x + o, z
		end
		return x, z + o
	end
	for _, o in { -half, half } do
		local px, pz = at(o)
		for i = 1, height do
			api.set(px, y + i, pz, pillar)
		end
		api.set(px, y, pz, "dark_stone")
	end
	for o = -half + 1, half - 1 do
		local px, pz = at(o)
		api.set(px, y + height - 1, pz, pillar)
	end
	for o = -half - 1, half + 1 do
		local px, pz = at(o)
		api.set(px, y + height + 1, pz, "dark_planks")
		if math.abs(o) == half + 1 then
			api.set(px, y + height + 2, pz, "dark_planks_slab")
		end
	end
	local mx, mz = at(0)
	api.set(mx, y + height, mz, "dark_planks")
end

-- 층마다 처마가 있는 탑 (탑 모양 정자)
function Shapes.pagoda(api, cx: number, y: number, cz: number, levels: number, size: number, opts: { wall: string, pillar: string, roof: string, floor: string })
	local yy = y
	for level = 1, levels do
		local s = size - (level - 1)
		if s < 1 then
			break
		end
		-- 기둥
		for _, c in { { -s, -s }, { s, -s }, { -s, s }, { s, s } } do
			for i = 1, 3 do
				api.set(cx + c[1], yy + i, cz + c[2], opts.pillar)
			end
		end
		-- 처마 (모서리가 살짝 올라감)
		for dx = -s - 1, s + 1 do
			for dz = -s - 1, s + 1 do
				local ring = math.max(math.abs(dx), math.abs(dz))
				if ring >= s then
					local cornerUp = math.abs(dx) == s + 1 and math.abs(dz) == s + 1
					api.set(cx + dx, yy + 4 + (if cornerUp then 1 else 0), cz + dz, opts.roof)
				elseif level < levels then
					api.set(cx + dx, yy + 4, cz + dz, opts.floor)
				end
			end
		end
		yy += 4
	end
	api.set(cx, yy + 1, cz, opts.roof)
	api.set(cx, yy + 2, cz, "gold_block")
end

-- 계단식 피라미드
function Shapes.pyramid(api, cx: number, y: number, cz: number, half: number, id: string, edge: string?)
	for i = 0, half do
		local r = half - i
		for dx = -r, r do
			for dz = -r, r do
				local border = math.abs(dx) == r or math.abs(dz) == r
				api.set(cx + dx, y + i, cz + dz, if border and edge then edge else id)
			end
		end
	end
end

-- 부서진 기둥 (폐허)
function Shapes.ruinPillar(api, x: number, y: number, z: number, height: number, id: string, rng)
	local h = math.max(1, height - rng:int(0, height // 2))
	for i = 1, h do
		api.set(x, y + i, z, if rng:next() < 0.2 then "mossy_cobble" else id)
	end
	if rng:next() < 0.5 then
		api.set(x + rng:int(-1, 1), y + 1, z + rng:int(-1, 1), id)
	end
end

return Shapes
