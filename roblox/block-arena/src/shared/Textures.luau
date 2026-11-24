-- 블록 픽셀 텍스처 (16x16). 이미지 파일 없이 코드로 직접 그린 이 게임만의 텍스처입니다.
-- Textures.get(이름) 은 {r, g, b, a, r, g, b, a, ...} (0~255, 1024개) 배열을 돌려줍니다.
local Blocks = require(script.Parent.Blocks)
local Util = require(script.Parent.Util)

local Textures = {}

local N = 16
Textures.Size = N

type Color = { number }
type Pixels = { number }

local function clamp8(v: number): number
	return math.clamp(math.floor(v + 0.5), 0, 255)
end

local function c3(color: Color3): Color
	return { color.R * 255, color.G * 255, color.B * 255 }
end

local function mul(c: Color, f: number): Color
	return { c[1] * f, c[2] * f, c[3] * f }
end

local function put(px: Pixels, x: number, y: number, c: Color, a: number?)
	local i = ((y % N) * N + (x % N)) * 4
	px[i + 1] = clamp8(c[1])
	px[i + 2] = clamp8(c[2])
	px[i + 3] = clamp8(c[3])
	px[i + 4] = a or 255
end

local function get(px: Pixels, x: number, y: number): Color
	local i = ((y % N) * N + (x % N)) * 4
	return { px[i + 1], px[i + 2], px[i + 3] }
end

local function alpha(px: Pixels, x: number, y: number, a: number)
	px[((y % N) * N + (x % N)) * 4 + 4] = a
end

-- 기본 색에 픽셀마다 밝기 흔들림을 준 바탕
local function base(c: Color, rng, amount: number): Pixels
	local px = table.create(N * N * 4, 255)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local f = 1 + (rng:next() - 0.5) * 2 * amount
			put(px, x, y, mul(c, f))
		end
	end
	return px
end

local function shadeAt(px: Pixels, x: number, y: number, f: number)
	put(px, x, y, mul(get(px, x, y), f))
end

-- 작은 얼룩 여러 개
local function blobs(px: Pixels, rng, count: number, color: Color, minSize: number, maxSize: number)
	for _ = 1, count do
		local cx, cy = rng:int(0, N - 1), rng:int(0, N - 1)
		local size = rng:int(minSize, maxSize)
		for dy = 0, size - 1 do
			for dx = 0, size - 1 do
				if rng:next() < 0.8 then
					put(px, cx + dx, cy + dy, mul(color, 0.92 + rng:next() * 0.16))
				end
			end
		end
	end
end

local function specks(px: Pixels, rng, chance: number, color: Color)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			if rng:next() < chance then
				put(px, x, y, mul(color, 0.9 + rng:next() * 0.2))
			end
		end
	end
end

-- 바둑판처럼 이어지는 보로노이 조각 (조약돌, 마그마 금)
local function cells(rng, count: number): (number, number) -> (number, boolean)
	local seeds = {}
	for i = 1, count do
		seeds[i] = { rng:next() * N, rng:next() * N }
	end
	local function nearest(x: number, y: number): number
		local best, bestD = 1, math.huge
		for i, s in seeds do
			local dx = math.abs(x + 0.5 - s[1])
			local dy = math.abs(y + 0.5 - s[2])
			dx = math.min(dx, N - dx)
			dy = math.min(dy, N - dy)
			local d = dx * dx + dy * dy
			if d < bestD then
				best, bestD = i, d
			end
		end
		return best
	end
	return function(x: number, y: number): (number, boolean)
		local id = nearest(x, y)
		local edge = nearest(x + 1, y) ~= id or nearest(x, y + 1) ~= id
		return id, edge
	end
end

local function bevel(px: Pixels, light: Color, dark: Color)
	for i = 0, N - 1 do
		put(px, i, 0, light)
		put(px, 0, i, light)
		put(px, i, N - 1, dark)
		put(px, N - 1, i, dark)
	end
end

local function fringe(px: Pixels, rng, color: Color, minDepth: number, maxDepth: number, amount: number)
	for x = 0, N - 1 do
		local depth = rng:int(minDepth, maxDepth)
		for y = 0, depth - 1 do
			put(px, x, y, mul(color, 1 + (rng:next() - 0.5) * 2 * amount))
		end
	end
end

-- 3x5 픽셀 글자
local FONT = {
	T = { "###", ".#.", ".#.", ".#.", ".#." },
	N = { "#..#", "##.#", "#.##", "#..#", "#..#" },
}
local function text(px: Pixels, word: string, x0: number, y0: number, c: Color)
	local x = x0
	for i = 1, #word do
		local glyph = FONT[string.sub(word, i, i)]
		if glyph then
			for row, line in glyph do
				for col = 1, #line do
					if string.sub(line, col, col) == "#" then
						put(px, x + col - 1, y0 + row - 1, c)
					end
				end
			end
			x += #glyph[1] + 1
		end
	end
end

local G = {}

-- 땅
function G.dirt(rng)
	local px = base({ 134, 96, 67 }, rng, 0.08)
	specks(px, rng, 0.12, { 98, 70, 48 })
	specks(px, rng, 0.06, { 162, 122, 88 })
	return px
end
function G.grass_top(rng)
	local px = base({ 96, 150, 58 }, rng, 0.1)
	specks(px, rng, 0.16, { 76, 128, 44 })
	specks(px, rng, 0.08, { 124, 178, 72 })
	return px
end
function G.grass_side(rng)
	local px = G.dirt(rng)
	fringe(px, rng, { 96, 150, 58 }, 2, 4, 0.1)
	for x = 0, N - 1 do
		if rng:next() < 0.25 then
			put(px, x, rng:int(3, 5), { 88, 140, 52 })
		end
	end
	return px
end
function G.path_top(rng)
	local px = base({ 150, 124, 76 }, rng, 0.07)
	specks(px, rng, 0.14, { 126, 100, 60 })
	return px
end
function G.mycelium_top(rng)
	local px = base({ 118, 98, 118 }, rng, 0.1)
	specks(px, rng, 0.12, { 150, 136, 150 })
	specks(px, rng, 0.08, { 92, 76, 92 })
	return px
end
function G.mycelium_side(rng)
	local px = G.dirt(rng)
	fringe(px, rng, { 118, 98, 118 }, 2, 4, 0.1)
	return px
end
function G.snow(rng)
	local px = base({ 242, 248, 252 }, rng, 0.025)
	specks(px, rng, 0.08, { 222, 234, 246 })
	return px
end
function G.snow_side(rng)
	local px = G.dirt(rng)
	fringe(px, rng, { 242, 248, 252 }, 3, 5, 0.025)
	return px
end
function G.sand(rng)
	local px = base({ 220, 208, 158 }, rng, 0.05)
	specks(px, rng, 0.1, { 200, 186, 136 })
	specks(px, rng, 0.05, { 236, 226, 184 })
	return px
end
function G.red_sand(rng)
	local px = base({ 190, 104, 50 }, rng, 0.06)
	specks(px, rng, 0.12, { 166, 88, 40 })
	return px
end
function G.gravel(rng)
	local palette = { { 112, 106, 104 }, { 138, 130, 126 }, { 160, 154, 150 }, { 92, 88, 86 } }
	local px = base({ 130, 124, 120 }, rng, 0)
	for y = 0, N - 1, 2 do
		for x = 0, N - 1, 2 do
			local c = palette[rng:int(1, #palette)]
			for dy = 0, 1 do
				for dx = 0, 1 do
					put(px, x + dx, y + dy, mul(c, 0.92 + rng:next() * 0.16))
				end
			end
		end
	end
	return px
end
function G.ice(rng)
	local px = base({ 150, 192, 245 }, rng, 0.03)
	for i = 0, N - 1 do
		if i % 5 ~= 0 then
			put(px, i, (i * 2) % N, { 200, 226, 255 })
		end
	end
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			alpha(px, x, y, 220)
		end
	end
	return px
end
function G.packed_ice(rng)
	local px = base({ 130, 170, 232 }, rng, 0.05)
	local split = cells(rng, 6)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local _, edge = split(x, y)
			if edge then
				put(px, x, y, { 176, 206, 250 })
			end
		end
	end
	return px
end
function G.clay_block(rng)
	return base({ 160, 166, 178 }, rng, 0.03)
end

-- 돌
function G.stone(rng)
	local px = base({ 126, 126, 126 }, rng, 0.06)
	blobs(px, rng, 8, { 104, 104, 104 }, 1, 3)
	blobs(px, rng, 5, { 146, 146, 146 }, 1, 2)
	return px
end
function G.cobble(rng)
	local px = base({ 120, 120, 120 }, rng, 0)
	local split = cells(rng, 9)
	local shades = {}
	for i = 1, 9 do
		shades[i] = 0.78 + rng:next() * 0.42
	end
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local id, edge = split(x, y)
			if edge then
				put(px, x, y, { 68, 68, 70 })
			else
				put(px, x, y, mul({ 124, 124, 124 }, shades[id] * (0.94 + rng:next() * 0.12)))
			end
		end
	end
	return px
end
local function moss(px: Pixels, rng, amount: number)
	for _ = 1, amount do
		local x, y = rng:int(0, N - 1), rng:int(0, N - 1)
		for dy = 0, 2 do
			for dx = 0, 2 do
				if rng:next() < 0.55 then
					put(px, x + dx, y + dy, mul({ 86, 118, 56 }, 0.9 + rng:next() * 0.2))
				end
			end
		end
	end
end
function G.mossy_cobble(rng)
	local px = G.cobble(rng)
	moss(px, rng, 7)
	return px
end
local function brickRows(px: Pixels, rng, rowHeight: number, brickWidth: number, brick: Color, mortar: Color, jitter: number)
	for y = 0, N - 1 do
		local row = y // rowHeight
		local offset = if row % 2 == 0 then 0 else brickWidth // 2
		for x = 0, N - 1 do
			local inRow = y % rowHeight
			local col = (x + offset) % brickWidth
			if inRow == rowHeight - 1 or col == brickWidth - 1 then
				put(px, x, y, mul(mortar, 0.95 + rng:next() * 0.1))
			else
				local f = 1 + (rng:next() - 0.5) * jitter
				if inRow == 0 then
					f *= 1.08
				end
				put(px, x, y, mul(brick, f))
			end
		end
	end
end
function G.stone_bricks(rng)
	local px = base({ 122, 122, 122 }, rng, 0)
	brickRows(px, rng, 8, 16, { 124, 124, 124 }, { 80, 80, 82 }, 0.12)
	blobs(px, rng, 4, { 108, 108, 108 }, 1, 2)
	return px
end
function G.mossy_stone_bricks(rng)
	local px = G.stone_bricks(rng)
	moss(px, rng, 6)
	return px
end
function G.dark_bricks(rng)
	local px = base({ 52, 46, 56 }, rng, 0)
	brickRows(px, rng, 4, 8, { 58, 52, 62 }, { 30, 26, 32 }, 0.16)
	return px
end
function G.bricks(rng)
	local px = base({ 170, 160, 150 }, rng, 0)
	brickRows(px, rng, 4, 8, { 150, 72, 56 }, { 178, 168, 158 }, 0.2)
	return px
end
function G.polished_stone(rng)
	local px = base({ 160, 160, 162 }, rng, 0.025)
	bevel(px, { 184, 184, 186 }, { 128, 128, 130 })
	return px
end
function G.polished_granite(rng)
	local px = base({ 160, 110, 92 }, rng, 0.06)
	specks(px, rng, 0.12, { 184, 132, 112 })
	specks(px, rng, 0.08, { 126, 84, 70 })
	bevel(px, { 182, 132, 112 }, { 128, 84, 70 })
	return px
end
function G.polished_diorite(rng)
	local px = base({ 198, 198, 200 }, rng, 0.04)
	specks(px, rng, 0.1, { 140, 140, 144 })
	bevel(px, { 222, 222, 224 }, { 160, 160, 164 })
	return px
end
local function ore(rng, color: Color)
	local px = G.stone(rng)
	for _ = 1, 4 do
		local x, y = rng:int(1, N - 3), rng:int(1, N - 3)
		put(px, x, y, color)
		put(px, x + 1, y, mul(color, 0.85))
		put(px, x, y + 1, mul(color, 0.9))
		if rng:next() < 0.6 then
			put(px, x + 1, y + 1, mul(color, 1.1))
		end
	end
	return px
end
function G.coal_ore(rng)
	return ore(rng, { 40, 40, 42 })
end
function G.iron_ore(rng)
	return ore(rng, { 214, 176, 146 })
end
function G.gold_ore(rng)
	return ore(rng, { 250, 214, 70 })
end
function G.quartz(rng)
	local px = base({ 236, 230, 222 }, rng, 0.015)
	bevel(px, { 246, 242, 236 }, { 214, 208, 200 })
	return px
end
function G.quartz_pillar(rng)
	local px = base({ 234, 228, 220 }, rng, 0.015)
	for y = 0, N - 1 do
		for _, x in { 0, 3, 12, 15 } do
			put(px, x, y, { 212, 206, 198 })
		end
	end
	return px
end
function G.quartz_pillar_top(rng)
	local px = base({ 234, 228, 220 }, rng, 0.015)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local d = math.max(math.abs(x - 7.5), math.abs(y - 7.5))
			if d > 7 or (d > 3 and d < 4) then
				put(px, x, y, { 212, 206, 198 })
			end
		end
	end
	return px
end
function G.sandstone_top(rng)
	local px = base({ 218, 204, 150 }, rng, 0.03)
	specks(px, rng, 0.06, { 204, 188, 136 })
	return px
end
local function bandedSide(rng, c: Color)
	local px = base(c, rng, 0.03)
	for x = 0, N - 1 do
		put(px, x, 0, mul(c, 1.08))
		put(px, x, 1, mul(c, 1.05))
		put(px, x, 3, mul(c, 0.86))
		put(px, x, 12, mul(c, 0.86))
		for _, y in { 6, 9 } do
			if rng:next() < 0.5 then
				put(px, x, y, mul(c, 0.94))
			end
		end
	end
	return px
end
function G.sandstone_side(rng)
	return bandedSide(rng, { 214, 198, 144 })
end
function G.sandstone_carved(rng)
	local c = { 214, 198, 144 }
	local px = bandedSide(rng, c)
	local dark = mul(c, 0.78)
	-- 해 무늬: 가운데 원과 네 방향 빛살
	for y = 4, 11 do
		for x = 4, 11 do
			local dx, dy = x - 7.5, y - 7.5
			local d = math.sqrt(dx * dx + dy * dy)
			if d > 2.2 and d < 3.4 then
				put(px, x, y, dark)
			end
		end
	end
	for _, p in { { 7, 4 }, { 8, 4 }, { 7, 11 }, { 8, 11 }, { 4, 7 }, { 4, 8 }, { 11, 7 }, { 11, 8 } } do
		put(px, p[1], p[2], dark)
	end
	put(px, 7, 7, dark)
	put(px, 8, 8, dark)
	return px
end
function G.red_sandstone_top(rng)
	local px = base({ 188, 102, 48 }, rng, 0.04)
	specks(px, rng, 0.06, { 168, 88, 40 })
	return px
end
function G.red_sandstone_side(rng)
	return bandedSide(rng, { 186, 100, 46 })
end
function G.basalt_side(rng)
	local px = base({ 72, 70, 76 }, rng, 0)
	for x = 0, N - 1 do
		local f = 0.82 + rng:next() * 0.3
		for y = 0, N - 1 do
			put(px, x, y, mul({ 72, 70, 76 }, f * (0.95 + rng:next() * 0.1)))
		end
	end
	return px
end
function G.basalt_top(rng)
	local px = base({ 72, 70, 76 }, rng, 0.05)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local d = math.max(math.abs(x - 7.5), math.abs(y - 7.5))
			if math.floor(d) % 3 == 0 then
				shadeAt(px, x, y, 0.82)
			end
		end
	end
	return px
end
function G.dark_stone(rng)
	local px = base({ 46, 42, 50 }, rng, 0.1)
	specks(px, rng, 0.1, { 62, 58, 68 })
	return px
end
function G.magma(rng)
	local px = base({ 112, 38, 22 }, rng, 0.08)
	local split = cells(rng, 7)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local _, edge = split(x, y)
			if edge then
				put(px, x, y, if rng:next() < 0.4 then { 255, 200, 90 } else { 250, 130, 40 })
			end
		end
	end
	return px
end
function G.obsidian(rng)
	local px = base({ 30, 22, 44 }, rng, 0.1)
	specks(px, rng, 0.08, { 70, 48, 104 })
	specks(px, rng, 0.03, { 110, 80, 150 })
	return px
end
function G.bedrock(rng)
	local palette = { { 40, 40, 42 }, { 70, 70, 72 }, { 96, 96, 98 }, { 56, 56, 58 } }
	local px = base({ 60, 60, 62 }, rng, 0)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			put(px, x, y, palette[rng:int(1, #palette)])
		end
	end
	return px
end

-- 나무
local function planks(rng, c: Color)
	local px = base(c, rng, 0)
	for board = 0, 3 do
		local f = 0.92 + rng:next() * 0.16
		local seam = rng:int(2, 13)
		for y = board * 4, board * 4 + 3 do
			for x = 0, N - 1 do
				local v = f * (0.95 + rng:next() * 0.1)
				if y == board * 4 + 3 then
					v *= 0.72
				elseif x == seam then
					v *= 0.76
				elseif rng:next() < 0.08 then
					v *= 0.86
				end
				put(px, x, y, mul(c, v))
			end
		end
	end
	return px
end
function G.planks(rng)
	return planks(rng, { 164, 132, 82 })
end
function G.dark_planks(rng)
	return planks(rng, { 90, 64, 40 })
end
function G.birch_planks(rng)
	return planks(rng, { 198, 180, 126 })
end
function G.cherry_planks(rng)
	return planks(rng, { 224, 172, 168 })
end
local function bark(rng, c: Color)
	local px = base(c, rng, 0)
	for x = 0, N - 1 do
		local f = 0.8 + rng:next() * 0.32
		for y = 0, N - 1 do
			local v = f * (0.94 + rng:next() * 0.12)
			if rng:next() < 0.05 then
				v *= 0.7
			end
			put(px, x, y, mul(c, v))
		end
	end
	return px
end
local function rings(rng, wood: Color, barkColor: Color)
	local px = base(wood, rng, 0.03)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local d = math.max(math.abs(x - 7.5), math.abs(y - 7.5))
			if d > 6.6 then
				put(px, x, y, mul(barkColor, 0.9 + rng:next() * 0.2))
			elseif math.floor(d) % 2 == 1 then
				shadeAt(px, x, y, 0.84)
			end
		end
	end
	return px
end
function G.log_side(rng)
	return bark(rng, { 104, 82, 50 })
end
function G.log_top(rng)
	return rings(rng, { 172, 138, 86 }, { 104, 82, 50 })
end
function G.dark_log_side(rng)
	return bark(rng, { 62, 46, 30 })
end
function G.dark_log_top(rng)
	return rings(rng, { 110, 80, 50 }, { 62, 46, 30 })
end
function G.cherry_log_side(rng)
	return bark(rng, { 72, 42, 52 })
end
function G.cherry_log_top(rng)
	return rings(rng, { 220, 160, 160 }, { 72, 42, 52 })
end
function G.birch_log_side(rng)
	local px = base({ 220, 218, 208 }, rng, 0.04)
	for _ = 1, 9 do
		local y = rng:int(0, N - 1)
		local x = rng:int(0, N - 1)
		for dx = 0, rng:int(1, 4) do
			put(px, x + dx, y, { 46, 44, 42 })
		end
	end
	return px
end
function G.birch_log_top(rng)
	return rings(rng, { 206, 190, 136 }, { 220, 218, 208 })
end
function G.bookshelf(rng)
	local px = G.planks(rng)
	local colors = { { 150, 40, 40 }, { 40, 70, 150 }, { 50, 120, 50 }, { 140, 100, 40 }, { 120, 50, 130 }, { 200, 170, 60 } }
	for _, rowStart in { 2, 9 } do
		local x = 1
		while x < N - 1 do
			local width = rng:int(1, 2)
			local c = colors[rng:int(1, #colors)]
			local top = rowStart + rng:int(0, 1)
			for dx = 0, width - 1 do
				if x + dx < N - 1 then
					for y = top, rowStart + 4 do
						put(px, x + dx, y, mul(c, if y == top then 1.2 else 0.95 + rng:next() * 0.1))
					end
				end
			end
			x += width
		end
	end
	return px
end
function G.hay_side(rng)
	local px = base({ 196, 166, 50 }, rng, 0)
	for x = 0, N - 1 do
		local f = 0.86 + rng:next() * 0.26
		for y = 0, N - 1 do
			put(px, x, y, mul({ 196, 166, 50 }, f * (0.95 + rng:next() * 0.1)))
		end
	end
	for _, y in { 3, 4, 11, 12 } do
		for x = 0, N - 1 do
			put(px, x, y, mul({ 150, 70, 36 }, 0.9 + rng:next() * 0.2))
		end
	end
	return px
end
function G.hay_top(rng)
	local px = base({ 200, 170, 56 }, rng, 0.12)
	specks(px, rng, 0.2, { 160, 130, 40 })
	return px
end

-- 잎, 식물
local function leaves(rng, c: Color)
	local px = base(c, rng, 0.12)
	specks(px, rng, 0.2, mul(c, 0.75))
	specks(px, rng, 0.08, mul(c, 1.2))
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			if rng:next() < 0.14 then
				alpha(px, x, y, 0)
			end
		end
	end
	return px
end
function G.leaves(rng)
	return leaves(rng, { 62, 128, 44 })
end
function G.dark_leaves(rng)
	return leaves(rng, { 46, 92, 46 })
end
function G.birch_leaves(rng)
	return leaves(rng, { 110, 150, 70 })
end
function G.palm_leaves(rng)
	return leaves(rng, { 92, 160, 56 })
end
function G.sakura_leaves(rng)
	local px = leaves(rng, { 240, 172, 204 })
	specks(px, rng, 0.06, { 252, 220, 236 })
	return px
end
function G.cactus_side(rng)
	local px = base({ 70, 128, 48 }, rng, 0.05)
	for _, x in { 1, 5, 10, 14 } do
		for y = 0, N - 1 do
			put(px, x, y, { 52, 100, 36 })
			if rng:next() < 0.18 then
				put(px, x, y, { 214, 226, 180 })
			end
		end
	end
	return px
end
function G.cactus_top(rng)
	local px = base({ 76, 134, 52 }, rng, 0.05)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local d = math.max(math.abs(x - 7.5), math.abs(y - 7.5))
			if math.floor(d) == 5 then
				put(px, x, y, { 98, 156, 70 })
			end
		end
	end
	return px
end
function G.mushroom_red(rng)
	local px = base({ 190, 44, 40 }, rng, 0.06)
	for _ = 1, 5 do
		local cx, cy = rng:int(0, N - 1), rng:int(0, N - 1)
		for dy = -1, 1 do
			for dx = -1, 1 do
				if math.abs(dx) + math.abs(dy) < 2 or rng:next() < 0.3 then
					put(px, cx + dx, cy + dy, { 238, 234, 226 })
				end
			end
		end
	end
	return px
end
function G.mushroom_brown(rng)
	local px = base({ 150, 112, 80 }, rng, 0.08)
	specks(px, rng, 0.1, { 126, 92, 64 })
	return px
end
function G.mushroom_stem(rng)
	local px = base({ 222, 214, 198 }, rng, 0.03)
	for x = 0, N - 1, 3 do
		for y = 0, N - 1 do
			shadeAt(px, x, y, 0.93)
		end
	end
	return px
end
function G.petals(rng)
	local px = table.create(N * N * 4, 0)
	for _ = 1, 18 do
		local x, y = rng:int(0, N - 1), rng:int(0, N - 1)
		put(px, x, y, { 246, 176, 208 }, 255)
		if rng:next() < 0.6 then
			put(px, x + 1, y, { 236, 156, 192 }, 255)
		end
	end
	return px
end

-- 광물 블록
local function metal(rng, c: Color)
	local px = base(c, rng, 0.03)
	bevel(px, mul(c, 1.15), mul(c, 0.78))
	for i = 2, N - 3 do
		put(px, i, 2, mul(c, 0.9))
		put(px, 2, i, mul(c, 0.9))
		put(px, i, N - 3, mul(c, 1.08))
		put(px, N - 3, i, mul(c, 1.08))
	end
	return px
end
function G.iron_block(rng)
	return metal(rng, { 216, 216, 220 })
end
function G.gold_block(rng)
	local px = metal(rng, { 246, 206, 62 })
	specks(px, rng, 0.05, { 255, 238, 140 })
	return px
end
function G.diamond_block(rng)
	local px = metal(rng, { 100, 222, 212 })
	for i = 4, 11 do
		put(px, i, i, { 200, 252, 246 })
		put(px, i, N - 1 - i, { 160, 244, 236 })
	end
	return px
end
function G.emerald_block(rng)
	local px = metal(rng, { 48, 196, 96 })
	for y = 4, 11 do
		for x = 4, 11 do
			if math.abs(x - 7.5) + math.abs(y - 7.5) < 3.5 then
				put(px, x, y, { 120, 236, 150 })
			end
		end
	end
	return px
end

-- 유리, 물
function G.glass(_rng)
	local px = table.create(N * N * 4, 0)
	for i = 0, N - 1 do
		put(px, i, 0, { 236, 246, 252 }, 230)
		put(px, 0, i, { 236, 246, 252 }, 230)
		put(px, i, N - 1, { 200, 220, 232 }, 230)
		put(px, N - 1, i, { 200, 220, 232 }, 230)
	end
	for _, p in { { 3, 6 }, { 4, 5 }, { 5, 4 }, { 6, 3 }, { 4, 8 }, { 5, 7 }, { 6, 6 }, { 7, 5 }, { 8, 4 } } do
		put(px, p[1], p[2], { 250, 252, 255 }, 170)
	end
	return px
end
function G.water(rng)
	local px = base({ 56, 108, 214 }, rng, 0.04)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			if (x + y * 3) % 11 == 0 then
				put(px, x, y, { 110, 160, 240 })
			end
			alpha(px, x, y, 190)
		end
	end
	return px
end

-- TNT, 상자, 침대
function G.tnt_side(rng)
	local px = base({ 204, 56, 44 }, rng, 0.05)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			if x % 4 == 3 then
				shadeAt(px, x, y, 0.72)
			end
		end
	end
	for y = 4, 11 do
		for x = 0, N - 1 do
			put(px, x, y, if y == 4 or y == 11 then { 200, 200, 196 } else { 238, 238, 232 })
		end
	end
	text(px, "TNT", 2, 5, { 30, 30, 30 })
	return px
end
function G.tnt_top(rng)
	local px = base({ 204, 56, 44 }, rng, 0.05)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local dx, dy = (x % 4) - 1.5, (y % 4) - 1.5
			if dx * dx + dy * dy > 2.6 then
				shadeAt(px, x, y, 0.7)
			end
		end
	end
	for _, p in { { 7, 7 }, { 8, 7 }, { 7, 8 }, { 8, 8 } } do
		put(px, p[1], p[2], { 70, 70, 70 })
	end
	return px
end
function G.tnt_bottom(rng)
	local px = G.tnt_top(rng)
	for _, p in { { 7, 7 }, { 8, 7 }, { 7, 8 }, { 8, 8 } } do
		put(px, p[1], p[2], { 150, 40, 32 })
	end
	return px
end
local function chestBase(rng)
	local px = planks(rng, { 160, 112, 54 })
	bevel(px, { 70, 46, 20 }, { 70, 46, 20 })
	return px
end
function G.chest_side(rng)
	local px = chestBase(rng)
	for x = 0, N - 1 do
		put(px, x, 5, { 70, 46, 20 })
	end
	return px
end
function G.chest_front(rng)
	local px = G.chest_side(rng)
	for y = 4, 7 do
		for x = 7, 8 do
			put(px, x, y, if y == 4 or y == 7 then { 60, 60, 64 } else { 170, 170, 176 })
		end
	end
	return px
end
function G.chest_top(rng)
	return chestBase(rng)
end
function G.bed_side(rng)
	local px = planks(rng, { 160, 112, 54 })
	for y = 0, 5 do
		for x = 0, N - 1 do
			put(px, x, y, mul({ 232, 232, 228 }, 0.95 + rng:next() * 0.08))
		end
	end
	return px
end

-- 사다리, 강화 석재, 스펀지, 럭키 블록, 개인 상자
function G.ladder(rng)
	local px = table.create(N * N * 4, 0)
	local wood = { 150, 116, 64 }
	for y = 0, N - 1 do
		for _, x in { 2, 13 } do
			put(px, x, y, mul(wood, 0.85 + rng:next() * 0.15))
			put(px, x + (if x == 2 then -1 else 1), y, mul(wood, 0.7))
		end
	end
	for _, y in { 2, 6, 10, 14 } do
		for x = 2, 13 do
			put(px, x, y, mul(wood, 0.95 + rng:next() * 0.1))
		end
	end
	return px
end
function G.hardstone(rng)
	local px = base({ 224, 222, 172 }, rng, 0.04)
	specks(px, rng, 0.12, { 196, 192, 140 })
	local split = cells(rng, 5)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			local _, edge = split(x, y)
			if edge and rng:next() < 0.5 then
				put(px, x, y, { 200, 196, 146 })
			end
		end
	end
	return px
end
function G.sponge(rng)
	local px = base({ 204, 194, 72 }, rng, 0.06)
	for _ = 1, 14 do
		local x, y = rng:int(0, N - 2), rng:int(0, N - 2)
		put(px, x, y, { 150, 140, 40 })
		if rng:next() < 0.5 then
			put(px, x + 1, y, { 170, 160, 50 })
		end
	end
	return px
end
local QUESTION = {
	"..####..",
	".##..##.",
	".....##.",
	"....##..",
	"...##...",
	"...##...",
	"........",
	"...##...",
}
function G.lucky_block(rng)
	local px = base({ 250, 206, 46 }, rng, 0.03)
	bevel(px, { 255, 236, 120 }, { 190, 140, 20 })
	for row, line in QUESTION do
		for col = 1, 8 do
			if string.sub(line, col, col) == "#" then
				put(px, 3 + col, 3 + row, { 90, 50, 10 })
			end
		end
	end
	return px
end
local function enderBase(rng)
	local px = base({ 40, 30, 64 }, rng, 0.08)
	specks(px, rng, 0.08, { 60, 46, 90 })
	bevel(px, { 30, 140, 120 }, { 20, 90, 80 })
	return px
end
function G.ender_chest_top(rng)
	return enderBase(rng)
end
function G.ender_chest_side(rng)
	local px = enderBase(rng)
	for x = 0, N - 1 do
		put(px, x, 5, { 20, 90, 80 })
	end
	return px
end
function G.ender_chest_front(rng)
	local px = G.ender_chest_side(rng)
	for y = 4, 7 do
		for x = 7, 8 do
			put(px, x, y, if y == 4 or y == 7 then { 20, 90, 80 } else { 60, 220, 190 })
		end
	end
	return px
end

-- 색 블록들 (양털: 뜨개 무늬, 테라코타: 부드러운 흙, 콘크리트: 매끈)
local function knit(rng, c: Color)
	local px = base(c, rng, 0.03)
	for y = 0, N - 1 do
		for x = 0, N - 1 do
			if (x + 2 * y) % 4 == 0 then
				shadeAt(px, x, y, 0.9)
			elseif (x - y) % 4 == 0 then
				shadeAt(px, x, y, 1.07)
			end
		end
	end
	return px
end

local function generator(name: string): ((any) -> Pixels)?
	local direct = G[name]
	if direct then
		return direct
	end
	local wool = string.match(name, "^wool_(.+)$")
	if wool then
		local def = Blocks.get("wool_" .. wool)
		if def then
			local c = c3(def.color)
			return function(rng)
				return knit(rng, c)
			end
		end
	end
	local terracotta = string.match(name, "^terracotta_(.+)$")
	if terracotta then
		local def = Blocks.get("terracotta_" .. terracotta)
		if def then
			local c = c3(def.color)
			return function(rng)
				local px = base(c, rng, 0.035)
				specks(px, rng, 0.05, mul(c, 0.9))
				return px
			end
		end
	end
	local concrete = string.match(name, "^concrete_(.+)$")
	if concrete then
		local color = Blocks.ColorValues[concrete]
		if color then
			local c = c3(color)
			return function(rng)
				return base(c, rng, 0.018)
			end
		end
	end
	return nil
end

local function seedOf(name: string): number
	local h = 7
	for i = 1, #name do
		h = (h * 31 + string.byte(name, i)) % 2147483647
	end
	return h + 1
end

local cache: { [string]: Pixels } = {}

function Textures.has(name: string): boolean
	return generator(name) ~= nil
end

function Textures.get(name: string): Pixels?
	local cached = cache[name]
	if cached then
		return cached
	end
	local gen = generator(name)
	if not gen then
		return nil
	end
	local px = gen(Util.rng(seedOf(name)))
	cache[name] = px
	return px
end

-- 블록 정의가 쓰는 모든 텍스처 이름
function Textures.names(): { string }
	local seen = {}
	local out = {}
	for _, def in Blocks.defs do
		for _, face in { "top", "side", "bottom", "front" } do
			local name = Blocks.faceTexture(def, face)
			if name and not seen[name] then
				seen[name] = true
				table.insert(out, name)
			end
		end
	end
	table.sort(out)
	return out
end

return Textures
